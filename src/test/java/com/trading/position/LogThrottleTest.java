package com.trading.position;

import com.trading.backtest.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 경고 로그 문지기 — "처음 1회 + 이어지면 10분에 최대 1회, 참은 횟수는 다음 기록에 붙인다".
 * 1초 감시 루프에서 같은 이상이 계속되면 하루 2만 줄이 쌓이므로 줄이되, 몇 번이었는지는 잃지 않는다.
 */
@DisplayName("LogThrottle — 처음 1회 + 10분에 최대 1회 + 생략 건수")
class LogThrottleTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);

    private final MutableClock clock = new MutableClock(
            ZonedDateTime.of(DAY, LocalTime.of(10, 0), ZoneId.of("Asia/Seoul")).toInstant());
    private final LogThrottle throttle = new LogThrottle(clock, Duration.ofMinutes(10));

    @Test
    @DisplayName("처음 한 번은 바로 남기고, 그때 생략 건수는 0이다")
    void first_call_is_emitted_with_zero_skipped() {
        assertThat(throttle.tryAcquire()).hasValue(0);
    }

    @Test
    @DisplayName("10분 안에 다시 오면 참는다 — 참은 횟수는 10분 뒤 첫 기록에 붙는다")
    void calls_within_interval_are_suppressed_and_counted() {
        throttle.tryAcquire();                         // 10:00 기록

        clock.setTo(DAY, LocalTime.of(10, 3));
        assertThat(throttle.tryAcquire()).isEmpty();   // 참음 1
        clock.setTo(DAY, LocalTime.of(10, 9));
        assertThat(throttle.tryAcquire()).isEmpty();   // 참음 2

        clock.setTo(DAY, LocalTime.of(10, 10));
        assertThat(throttle.tryAcquire()).hasValue(2); // 정확히 10분 — 기록, 그동안 2회 생략
    }

    @Test
    @DisplayName("기록하면 생략 건수는 다시 0부터 센다")
    void skipped_count_resets_after_emitting() {
        throttle.tryAcquire();
        clock.setTo(DAY, LocalTime.of(10, 5));
        throttle.tryAcquire();                         // 참음 1
        clock.setTo(DAY, LocalTime.of(10, 10));
        assertThat(throttle.tryAcquire()).hasValue(1);

        clock.setTo(DAY, LocalTime.of(10, 25));
        assertThat(throttle.tryAcquire()).hasValue(0); // 그 뒤로 참은 건 없다
    }
}
