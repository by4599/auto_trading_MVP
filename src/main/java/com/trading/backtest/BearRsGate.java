package com.trading.backtest;

import com.trading.market.Candle;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.Timeframe;
import com.trading.risk.RiskLimitsProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 하락장 상대강도 예외 스위치 (55_quant §0.3, 백테스트 전용) — <b>기본 꺼짐</b>. 꺼져 있으면 {@link #permits}는 항상 true,
 * {@link #executeSized}는 주문을 그대로 실행한다(기존 모드 결과 불변).
 *
 * <p>켜진 칸에서는 {@code IndexTrendRule}을 끄고, 이 스위치가 <b>하락장 날</b>(전일 KOSPI 종가 &lt; 전일까지 MA120 —
 * {@link BacktestIndexRegimeSource#belowTrend}와 같은 함수)의 진입만 RS 조건으로 거른다. 하락장이 아닌 날은 손대지 않는다.
 * 라이브 룰({@code IndexTrendRule}·{@code RiskEngine})은 고치지 않는다.
 *
 * <p>시계열은 candle_history 전 기간을 직접 읽는다(러너의 워밍업 260일 창과 무관). 모든 판정은 시뮬 당일 봉을 쓰지 않는다.
 */
@Component
@Profile("backtest")
public class BearRsGate {

    static final int INDEX_MA = 120;
    private static final LocalDate LOAD_FROM = LocalDate.of(1990, 1, 1);
    private static final LocalDate LOAD_TO = LocalDate.of(2999, 12, 31);

    record Series(NavigableMap<LocalDate, Double> closes, NavigableMap<LocalDate, Double> highs) {}

    private final CandleHistoryRepository repository;
    private final BacktestDataProperties properties;
    private final RiskLimitsProperties limits;
    private final ScaleInRunStats stats;

    private volatile BearRsConfig config;            // null = 꺼짐
    private volatile List<String> universe = List.of();
    private final Map<String, Series> series = new ConcurrentHashMap<>();
    private final Map<LocalDate, Boolean> bearDays = new ConcurrentHashMap<>();
    private final Map<LocalDate, OptionalDouble> thresholds = new ConcurrentHashMap<>();
    private volatile Series index;

    public BearRsGate(CandleHistoryRepository repository, BacktestDataProperties properties,
                      RiskLimitsProperties limits, ScaleInRunStats stats) {
        this.repository = repository;
        this.properties = properties;
        this.limits = limits;
        this.stats = stats;
    }

    /** 다음 run들에 적용 — universe는 (b)의 순위를 매기는 종목 집합(후보 54종목) */
    public void configure(BearRsConfig newConfig, List<String> newUniverse) {
        this.config = newConfig;
        this.universe = List.copyOf(newUniverse);
        thresholds.clear();
    }

    public void disable() {
        this.config = null;
        thresholds.clear();
    }

    public boolean isEnabled() {
        return config != null;
    }

    /** 하락장 날 — 전일 KOSPI 종가 &lt; 전일까지 MA120. 판정 불가(표본 부족)는 하락장 아님(IndexTrendRule과 같다) */
    public boolean isBearDay(LocalDate date) {
        return bearDays.computeIfAbsent(date, d ->
                BacktestIndexRegimeSource.belowTrend(indexSeries().closes(), d, INDEX_MA).orElse(false));
    }

    /** 진입 직전 허용 여부 — 꺼져 있거나 하락장이 아닌 날은 항상 true */
    public boolean permits(String stockCode, LocalDate date) {
        BearRsConfig c = config;
        if (c == null || !isBearDay(date)) return true;
        boolean allowed = allowsOnBearDay(c, stockCode, date);
        stats.count(allowed ? "하락장 허용" : "하락장 불허");
        return allowed;
    }

    /** 하락장 날 진입분만 1R 비율을 절반으로 낮춰 주문한다(그 한 건만, 끝나면 원복) */
    public void executeSized(LocalDate date, Runnable order) {
        BearRsConfig c = config;
        if (c == null || !c.halfSize() || !isBearDay(date)) {
            order.run();
            return;
        }
        double base = limits.getRiskFractionPerTrade();
        limits.setRiskFractionPerTrade(base / 2);
        try {
            stats.count("하락장 절반 사이징");
            order.run();
        } finally {
            limits.setRiskFractionPerTrade(base);
        }
    }

    boolean allowsOnBearDay(BearRsConfig c, String stockCode, LocalDate date) {
        OptionalDouble rs = rs(stockCode, date, c.n());
        if (rs.isEmpty()) return false;
        return switch (c.condition()) {
            case A_POSITIVE -> rs.getAsDouble() > 0;
            case B_TOP -> inTop(c, rs.getAsDouble(), date);
            case C_TOP_NEAR_HIGH -> inTop(c, rs.getAsDouble(), date) && nearHigh(c, stockCode, date);
        };
    }

    private boolean inTop(BearRsConfig c, double rs, LocalDate date) {
        OptionalDouble threshold = thresholds.computeIfAbsent(date, d -> universeThreshold(c, d));
        return threshold.isPresent() && rs >= threshold.getAsDouble();
    }

    private OptionalDouble universeThreshold(BearRsConfig c, LocalDate date) {
        List<Double> values = new ArrayList<>();
        for (String code : universe) {
            rs(code, date, c.n()).ifPresent(values::add);
        }
        return BearRsMath.topThreshold(values, c.topFraction());
    }

    private boolean nearHigh(BearRsConfig c, String stockCode, LocalDate date) {
        Series s = stockSeries(stockCode);
        return BearRsMath.nearHigh(s.closes(), s.highs(), date, BearRsConfig.HIGH_LOOKBACK, c.highRatio());
    }

    OptionalDouble rs(String stockCode, LocalDate date, int n) {
        return BearRsMath.relativeStrength(stockSeries(stockCode).closes(), indexSeries().closes(), date, n);
    }

    private Series stockSeries(String stockCode) {
        return series.computeIfAbsent(stockCode, this::load);
    }

    private Series indexSeries() {
        Series cached = index;
        if (cached != null) return cached;
        cached = load(properties.getKospiStorageCode());
        index = cached;
        return cached;
    }

    private Series load(String storageCode) {
        TreeMap<LocalDate, Double> closes = new TreeMap<>();
        TreeMap<LocalDate, Double> highs = new TreeMap<>();
        repository.findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        storageCode, Timeframe.DAILY, LOAD_FROM, LOAD_TO)
                .forEach(h -> {
                    Candle candle = h.toCandle();
                    closes.put(h.getCandleDate(), candle.getClose());
                    highs.put(h.getCandleDate(), candle.getHigh());
                });
        return new Series(closes, highs);
    }
}
