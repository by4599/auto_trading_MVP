package com.trading.dashboard;

import com.trading.bucket.SleeveRealized;
import com.trading.bucket.SleeveRealizedSource;
import com.trading.bucket.StrategyBucket;
import com.trading.order.OrderHistory;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import com.trading.position.TradeResult;
import com.trading.position.TradeResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 칸 실현손익 원천 — 대시보드의 거래 짝짓기({@link TradePairer})와 매도가 추정기({@link SellPriceEstimator})로
 * 칸별 낙폭 상한에 실현손익을 대 준다. bucket 패키지는 이 클래스를 모르고 인터페이스만 안다.
 *
 * <p><b>기록값 우선.</b> 실제 체결가로 남은 {@link TradeResult}(매도가 &gt; 0)가 있으면 그 금액을 쓰고,
 * 그 매도는 추정에서 뺀다(두 번 세지 않는다). 어느 매도의 기록인지는 "그 매도 주문이 접수된 뒤 ~ 체결 확인
 * 직후 사이에 같은 종목의 기록이 남았나"로 본다 — 정상 체결 경로가 체결 반영과 같은 트랜잭션에서 기록을 남긴다
 * ({@code FillStateUpdater.applySellFill}). 매도가 0원으로 박힌 옛 기록(2026-07-30 헛청산 5건 등)은 쓰지 않는다.
 *
 * <p><b>감시 스레드를 가볍게 (46_audit M-2).</b> 1분 감시가 손절·강제청산과 같은 스레드(scheduling-1)에서 칸마다 부른다.
 * <ol>
 *   <li>시간이 지났다는 이유만으로 다시 계산하지 않는다 — 체결 주문·기록·날짜(지문)가 바뀔 때만 다시 짝짓는다
 *       (예전 10분 만료는 장중 하루 약 39회 전체 재짝짓기·분봉 약 118회 조회를 만들었다).</li>
 *   <li>분봉은 요청 기간(굳히지 않은 기간) 안의, 그 칸의, 기록 없는 매도만 읽고, 구한 추정은 그날 동안 기억한다.
 *       오늘 판 매도의 "측정 불가"만은 기억하지 않는다 — 장 마감 뒤(15:40) 분봉이 쌓이면 바로 잡히게.</li>
 *   <li>같은 회차({@value #ROUND_REUSE_SECONDS}초 안)의 다음 호출은 직전 DB 조회를 다시 쓴다 — 회차당 조회·지문 1회.</li>
 *   <li>체결 주문은 상태 인덱스(idx_order_history_status)로 읽는다 — 실패 주문 수만 건을 매번 훑지 않는다.</li>
 * </ol>
 */
@Component
@Profile("paper")
public class SleeveRealizedPnlAdapter implements SleeveRealizedSource {

    private static final Logger log = LoggerFactory.getLogger(SleeveRealizedPnlAdapter.class);

    static final long ROUND_REUSE_SECONDS = 5;
    static final Duration ROUND_REUSE = Duration.ofSeconds(ROUND_REUSE_SECONDS);

    /** 기록 시각이 체결 확인 시각보다 아주 조금 늦게 찍혀도 같은 매도로 본다 */
    private static final Duration RECORD_MATCH_SLACK = Duration.ofMinutes(1);

    /**
     * 체결 수량이 있을 수 있는 상태 — PARTIAL_FILLED·FILLED·CANCEL_REQUESTED(취소 중 체결)에서 생기고
     * CANCELLED·CANCEL_FAILED로도 남는다. ACCEPTED·FAILED는 체결 수량이 늘 0이다({@code OrderHistory} 상태 전이).
     */
    static final List<OrderStatus> FILLABLE = List.of(OrderStatus.PARTIAL_FILLED, OrderStatus.FILLED,
            OrderStatus.CANCEL_REQUESTED, OrderStatus.CANCELLED, OrderStatus.CANCEL_FAILED, OrderStatus.TIMEOUT);

    private final OrderHistoryRepository orderHistoryRepository;
    private final TradeResultRepository tradeResultRepository;
    private final SellPriceEstimator sellPriceEstimator;
    private final Clock clock;

    // 아래 넷은 synchronized 안에서만 읽고 쓴다
    private Loaded loaded;
    private boolean reloadNext;
    private final Map<String, SellPriceEstimator.Estimate> estimates = new HashMap<>();
    private LocalDate estimatesDate;

    /** 한 번의 DB 조회 결과 — 짝짓기까지 마친 것 (가격은 아직 없다) */
    private record Loaded(Instant at, List<Object> fingerprint, List<TradeResult> recorded,
                          Set<String> recordedSellKeys, TradePairer.Matching matching) {

        boolean isRecorded(String stockCode, LocalDateTime soldAt) {
            return recordedSellKeys.contains(sellKey(stockCode, soldAt));
        }

        Loaded seenAt(Instant now) {
            return new Loaded(now, fingerprint, recorded, recordedSellKeys, matching);
        }
    }

    /** 칸과 매도 날짜 기간 [from, toExclusive) */
    private record Window(StrategyBucket bucket, LocalDate from, LocalDate toExclusive) {
        boolean includes(StrategyBucket tradeBucket, LocalDate soldOn) {
            return StrategyBucket.orDefault(tradeBucket) == bucket
                    && !soldOn.isBefore(from) && soldOn.isBefore(toExclusive);
        }
    }

    public SleeveRealizedPnlAdapter(OrderHistoryRepository orderHistoryRepository,
                                    TradeResultRepository tradeResultRepository,
                                    SellPriceEstimator sellPriceEstimator,
                                    Clock clock) {
        this.orderHistoryRepository = orderHistoryRepository;
        this.tradeResultRepository = tradeResultRepository;
        this.sellPriceEstimator = sellPriceEstimator;
        this.clock = clock;
    }

    @Override
    public synchronized SleeveRealized realized(StrategyBucket bucket, LocalDate fromInclusive, LocalDate toExclusive) {
        Loaded l = load();
        Window w = new Window(bucket, fromInclusive, toExclusive);
        TradePairer.Matching scoped = l.matching().where(
                m -> w.includes(m.bucket(), m.soldAt().toLocalDate()) && !l.isRecorded(m.stockCode(), m.soldAt()),
                u -> w.includes(null, u.soldAt().toLocalDate()) && !l.isRecorded(u.stockCode(), u.soldAt()));
        SellPriceEstimator.Lookup lookup = sellPriceEstimator.newLookup();
        TradePairer.Result priced = TradePairer.price(scoped, (code, at) -> estimate(lookup, code, at));
        return recordedPart(l, w).plus(estimatedPart(priced));
    }

    /**
     * 다음 호출은 회차 재사용 없이 DB 지문을 다시 확인한다 — 사람의 해제가 부른다(46b N-2). 해제 직전에 반영된 체결이
     * 재사용 때문에 빠지면 실현손익은 옛 값·보유는 새 값이 돼 최고 기록이 그 손실만큼 높게 박히고, 다음 거래일 헛잠금이 난다.
     * 감시 회차 경로는 이것을 부르지 않으므로 동작이 그대로다.
     */
    @Override
    public synchronized void refreshOnNextCall() {
        reloadNext = true;
    }

    private static SleeveRealized recordedPart(Loaded l, Window w) {
        double pnl = 0;
        int count = 0;
        for (TradeResult r : l.recorded()) {
            if (!w.includes(r.getBucket(), r.getTradeDate())) continue;
            pnl += r.getRealizedPnl();
            count++;
        }
        return new SleeveRealized(pnl, count, 0, 0, null);
    }

    private static SleeveRealized estimatedPart(TradePairer.Result priced) {
        double pnl = priced.trades().stream().mapToDouble(EstimatedTrade::pnl).sum();
        LocalDate latest = priced.unmeasurable().stream()
                .map(u -> u.soldAt().toLocalDate())
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new SleeveRealized(pnl, 0, priced.trades().size(), priced.unmeasurable().size(), latest);
    }

    /** 그날 이미 구한 추정은 다시 읽지 않는다. 오늘 판 매도의 '측정 불가'만은 기억하지 않는다(장 마감 뒤 분봉이 쌓인다) */
    private SellPriceEstimator.Estimate estimate(SellPriceEstimator.Lookup lookup, String stockCode, LocalDateTime soldAt) {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(estimatesDate)) {
            estimates.clear();
            estimatesDate = today;
        }
        String key = sellKey(stockCode, soldAt);
        SellPriceEstimator.Estimate known = estimates.get(key);
        if (known != null) return known;

        SellPriceEstimator.Estimate fresh = lookup.estimate(stockCode, soldAt);
        if (fresh.isMeasurable() || !soldAt.toLocalDate().equals(today)) estimates.put(key, fresh);
        return fresh;
    }

    // ── 조회·짝짓기 (회차당 1회, 바뀔 때만 다시 짝짓기) ─────────────────────────

    private Loaded load() {
        Instant now = clock.instant();
        if (!reloadNext && loaded != null && withinRound(loaded.at(), now)) return loaded;
        reloadNext = false;

        List<OrderHistory> filled = filledOrders();
        List<TradeResult> recorded = tradeResultRepository.findAll().stream()
                .filter(r -> r.getSellPrice() > 0)
                .toList();
        List<Object> fingerprint = fingerprint(filled, recorded);
        if (loaded != null && loaded.fingerprint().equals(fingerprint)) {
            loaded = loaded.seenAt(now);
            return loaded;
        }
        loaded = new Loaded(now, fingerprint, recorded, recordedSellKeys(filled, recorded), TradePairer.match(filled));
        warnIfCrossBucket(loaded.matching());
        return loaded;
    }

    /** 직전 조회가 같은 회차(5초 안)인가 — 시계가 뒤로 가 경과가 음수면 아니다(46b N-2, 재사용이 무한히 길어지지 않게) */
    private static boolean withinRound(Instant loadedAt, Instant now) {
        Duration elapsed = Duration.between(loadedAt, now);
        return !elapsed.isNegative() && elapsed.compareTo(ROUND_REUSE) < 0;
    }

    /** 체결 수량이 있는 주문 — 상태 인덱스로 읽고 id 순으로 세운다(지문이 조회 순서에 흔들리지 않게) */
    private List<OrderHistory> filledOrders() {
        return orderHistoryRepository.findByStatusIn(FILLABLE).stream()
                .filter(o -> o.getFilledQuantity() > 0)
                .sorted(Comparator.comparingLong(o -> o.getId() == null ? 0L : o.getId()))
                .toList();
    }

    /** 짝짓기 결과를 바꿀 수 있는 값 전부 — 하나라도 다르면 다시 짝짓는다 */
    private List<Object> fingerprint(List<OrderHistory> filled, List<TradeResult> recorded) {
        List<Object> fp = new ArrayList<>();
        fp.add(LocalDate.now(clock));
        for (OrderHistory o : filled) {
            fp.add(List.of(String.valueOf(o.getId()), o.getSide(), o.getFilledQuantity(),
                    String.valueOf(o.getFilledPrice()), String.valueOf(o.getRequestedAt()),
                    String.valueOf(o.getFilledAt()), String.valueOf(o.getBucket())));
        }
        for (TradeResult r : recorded) {
            fp.add(List.of(r.getStockCode(), String.valueOf(r.getSoldAt()), r.getQuantity(), r.getRealizedPnl()));
        }
        return fp;
    }

    private static void warnIfCrossBucket(TradePairer.Matching matching) {
        long count = matching.crossBucketCount();
        if (count == 0) return;
        log.warn("[칸 장부] 칸 넘김 짝 {}건 — 직전 매수의 칸 조각이 모자라 다른 칸의 옛 조각과 짝지었다"
                + "(그 짝의 매수가·칸은 믿기 어렵다): {}", count,
                matching.matches().stream().filter(TradePairer.Match::crossBucket)
                        .map(m -> m.stockCode() + " " + m.soldAt().toLocalDate() + " " + m.quantity() + "주")
                        .toList());
    }

    private static Set<String> recordedSellKeys(List<OrderHistory> filled, List<TradeResult> recorded) {
        Set<String> keys = new HashSet<>();
        for (OrderHistory o : filled) {
            if (o.getSide() == OrderSide.SELL && hasTradeResult(o, recorded)) {
                keys.add(sellKey(o.getStockCode(), TradePairer.fillTimeOf(o)));
            }
        }
        return keys;
    }

    /** 그 매도 주문이 접수된 뒤 ~ 체결 확인 직후 사이에 같은 종목의 기록이 남았나 */
    private static boolean hasTradeResult(OrderHistory sell, List<TradeResult> recorded) {
        LocalDateTime start = sell.getRequestedAt();
        LocalDateTime end = (sell.getFilledAt() != null ? sell.getFilledAt() : start.plusDays(1))
                .plus(RECORD_MATCH_SLACK);
        return recorded.stream().anyMatch(r -> r.getStockCode().equals(sell.getStockCode())
                && !r.getSoldAt().isBefore(start) && !r.getSoldAt().isAfter(end));
    }

    private static String sellKey(String stockCode, LocalDateTime soldAt) {
        return stockCode + "|" + soldAt;
    }
}
