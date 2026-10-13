package com.trading.strategy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Wilder RSI 계산기 — RSI(2) 평균회귀 전략(전략3=스캘핑 대체 후보, 2026-08)용.
 *
 * 표준 Wilder 방식: 첫 period개 변화량의 단순평균으로 초기화한 뒤 나머지는
 * (이전평균×(period−1) + 당일값) / period 로 지수 스무딩한다. period=2면 반응이 매우 빨라
 * 짧은 눌림에도 값이 크게 떨어진다(Connors RSI2의 의도된 성질).
 */
@Component
public class RsiCalculator {

    /**
     * @param closes 과거→최신 종가. period+1개 이상 필요(변화량 period개를 만들려면)
     * @param period RSI 기간 (RSI(2)면 2)
     * @return 0~100 RSI. 데이터 부족이면 empty. 하락이 전무하면 100.
     */
    public OptionalDouble rsi(List<Double> closes, int period) {
        if (closes == null || period < 1 || closes.size() < period + 1) {
            return OptionalDouble.empty();
        }

        // 초기 평균 — 첫 period개 변화량의 단순평균 (Wilder 초기화)
        double gainSum = 0.0;
        double lossSum = 0.0;
        for (int i = 1; i <= period; i++) {
            double change = closes.get(i) - closes.get(i - 1);
            if (change >= 0) gainSum += change;
            else             lossSum -= change;
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;

        // 이후 Wilder 스무딩
        for (int i = period + 1; i < closes.size(); i++) {
            double change = closes.get(i) - closes.get(i - 1);
            double gain = change > 0 ? change : 0.0;
            double loss = change < 0 ? -change : 0.0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }

        if (avgLoss == 0.0) return OptionalDouble.of(100.0);
        double rs = avgGain / avgLoss;
        return OptionalDouble.of(100.0 - 100.0 / (1.0 + rs));
    }
}
