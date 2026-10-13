package com.trading.strategy;

import com.trading.bucket.StrategyBucket;
import com.trading.market.Candle;
import com.trading.market.MarketDataService;
import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 매집 흔적 진입 (흔적레이더 검증, BACKTEST-DESIGN §18 · `_workspace/51_quant` §0) — 백테스트 전용·기본 OFF.
 *
 * <p>매수: 신호일 t의 <b>완성 봉</b>에 거래량 흔적({@link AccumulationSignalCalculator})이 있을 때.
 * 백테스트는 이 판정을 t+1일 아침에 부르고 t+1 시가에 산다({@code DailyBarSimulator}의 다음 날 시가 경로).
 *
 * <p><b>{@code evaluate}의 candles 인자(당일 봉)는 쓰지 않는다</b> — 백테스트 당일 봉의 거래량은 하루치 총량이라
 * 장중에는 알 수 없는 미래 정보다(§15.8에서 거래량 필터가 무효가 된 원인). {@code getDailyCandles}는 시뮬
 * 일자 <b>이전</b>의 완성 봉만 돌려주므로 t+1일에 부르면 t일까지가 보인다.
 *
 * <p>출구는 다른 전략과 같이 프레임워크(ATR 손절·트레일링·최대보유)가 맡는다. Strategy는 Signal만 반환한다.
 * 이름표는 돈치안과 같은 A동 {@link StrategyBucket#TREND} — 진입 방아쇠만 바꿔 비교하기 위해서다.
 */
@Component
@Profile("backtest")
public class AccumulationStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(AccumulationStrategy.class);

    /** 요청 봉 수 — 어떤 규칙의 requiredBars(최대 121)보다 넉넉하게 */
    static final int HISTORY_BARS = 130;

    private final MarketDataService marketDataService;
    private final AccumulationProperties properties;

    public AccumulationStrategy(MarketDataService marketDataService, AccumulationProperties properties) {
        this.marketDataService = marketDataService;
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "ACCUMULATION_TRACE";
    }

    @Override
    public List<Signal> evaluate(String stockCode, List<Candle> candles) {
        if (!properties.isEnabled()) return List.of();
        AccumulationRule rule = properties.getRule();
        List<Candle> completed;
        try {
            completed = marketDataService.getDailyCandles(stockCode,
                    Math.max(HISTORY_BARS, rule.requiredBars()));
        } catch (Exception e) {
            log.warn("[ACCUMULATION] 일봉 조회 실패 — 이번 판정 스킵: {}", stockCode, e);
            return List.of();
        }
        if (!AccumulationSignalCalculator.fires(completed, rule)) return List.of();
        return List.of(Signal.buy(stockCode, getName(), StrategyBucket.TREND));
    }
}
