package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DailyLossRuleTest {

    private DailyLossRule sut;

    @BeforeEach
    void setUp() {
        sut = new DailyLossRule(new RiskLimitsProperties());
    }

    private static Account accountWithPnl(double dailyPnlPercent) {
        return new Account(50_000_000, dailyPnlPercent, 0, List.of());
    }

    @Test
    void pnl_above_block_threshold_passes() {
        RiskResult result = sut.validate(Signal.buy("005930", "TEST"), accountWithPnl(-0.029));
        assertThat(result.isPass()).isTrue();
    }

    @Test
    void pnl_at_minus_3_percent_blocks_new_buy() {
        RiskResult result = sut.validate(Signal.buy("005930", "TEST"), accountWithPnl(-0.03));
        assertThat(result.isPass()).isFalse();
        assertThat(result.getReason()).contains("-3%");
    }

    @Test
    void pnl_at_minus_5_percent_rejects_with_liquidate_message() {
        RiskResult result = sut.validate(Signal.buy("005930", "TEST"), accountWithPnl(-0.05));
        assertThat(result.isPass()).isFalse();
        assertThat(result.getReason()).contains("-5%");
    }

    @Test
    void sell_signal_always_passes() {
        RiskResult result = sut.validate(Signal.sell("005930", "TEST"), accountWithPnl(-0.10));
        assertThat(result.isPass()).isTrue();
    }
}
