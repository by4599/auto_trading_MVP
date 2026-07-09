package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clock 주입(F-8) 검증 — 서버 타임존과 무관하게 KST 기준으로 판정한다.
 */
@DisplayName("MarketCloseRule — 15:20 이후 신규 매수 금지 (KST Clock)")
class MarketCloseRuleTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Account ACCOUNT = new Account(1_000_000, 0.0, 0, List.of());

    private static MarketCloseRule ruleAt(int hour, int minute) {
        Clock fixed = Clock.fixed(
                LocalDateTime.of(2026, 7, 8, hour, minute).atZone(KST).toInstant(), KST);
        return new MarketCloseRule(fixed);
    }

    @Test
    @DisplayName("15:19 매수 → 통과")
    void buy_passes_before_cutoff() {
        assertThat(ruleAt(15, 19).validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isTrue();
    }

    @Test
    @DisplayName("15:21 매수 → 거부")
    void buy_rejected_after_cutoff() {
        assertThat(ruleAt(15, 21).validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isFalse();
    }

    @Test
    @DisplayName("15:20 정각 매수 → 통과 (isAfter — 정각까지 허용)")
    void buy_passes_at_exact_cutoff() {
        assertThat(ruleAt(15, 20).validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isTrue();
    }

    @Test
    @DisplayName("15:21 매도 → 통과 (매도는 시간 제한 없음)")
    void sell_always_passes() {
        assertThat(ruleAt(15, 21).validate(Signal.sell("005930", "t"), ACCOUNT).isPass()).isTrue();
    }
}
