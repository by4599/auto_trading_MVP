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
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 돈치안 채널 돌파 전략 (전략1=VB 대체 후보, 2026-08).
 *
 * 매수: 종가가 <b>직전 lookback일 고가</b>를 상향 돌파하고(모멘텀 확인),
 * 장기 추세 필터(종가 > MA trendMaPeriod)를 통과할 때. 고전 추세추종(터틀).
 *
 * 출구는 다른 전략과 동일하게 프레임워크(ATR 손절·트레일링·타임컷·최대보유)가 담당한다 —
 * §14에서 MA 정배열 진입이 P3 다일 트레일링으로 살아난 것과 같은 검증 경로로 태워 비교한다.
 * enabled 기본 false({@link DonchianProperties}) — 백테스트에서만 켠다.
 */
@Component
@Profile({"paper", "backtest"})
public class DonchianBreakoutStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(DonchianBreakoutStrategy.class);

    private final MarketDataService marketDataService;
    private final MovingAverageCalculator calculator;
    private final Clock clock;
    private final DonchianProperties properties;

    private final Map<String, CachedHistory> historyCache = new ConcurrentHashMap<>();

    public DonchianBreakoutStrategy(MarketDataService marketDataService,
                                    MovingAverageCalculator calculator,
                                    Clock clock,
                                    DonchianProperties properties) {
        this.marketDataService = marketDataService;
        this.calculator = calculator;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "DONCHIAN";
    }

    @Override
    public List<Signal> evaluate(String stockCode, List<Candle> candles) {
        if (!properties.isEnabled() || candles.isEmpty()) return List.of();
        double currentPrice = candles.get(candles.size() - 1).getClose();

        int lookback = properties.getLookback();
        int trendMa = properties.getTrendMaPeriod();

        List<Candle> history = dailyHistory(stockCode, Math.max(trendMa, lookback) + 5);
        // 직전 lookback봉(현재 봉 제외)이 확보돼야 돌파 기준가를 계산할 수 있다
        if (history.size() < lookback + 1 || history.size() < trendMa) return List.of();

        OptionalDouble trend = calculator.sma(history, trendMa);
        if (trend.isEmpty()) return List.of();

        // 직전 lookback일 고가 = 현재 봉 직전 N개 봉의 최고 고가
        double priorHigh = Double.NEGATIVE_INFINITY;
        int end = history.size() - 1;               // 현재 봉 인덱스(제외)
        for (int i = end - lookback; i < end; i++) {
            priorHigh = Math.max(priorHigh, history.get(i).getHigh());
        }

        boolean breakout = currentPrice > priorHigh;
        boolean uptrend  = currentPrice > trend.getAsDouble();

        if (breakout && uptrend) {
            return List.of(Signal.buy(stockCode, getName(), StrategyBucket.VB));
        }
        return List.of();
    }

    /** 하루 한 번만 일봉을 새로 받는다 (레이트리밋 보호) — MA 전략과 동일 패턴 */
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
            log.warn("[DONCHIAN] 일봉 조회 실패 — 이번 틱 스킵: {}", stockCode, e);
            return cached != null ? cached.candles() : List.of();
        }
    }

    private record CachedHistory(LocalDate asOf, List<Candle> candles) {}
}
