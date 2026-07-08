package com.trading.strategy;

import com.trading.market.Candle;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 래리 윌리엄스 변동성 돌파 전략 (v1 MVP).
 * 매수: 당일 종가가 (전일 고가 - 전일 저가) * K 만큼 시가 위로 돌파했을 때
 * 매도는 이 전략에서 다루지 않는다 -> 장 마감 강제청산 룰(MarketCloseRule)이
 * 신규 매수를 막고, 보유분 정리는 OrderEngine/스케줄러 쪽에서 별도 처리한다.
 */
@Component
public class VolatilityBreakoutStrategy implements Strategy {

    private static final double K = 0.5;

    @Override
    public String getName() {
        return "VOLATILITY_BREAKOUT";
    }

    @Override
    public List<Signal> evaluate(String stockCode, List<Candle> candles) {
        if (candles.size() < 2) {
            return List.of();
        }

        Candle yesterday = candles.get(candles.size() - 2);
        Candle today = candles.get(candles.size() - 1);

        double range = yesterday.getHigh() - yesterday.getLow();
        double breakoutPrice = today.getOpen() + range * K;

        if (today.getClose() > breakoutPrice) {
            return List.of(Signal.buy(stockCode, getName()));
        }

        return List.of();
    }
}
