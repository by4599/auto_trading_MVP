package com.trading.strategy;

import com.trading.market.Candle;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BreakoutCalculatorTest {

    @Test
    void target_is_open_plus_range_times_k() {
        // 전일 range 2,000 (71,000-69,000), 당일 시가 70,000, K=0.5 → 71,000
        assertThat(BreakoutCalculator.targetPrice(71_000, 69_000, 70_000, 0.5))
                .isEqualTo(71_000);
    }

    @Test
    void candle_overload_matches_scalar_version() {
        Candle yesterday = new Candle(LocalDate.of(2026, 7, 14), 69_500, 71_000, 69_000, 70_500, 1_000);
        Candle today     = new Candle(LocalDate.of(2026, 7, 15), 70_000, 70_800, 69_900, 70_600, 500);

        assertThat(BreakoutCalculator.targetPrice(yesterday, today, 0.5))
                .isEqualTo(BreakoutCalculator.targetPrice(71_000, 69_000, 70_000, 0.5));
    }
}
