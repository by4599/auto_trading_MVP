package com.trading.signal;

import com.trading.market.Candle;
import com.trading.strategy.Strategy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 등록된 모든 Strategy를 실행하고 결과를 모은다.
 * v1은 전략이 하나뿐이라 충돌이 없지만, v3(다중 전략)로 가면
 * 같은 종목에 BUY/SELL이 동시에 뜰 때 resolveConflicts()가 핵심이 된다.
 */
@Component
public class SignalDispatcher {

    private final List<Strategy> strategies;

    public SignalDispatcher(List<Strategy> strategies) {
        this.strategies = strategies;
    }

    public List<Signal> dispatch(String stockCode, List<Candle> candles) {
        List<Signal> signals = new ArrayList<>();
        for (Strategy strategy : strategies) {
            signals.addAll(strategy.evaluate(stockCode, candles));
        }
        return resolveConflicts(signals);
    }

    private List<Signal> resolveConflicts(List<Signal> signals) {
        // TODO v3: 같은 종목에 여러 전략의 신호가 충돌하면 우선순위 규칙 적용
        return signals;
    }
}
