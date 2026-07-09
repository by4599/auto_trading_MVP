package com.trading.market;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.OptionalDouble;

/**
 * ATR(14) 계산기 — 종목의 "평상시 하루 움직임 폭" (방법론 §4.1).
 *
 * TR(True Range) = max(고가-저가, |고가-전일종가|, |저가-전일종가|)
 * ATR = 최근 14개 TR의 단순평균.
 * (와일더 지수평활 방식과의 차이는 B-3 백테스트에서 A/B 검증 대상 — 우선 단순평균 채택)
 */
@Component
public class AtrCalculator {

    public static final int PERIOD = 14;

    /**
     * @param candles 완성 일봉, 과거→최신 순. PERIOD+1개(15개) 이상 필요
     *                (첫 TR 계산에 전일 종가가 필요하므로).
     * @return ATR. 데이터 부족이거나 0 이하(파싱 실패 캔들 등)면 empty.
     */
    public OptionalDouble atr(List<Candle> candles) {
        if (candles == null || candles.size() < PERIOD + 1) return OptionalDouble.empty();

        List<Candle> recent = candles.subList(candles.size() - (PERIOD + 1), candles.size());
        double sum = 0.0;
        for (int i = 1; i < recent.size(); i++) {
            Candle cur = recent.get(i);
            Candle prev = recent.get(i - 1);
            double tr = Math.max(cur.high() - cur.low(),
                    Math.max(Math.abs(cur.high() - prev.close()),
                             Math.abs(cur.low() - prev.close())));
            sum += tr;
        }

        double atr = sum / PERIOD;
        return atr > 0 ? OptionalDouble.of(atr) : OptionalDouble.empty();
    }
}
