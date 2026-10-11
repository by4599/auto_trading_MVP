package com.trading.risk;

import com.trading.bucket.StrategyBucket;
import com.trading.position.Account;
import com.trading.signal.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 낡은 잔고로 신규 매수 금지 (28_audit M-3) — 잔고 조회 실패로 넘어온 캐시·DB 폴백 스냅샷은
 * {@link Account#asStale()}로 표시된다. 매수만 보류하고 매도는 절대 막지 않는다.
 */
@DisplayName("StaleAccountBuyGuardRule — 낡은 잔고 스냅샷이면 신규 매수 보류")
class StaleAccountBuyGuardRuleTest {

    private static Account fresh() {
        return new Account(10_000_000, 0.0, 0, List.of());
    }

    private static Account stale() {
        return fresh().asStale();
    }

    private static Signal buy() {
        return Signal.buy("005930", "DONCHIAN", StrategyBucket.TREND);
    }

    private static Signal sell() {
        return Signal.sell("005930", "STOP_LOSS");
    }

    @Test
    @DisplayName("낡은 스냅샷 + 매수 → 거부")
    void stale_account_blocks_buy() {
        RiskResult result = new StaleAccountBuyGuardRule(true).validate(buy(), stale());

        assertThat(result.isPass()).isFalse();
        assertThat(result.getReason()).contains("잔고 정보가 낡음");
    }

    @Test
    @DisplayName("신선한 스냅샷 + 매수 → 통과")
    void fresh_account_passes_buy() {
        assertThat(new StaleAccountBuyGuardRule(true).validate(buy(), fresh()).isPass()).isTrue();
    }

    @Test
    @DisplayName("낡은 스냅샷이어도 매도는 통과 — 손절·타임컷을 늦추지 않는다")
    void sell_passes_even_when_stale() {
        assertThat(new StaleAccountBuyGuardRule(true).validate(sell(), stale()).isPass()).isTrue();
    }

    @Test
    @DisplayName("스위치가 꺼져 있으면 낡아도 통과")
    void disabled_guard_passes() {
        assertThat(new StaleAccountBuyGuardRule(false).validate(buy(), stale()).isPass()).isTrue();
    }
}
