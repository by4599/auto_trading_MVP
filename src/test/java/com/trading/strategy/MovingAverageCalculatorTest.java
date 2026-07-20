package com.trading.strategy;

import com.trading.market.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("MovingAverageCalculator — 단순이동평균")
class MovingAverageCalculatorTest {

    private final MovingAverageCalculator sut = new MovingAverageCalculator();

    private List<Candle> candlesWithCloses(double... closes) {
        List<Candle> list = new ArrayList<>();
        LocalDate base = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < closes.length; i++) {
            list.add(new Candle(base.plusDays(i), closes[i], closes[i], closes[i], closes[i], 1000));
        }
        return list;
    }

    @Test
    @DisplayName("최근 period개 종가 평균")
    void computes_simple_moving_average() {
        List<Candle> candles = candlesWithCloses(10, 20, 30, 40, 50);

        OptionalDouble ma3 = sut.sma(candles, 3);

        assertThat(ma3.getAsDouble()).isCloseTo((30 + 40 + 50) / 3.0, within(1e-9));
    }

    @Test
    @DisplayName("데이터가 period보다 적으면 empty")
    void empty_when_insufficient_data() {
        List<Candle> candles = candlesWithCloses(10, 20);

        assertThat(sut.sma(candles, 5)).isEmpty();
    }

    @Test
    @DisplayName("정확히 period개면 전체 평균")
    void uses_all_when_exact_period() {
        List<Candle> candles = candlesWithCloses(10, 20, 30);

        assertThat(sut.sma(candles, 3).getAsDouble()).isCloseTo(20.0, within(1e-9));
    }
}
