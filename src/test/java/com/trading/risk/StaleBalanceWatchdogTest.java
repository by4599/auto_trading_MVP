package com.trading.risk;

import com.trading.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StaleBalanceWatchdogTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 16, 10, 0);

    private final List<String> sent = new ArrayList<>();
    private StaleBalanceWatchdog sut;

    @BeforeEach
    void setUp() {
        NotificationService notifier = sent::add;
        sut = new StaleBalanceWatchdog(null, notifier, null, null, null);
    }

    @Test
    @DisplayName("낡음이 10분 미만이면 알리지 않는다")
    void silent_below_threshold() {
        sut.observe(false, T0);
        sut.observe(false, T0.plusMinutes(9).plusSeconds(59));

        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("낡음이 10분 이어지면 한 번만 알린다")
    void alerts_once_at_threshold() {
        sut.observe(false, T0);
        sut.observe(false, T0.plusMinutes(10));
        sut.observe(false, T0.plusMinutes(30));

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("10분");
    }

    @Test
    @DisplayName("알린 뒤 회복하면 회복 알림 한 번, 그 뒤 다시 낡으면 처음부터 센다")
    void recovery_notice_then_restart() {
        sut.observe(false, T0);
        sut.observe(false, T0.plusMinutes(10));
        sut.observe(true, T0.plusMinutes(11));
        sut.observe(false, T0.plusMinutes(12));
        sut.observe(false, T0.plusMinutes(21));

        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).contains("회복");
    }

    @Test
    @DisplayName("중간에 한 번이라도 신선하면 연속 낡음이 아니다")
    void fresh_snapshot_resets_streak() {
        sut.observe(false, T0);
        sut.observe(true, T0.plusMinutes(5));
        sut.observe(false, T0.plusMinutes(6));
        sut.observe(false, T0.plusMinutes(15));

        assertThat(sent).isEmpty();
    }
}
