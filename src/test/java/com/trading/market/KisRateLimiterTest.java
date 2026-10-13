package com.trading.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KisRateLimiter — 초당 한도 균등 배분")
class KisRateLimiterTest {

    @Test
    @DisplayName("첫 호출은 대기 없이 즉시 통과")
    void first_acquire_is_immediate() {
        KisRateLimiter limiter = new KisRateLimiter(2); // 500ms 간격
        long start = System.nanoTime();

        limiter.acquire();

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs).isLessThan(100L);
    }

    @Test
    @DisplayName("연속 호출을 한도 간격만큼 벌린다 (100/초 → 10회에 최소 90ms)")
    void spaces_calls_to_respect_rate() {
        KisRateLimiter limiter = new KisRateLimiter(100); // 10ms 간격
        long start = System.nanoTime();

        for (int i = 0; i < 10; i++) limiter.acquire();

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(90L); // 첫 회 즉시 + 9간격
    }
}
