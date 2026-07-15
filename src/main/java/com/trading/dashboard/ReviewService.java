package com.trading.dashboard;

import com.trading.market.Candle;
import com.trading.market.MarketDataService;
import com.trading.research.DisclosureItem;
import com.trading.research.DisclosureRepository;
import com.trading.research.NewsItem;
import com.trading.research.NewsRepository;
import com.trading.risk.DisclosureCooldownRule;
import com.trading.strategy.BreakoutCalculator;
import com.trading.strategy.FilterProperties;
import com.trading.strategy.StrategyParameters;
import com.trading.universe.TradingUniverseItem;
import com.trading.universe.TradingUniverseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 검토종목 대시보드 — 유니버스 종목별 변동성 돌파 검토 현황.
 *
 * KIS 레이트리밋(모의 초당 2건) 대응:
 * - 전일 OHLC·당일 시가는 장중 불변 → 하루 1회만 조회해 dayCache에 보관
 *   (갱신 시 종목 간 600ms 간격, 실패 종목은 다음 요청에서 재시도)
 * - 현재가는 QuoteCacheService(2초 캐시, 대시보드와 공유)만 사용
 *
 * 목표가 공식은 BreakoutCalculator — 전략의 실제 판정과 동일한 값이다.
 */
@Component
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final long DAY_CACHE_REFRESH_GAP_MS = 600L;

    private final TradingUniverseService universeService;
    private final MarketDataService marketDataService;
    private final QuoteCacheService quoteCache;
    private final StrategyParameters strategyParameters;
    private final FilterProperties filters;
    private final DisclosureRepository disclosureRepository;
    private final NewsRepository newsRepository;
    private final Clock clock;

    private final Map<String, DayBase> dayCache = new ConcurrentHashMap<>();

    public ReviewService(TradingUniverseService universeService,
                         MarketDataService marketDataService,
                         QuoteCacheService quoteCache,
                         StrategyParameters strategyParameters,
                         FilterProperties filters,
                         DisclosureRepository disclosureRepository,
                         NewsRepository newsRepository,
                         Clock clock) {
        this.universeService = universeService;
        this.marketDataService = marketDataService;
        this.quoteCache = quoteCache;
        this.strategyParameters = strategyParameters;
        this.filters = filters;
        this.disclosureRepository = disclosureRepository;
        this.newsRepository = newsRepository;
        this.clock = clock;
    }

    public List<Map<String, Object>> candidates() {
        LocalDate today = LocalDate.now(clock);
        List<TradingUniverseItem> universe = universeService.getAll();
        List<Map<String, Object>> out = new ArrayList<>();

        boolean refreshedAny = false;
        for (TradingUniverseItem item : universe) {
            String code = item.getStockCode();

            DayBase base = dayCache.get(code);
            if (base == null || !base.date().equals(today)) {
                if (refreshedAny) sleepQuietly();
                base = loadDayBase(code, today);
                refreshedAny = true;
                if (base != null) dayCache.put(code, base);
            }

            out.add(buildEntry(item, base, today));
        }
        return out;
    }

    private DayBase loadDayBase(String code, LocalDate today) {
        try {
            List<Candle> candles = marketDataService.getRecentCandles(code);
            if (candles.size() < 2) return null;
            Candle last = candles.get(candles.size() - 1);
            if (!today.equals(last.date())) return null;  // 당일 봉 없음 — 개장 전
            if (last.getOpen() <= 0) return null;         // KIS가 개장 전 시가를 0으로 반환
            Candle yesterday = candles.get(candles.size() - 2);
            return new DayBase(today, yesterday, last.getOpen());
        } catch (Exception e) {
            log.warn("[Review] 일봉 조회 실패 — 다음 요청에서 재시도: {} ({})", code, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildEntry(TradingUniverseItem item, DayBase base, LocalDate today) {
        String code = item.getStockCode();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stockCode", code);

        QuoteCacheService.Quote quote = fetchQuoteQuietly(code);
        m.put("stockName",  quote != null && quote.stockName() != null && !quote.stockName().isBlank()
                ? quote.stockName() : item.getStockName());
        m.put("currentPrice", quote != null ? quote.currentPrice() : null);
        m.put("changeAmount", quote != null ? quote.changeAmount() : null);
        m.put("changeRate",   quote != null ? quote.changeRate() : null);

        double k = strategyParameters.getK();
        m.put("k", k);

        if (base != null) {
            double target = BreakoutCalculator.targetPrice(
                    base.yesterday().getHigh(), base.yesterday().getLow(), base.todayOpen(), k);
            m.put("yesterdayHigh",  Math.round(base.yesterday().getHigh()));
            m.put("yesterdayLow",   Math.round(base.yesterday().getLow()));
            m.put("yesterdayClose", Math.round(base.yesterday().getClose()));
            m.put("todayOpen",      Math.round(base.todayOpen()));
            m.put("targetPrice",    Math.round(target));

            if (quote != null && quote.currentPrice() > 0) {
                double distancePct = (target - quote.currentPrice()) / quote.currentPrice() * 100.0;
                m.put("distancePct", Math.round(distancePct * 100.0) / 100.0);
                m.put("brokeOut",    quote.currentPrice() > target);
            } else {
                m.put("distancePct", null);
                m.put("brokeOut",    null);
            }
        } else {
            // 개장 전이거나 일봉 조회 실패 — 목표가 미확정
            m.put("yesterdayHigh",  null);
            m.put("yesterdayLow",   null);
            m.put("yesterdayClose", null);
            m.put("todayOpen",      null);
            m.put("targetPrice",    null);
            m.put("distancePct",    null);
            m.put("brokeOut",       null);
        }

        m.put("filters",   filterStatus(code, today));
        m.put("sentiment", latestSentiment(code));
        return m;
    }

    private Map<String, Object> filterStatus(String code, LocalDate today) {
        Map<String, Object> f = new LinkedHashMap<>();

        // 공시 쿨다운 — DisclosureCooldownRule과 같은 창(어제까지)·같은 정례 유형 제외 기준
        FilterProperties.DisclosureCooldown cd = filters.getDisclosureCooldown();
        Map<String, Object> cooldown = new LinkedHashMap<>();
        cooldown.put("enabled", cd.isEnabled());
        if (cd.isEnabled()) {
            LocalDate to = today.minusDays(1);
            LocalDate from = to.minusDays(cd.getCooldownDays() - 1);
            Optional<DisclosureItem> blocking = disclosureRepository
                    .findByStockCodeAndDisclosedAtBetween(code, from, to).stream()
                    .filter(d -> d.getEventType() != null
                            && !DisclosureCooldownRule.ROUTINE_TYPES.contains(d.getEventType()))
                    .max(Comparator.comparing(DisclosureItem::getDisclosedAt));
            cooldown.put("active", blocking.isPresent());
            blocking.ifPresent(d -> {
                cooldown.put("eventType", d.getEventType());
                cooldown.put("until", d.getDisclosedAt().plusDays(cd.getCooldownDays()).toString());
            });
        }
        f.put("disclosureCooldown", cooldown);

        FilterProperties.EntryWindow ew = filters.getEntryWindow();
        Map<String, Object> entryWindow = new LinkedHashMap<>();
        entryWindow.put("enabled", ew.isEnabled());
        if (ew.isEnabled()) {
            entryWindow.put("notBefore", ew.getNotBefore().toString());
            entryWindow.put("ok", !LocalTime.now(clock).isBefore(ew.getNotBefore()));
        }
        f.put("entryWindow", entryWindow);

        f.put("volumeConfirm", Map.of("enabled", filters.getVolumeConfirm().isEnabled()));
        f.put("indexRegime",   Map.of("enabled", filters.getIndexRegime().isEnabled()));
        f.put("trailingStop",  Map.of("enabled", filters.getTrailingStop().isEnabled()));
        return f;
    }

    private String latestSentiment(String code) {
        try {
            List<NewsItem> news = newsRepository.findByStockCodeOrderByPublishedAtDesc(code);
            return news.isEmpty() ? null : news.get(0).getSentiment();
        } catch (Exception e) {
            return null;
        }
    }

    private QuoteCacheService.Quote fetchQuoteQuietly(String code) {
        if (!quoteCache.isConfigured()) return null;
        try {
            return quoteCache.fetch(code);
        } catch (Exception e) {
            log.warn("[Review] 현재가 조회 실패: {} ({})", code, e.getMessage());
            return null;
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(DAY_CACHE_REFRESH_GAP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record DayBase(LocalDate date, Candle yesterday, double todayOpen) {}
}
