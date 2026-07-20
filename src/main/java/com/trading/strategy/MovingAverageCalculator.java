package com.trading.strategy;

import com.trading.market.Candle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.OptionalDouble;

/**
 * 단순이동평균(SMA) 계산기 — 이동평균선 정배열 전략(방식2)용 (방법론 §2.4 팩터 계열).
 */
@Component
public class MovingAverageCalculator {

    /**
     * @param candles 완성 일봉, 과거→최신 순. period개 이상 필요
     * @return period개 종가 단순평균. 데이터 부족이면 empty
     */
    public OptionalDouble sma(List<Candle> candles, int period) {
        if (candles == null || candles.size() < period) return OptionalDouble.empty();

        List<Candle> recent = candles.subList(candles.size() - period, candles.size());
        double sum = 0.0;
        for (Candle c : recent) {
            sum += c.getClose();
        }
        return OptionalDouble.of(sum / period);
    }
}
