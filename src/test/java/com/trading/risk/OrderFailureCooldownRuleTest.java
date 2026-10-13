package com.trading.risk;

import com.trading.order.OrderFailureTracker;
import com.trading.position.Account;
import com.trading.signal.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 실패 종목의 신규 매수 차단 룰 — 매도는 막지 않는다(방어 경로 지연 금지).
 */
@DisplayName("OrderFailureCooldownRule — 실패 후 신규 매수 차단")
class OrderFailureCooldownRuleTest {

    private Instant now = Instant.parse("2026-08-04T01:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    private OrderFailureTracker tracker;
    private OrderFailureCooldownRule sut;

    @BeforeEach
    void setUp() {
        tracker = new OrderFailureTracker(clock, 60, 600);
        sut = new OrderFailureCooldownRule(tracker);
    }

    private Account account() {
        return new Account(10_000_000, 0.0, 0, java.util.List.of());
    }

    @Test
    @DisplayName("쿨다운 중인 종목의 매수는 거부된다")
    void rejects_buy_while_cooling_down() {
        tracker.recordRejected("034020");

        RiskResult result = sut.validate(Signal.buy("034020", "TEST"), account());

        assertThat(result.isPass()).isFalse();
        assertThat(result.getReason()).contains("034020");
    }

    @Test
    @DisplayName("쿨다운이 없으면 통과한다")
    void passes_when_not_cooling_down() {
        assertThat(sut.validate(Signal.buy("005930", "TEST"), account()).isPass()).isTrue();
    }

    @Test
    @DisplayName("매도는 쿨다운 중에도 통과한다 — 손절·타임컷을 지연시키면 안 되므로")
    void never_blocks_sell() {
        tracker.recordAmbiguous("034020");

        RiskResult result = sut.validate(Signal.sell("034020", "StopLoss"), account());

        assertThat(result.isPass()).isTrue();
    }
}
