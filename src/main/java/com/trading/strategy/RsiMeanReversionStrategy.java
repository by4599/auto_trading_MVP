package com.trading.strategy;

import com.trading.bucket.StrategyBucket;
import com.trading.market.Candle;
import com.trading.market.MarketDataService;
import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RSI(2) 평균회귀 전략 (전략3=눌림목 스캘핑 대체 후보, 2026-08).
 *
 * 매수: 장기 추세 필터(종가 &gt; MA trendMaPeriod)를 통과한 종목이 단기 RSI(rsiPeriod)로
 * oversoldThreshold 미만까지 과매도되면 매수(Larry Connors RSI2 계열). RSI(2) 과매도는
 * <b>종가에 확정</b>되는 신호라, 백테스트에서는 돌파용 고가-발화 경로가 아니라 종가 기반
 * 진입 경로(DailyBarSimulator.checkMeanReversionEntry)를 탄다.
 *
 * 출구는 다른 전략과 동일하게 프레임워크(ATR 손절·트레일링·최대보유·타임컷)가 담당한다 —
 * §14에서 진입만 바꿔 검증된 출구에 태워 비교하는 것과 같은 경로.
 * enabled 기본 false({@link RsiProperties}) — 백테스트에서만 켠다. 버킷은 전략3 슬롯(MIX)을 유지한다.
 */
@Component
@Profile({"paper", "backtest"})
public class RsiMeanReversionStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(RsiMeanReversionStrategy.class);

    private final MarketDataService marketDataService;
    private final RsiCalculator rsiCalculator;
    private final Clock clock;
    private final RsiProperties properties;

    private final Map<String, CachedHistory> historyCache = new ConcurrentHashMap<>();

    public RsiMeanReversionStrategy(MarketDataService marketDataService,
                                    RsiCalculator rsiCalculator,
                                    Clock clock,
                                    RsiProperties properties) {
        this.marketDataService = marketDataService;
        this.rsiCalculator = rsiCalculator;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "RSI2_MEAN_REVERSION";
    }

    @Override
    public List<Signal> evaluate(String stockCode, List<Candle> candles) {
        if (!properties.isEnabled() || candles.isEmpty()) return List.of();
        double currentPrice = candles.get(candles.size() - 1).getClose();

        int rsiPeriod = properties.getRsiPeriod();
        int trendMa = properties.getTrendMaPeriod();

        List<Candle> history = dailyHistory(stockCode, Math.max(trendMa, rsiPeriod + 1) + 5);
        // 오늘 이전 완결봉(history) 끝에 오늘 종가를 붙여 RSI·MA를 계산 — 선견편향 없음
        List<Double> closes = new ArrayList<>(history.size() + 1);
        for (Candle c : history) closes.add(c.getClose());
        closes.add(currentPrice);

        if (closes.size() < trendMa || closes.size() < rsiPeriod + 1) return List.of();

        // 추세 필터: 오늘 종가 포함 최근 trendMa개 종가의 단순평균
        double ma = 0.0;
        for (int i = closes.size() - trendMa; i < closes.size(); i++) ma += closes.get(i);
        ma /= trendMa;

        OptionalDouble rsi = rsiCalculator.rsi(closes, rsiPeriod);
        if (rsi.isEmpty()) return List.of();

        boolean uptrend  = currentPrice > ma;
        boolean oversold = rsi.getAsDouble() < properties.getOversoldThreshold();

        if (uptrend && oversold) {
            return List.of(Signal.buy(stockCode, getName(), StrategyBucket.MIX));
        }
        return List.of();
    }

    /** 하루 한 번만 일봉을 새로 받는다 (레이트리밋 보호) — Donchian/MA 전략과 동일 패턴 */
    private List<Candle> dailyHistory(String stockCode, int count) {
        LocalDate today = LocalDate.now(clock);
        CachedHistory cached = historyCache.get(stockCode);
        if (cached != null && cached.asOf().equals(today)) {
            return cached.candles();
        }
        try {
            List<Candle> fresh = marketDataService.getDailyCandles(stockCode, count);
            historyCache.put(stockCode, new CachedHistory(today, fresh));
            return fresh;
        } catch (Exception e) {
            log.warn("[RSI2] 일봉 조회 실패 — 이번 틱 스킵: {}", stockCode, e);
            return cached != null ? cached.candles() : List.of();
        }
    }

    private record CachedHistory(LocalDate asOf, List<Candle> candles) {}
}
