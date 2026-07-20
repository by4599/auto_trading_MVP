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
 * 이동평균선 정배열 돌파 전략 (방식2, 2026-07-20 — 검증 없이 모의투자 직결, 사용자 판단).
 *
 * 매수: 5/20/60/120일 이동평균선이 정배열(MA5 > MA20 > MA60 > MA120)이고
 * 현재가가 20일선을 상향 돌파했을 때.
 *
 * @Profile("paper") — B-3 백테스트(DailyBarSimulator도 SignalDispatcher를 공유)에
 * 이 미검증 전략의 신호가 섞여 들어가 결정성을 깨뜨리지 않도록 배제한다.
 */
@Component
@Profile("paper")
public class MovingAverageBreakoutStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(MovingAverageBreakoutStrategy.class);

    static final int MA_SHORT = 5;
    static final int MA_MID1 = 20;
    static final int MA_MID2 = 60;
    static final int MA_LONG = 120;

    private final MarketDataService marketDataService;
    private final MovingAverageCalculator calculator;
    private final Clock clock;

    private final Map<String, CachedHistory> historyCache = new ConcurrentHashMap<>();

    public MovingAverageBreakoutStrategy(MarketDataService marketDataService,
                                         MovingAverageCalculator calculator,
                                         Clock clock) {
        this.marketDataService = marketDataService;
        this.calculator = calculator;
        this.clock = clock;
    }

    @Override
    public String getName() {
        return "MA_BREAKOUT";
    }

    @Override
    public List<Signal> evaluate(String stockCode, List<Candle> candles) {
        if (candles.isEmpty()) return List.of();
        double currentPrice = candles.get(candles.size() - 1).getClose();

        List<Candle> history = dailyHistory(stockCode);
        if (history.size() < MA_LONG) return List.of();

        OptionalDouble ma5 = calculator.sma(history, MA_SHORT);
        OptionalDouble ma20 = calculator.sma(history, MA_MID1);
        OptionalDouble ma60 = calculator.sma(history, MA_MID2);
        OptionalDouble ma120 = calculator.sma(history, MA_LONG);
        if (ma5.isEmpty() || ma20.isEmpty() || ma60.isEmpty() || ma120.isEmpty()) return List.of();

        boolean alignedUp = ma5.getAsDouble() > ma20.getAsDouble()
                && ma20.getAsDouble() > ma60.getAsDouble()
                && ma60.getAsDouble() > ma120.getAsDouble();

        if (alignedUp && currentPrice > ma20.getAsDouble()) {
            return List.of(Signal.buy(stockCode, getName(), StrategyBucket.EVENT));
        }
        return List.of();
    }

    /** 하루 한 번만 KIS 일봉을 새로 받는다 — 매 틱 재조회는 레이트리밋을 압박한다 */
    private List<Candle> dailyHistory(String stockCode) {
        LocalDate today = LocalDate.now(clock);
        CachedHistory cached = historyCache.get(stockCode);
        if (cached != null && cached.asOf().equals(today)) {
            return cached.candles();
        }
        try {
            List<Candle> fresh = marketDataService.getDailyCandles(stockCode, MA_LONG + 5);
            historyCache.put(stockCode, new CachedHistory(today, fresh));
            return fresh;
        } catch (Exception e) {
            log.warn("[MA_BREAKOUT] 일봉 조회 실패 — 이번 틱 스킵: {}", stockCode, e);
            return cached != null ? cached.candles() : List.of();
        }
    }

    private record CachedHistory(LocalDate asOf, List<Candle> candles) {}
}
