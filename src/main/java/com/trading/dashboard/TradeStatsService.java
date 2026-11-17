package com.trading.dashboard;

import com.trading.bucket.StrategyBucket;
import com.trading.order.OrderHistoryRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 거래 기준 성적 — <b>추정치다.</b> 매수 체결가는 정확하지만 매도는
 * {@link SellPriceEstimator}가 캔들에서 되짚은 값이다(CLAUDE.md 결함 5).
 *
 * <p>정확한 금액의 정본은 {@link AccountPerformanceService}({@code /api/performance/account})이고,
 * 여기서 보는 것은 "어느 종목·어느 칸에서 벌고 잃었나"라는 <b>분포</b>다.
 *
 * <p>읽기 전용이다 — 원본 테이블을 고치지 않고, KIS도 부르지 않는다.
 *
 * <p>기간 처리: 짝짓기는 <b>항상 전 기간</b> 주문으로 하고, {@code days}는 <b>매도 시각</b>
 * 기준으로 결과를 자른다. 주문부터 잘라내면 기간 앞의 매수가 사라져 멀쩡한 거래가
 * "짝 없음(측정 불가)"으로 둔갑한다.
 */
@Component
@Profile("paper")
public class TradeStatsService {

    private static final int MAX_DAYS = 3650;

    /**
     * 짝짓기 결과 캐시 수명. 화면이 60초마다 이 API 3종을 폴링하므로, 없으면 요청마다
     * 체결 주문 전량 + 분봉을 다시 훑는다. 거래는 하루 몇 건 늘어날 뿐이라 30초 지연은 무해하다.
     * 스프링 캐시 추상화를 들이지 않고 이 클래스 안에서 끝낸다 (감사 24_audit M-3).
     */
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    /** days 값이 제각각인 요청이 들어와도 캐시가 무한히 늘지 않게 하는 상한 */
    private static final int MAX_CACHE_ENTRIES = 16;

    static final String WARNING =
            "매도 가격은 추정치입니다 — 증권사 모의계좌가 매도 체결가를 주지 않습니다(CLAUDE.md 결함 5). "
            + "정확한 금액은 계좌 기준(/api/performance/account)을 보세요";

    static final String MATCHING_RULE =
            "칸 우선 선입선출(FIFO) — 종목별로, 매도 직전 마지막 매수의 칸 조각부터 오래된 순으로 매도와 짝짓는다"
            + "(같은 종목은 한 번에 한 칸만 보유). 모자라면 다른 칸 조각을 쓰고 칸 넘김(crossBucketPieces)으로 센다. "
            + "매도 한 건이 매수 여러 건을 덮으면 거래도 그만큼 나뉜다. 칸은 매수 쪽 것을 쓴다";

    private final OrderHistoryRepository orderHistoryRepository;
    private final SellPriceEstimator sellPriceEstimator;
    private final Clock clock;

    public TradeStatsService(OrderHistoryRepository orderHistoryRepository,
                             SellPriceEstimator sellPriceEstimator,
                             Clock clock) {
        this.orderHistoryRepository = orderHistoryRepository;
        this.sellPriceEstimator = sellPriceEstimator;
        this.clock = clock;
    }

    /** 기간 안의 거래를 잘라 담은 결과 */
    private record Window(int days, LocalDate from,
                          List<EstimatedTrade> trades,
                          List<TradePairer.Unmeasurable> unmeasurable) {}

    private record Cached(Instant builtAt, Window window) {}

    /** days → 최근 결과. 읽기 전용 조회라 조금 낡아도 무방하다 */
    private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();

    // ── 공개 API ──────────────────────────────────────────────────────────────

    public Map<String, Object> summary(int days) {
        Window w = window(days);
        Map<String, Object> result = header(w);
        result.putAll(TradeStats.of(w.trades()).toMap());
        result.put("sellSource", sellSourceBreakdown(w.trades()));
        return result;
    }

    public Map<String, Object> byStock(int days) {
        Window w = window(days);
        Map<String, Object> result = header(w);
        result.put("stocks", grouped(w.trades(), EstimatedTrade::stockCode, "stockCode"));
        return result;
    }

    public Map<String, Object> byBucket(int days) {
        Window w = window(days);
        Map<String, Object> result = header(w);
        List<Map<String, Object>> rows = grouped(w.trades(), t -> t.bucket().name(), "bucket");
        rows.forEach(row -> row.put("displayName",
                StrategyBucket.valueOf((String) row.get("bucket")).getDisplayName()));
        result.put("buckets", rows);
        return result;
    }

    // ── 조립 ──────────────────────────────────────────────────────────────────

    /** 30초 안에 같은 기간을 또 물으면 다시 계산하지 않는다 (감사 24_audit M-3) */
    private Window window(int days) {
        int boundedDays = Math.max(1, Math.min(days, MAX_DAYS));
        Instant now = clock.instant();

        Cached cached = cache.get(boundedDays);
        if (cached != null && Duration.between(cached.builtAt(), now).compareTo(CACHE_TTL) < 0) {
            return cached.window();
        }
        if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();

        Window fresh = buildWindow(boundedDays);
        cache.put(boundedDays, new Cached(now, fresh));
        return fresh;
    }

    /** 체결 주문 전량을 짝지은 뒤 매도 시각으로 자른다 — 비싼 쪽은 이 메서드다 */
    private Window buildWindow(int boundedDays) {
        LocalDate from = LocalDate.now(clock).minusDays(boundedDays - 1L);
        LocalDateTime cutoff = from.atStartOfDay();

        TradePairer.Result paired = TradePairer.pair(
                orderHistoryRepository.findByFilledQuantityGreaterThan(0),
                sellPriceEstimator.newLookup());

        return new Window(boundedDays, from,
                paired.trades().stream().filter(t -> !t.soldAt().isBefore(cutoff)).toList(),
                paired.unmeasurable().stream().filter(u -> !u.soldAt().isBefore(cutoff)).toList());
    }

    /** 모든 응답이 공유하는 머리말 — "이건 추정이고 몇 건이 빠졌다"를 빠짐없이 알린다 */
    private static Map<String, Object> header(Window w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("estimated",            true);
        m.put("warning",              WARNING);
        m.put("matchingRule",         MATCHING_RULE);
        m.put("days",                 w.days());
        m.put("from",                 w.from().toString());
        m.put("unmeasurable",         w.unmeasurable().size());
        m.put("unmeasurableQuantity", w.unmeasurable().stream()
                                        .mapToInt(TradePairer.Unmeasurable::quantity).sum());
        m.put("unmeasurableReasons",  unmeasurableReasons(w.unmeasurable()));
        m.put("crossBucketPieces",    crossBucketPieces(w));
        return m;
    }

    /** 직전 매수의 칸 조각이 모자라 다른 칸 옛 조각과 짝지은 조각 수 — 그 짝의 매수가·칸은 믿기 어렵다 */
    private static int crossBucketPieces(Window w) {
        long trades = w.trades().stream().filter(EstimatedTrade::crossBucket).count();
        long excluded = w.unmeasurable().stream().filter(TradePairer.Unmeasurable::crossBucket).count();
        return (int) (trades + excluded);
    }

    private static List<Map<String, Object>> unmeasurableReasons(
            List<TradePairer.Unmeasurable> rows) {
        Map<String, List<TradePairer.Unmeasurable>> byReason = rows.stream()
                .collect(Collectors.groupingBy(TradePairer.Unmeasurable::reason, TreeMap::new,
                        Collectors.toList()));
        return byReason.entrySet().stream().map(e -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reason",   e.getKey());
            item.put("count",    e.getValue().size());
            item.put("quantity", e.getValue().stream()
                                  .mapToInt(TradePairer.Unmeasurable::quantity).sum());
            return item;
        }).toList();
    }

    /** 추정을 어디서 얻었는지 — 분봉/일봉 몇 건인지 사용자가 알 수 있게 */
    private static Map<String, Object> sellSourceBreakdown(List<EstimatedTrade> trades) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (SellPriceEstimator.Source source : SellPriceEstimator.Source.values()) {
            m.put(source.name().toLowerCase(),
                    trades.stream().filter(t -> t.sellSource() == source).count());
        }
        return m;
    }

    /** 키별로 묶어 성적을 낸다 — 손익이 큰 쪽이 먼저 */
    private static List<Map<String, Object>> grouped(List<EstimatedTrade> trades,
                                                     Function<EstimatedTrade, String> keyOf,
                                                     String keyName) {
        return trades.stream()
                .collect(Collectors.groupingBy(keyOf, TreeMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(keyName, e.getKey());
                    row.putAll(TradeStats.of(e.getValue()).toMap());
                    return row;
                })
                .sorted(Comparator.comparingLong(row -> -(long) row.get("totalPnl")))
                .toList();
    }
}
