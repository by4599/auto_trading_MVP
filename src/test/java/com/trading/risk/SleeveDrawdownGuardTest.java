package com.trading.risk;

import com.trading.NotificationService;
import com.trading.bucket.InMemoryPortfolioState;
import com.trading.bucket.SleeveDrawdownProperties;
import com.trading.bucket.SleeveEquity;
import com.trading.bucket.SleeveRealized;
import com.trading.bucket.SleeveStateStore;
import com.trading.bucket.StrategyBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 칸 낙폭 판정의 핵심 — 최고 기록(영속·단조), 2회 연속 확인, 잠금, 사람만 하는 해제.
 * 상태는 맵 기반 portfolio_state 목에 저장되므로 같은 맵으로 새 판정기를 만들면 재시작이 된다.
 */
@DisplayName("SleeveDrawdownGuard — 칸 최고 기록·2회 연속 확인·잠금·해제")
class SleeveDrawdownGuardTest {

    private static final double TREND_ALLOCATION = 4_000_000;
    private static final Instant NOW = Instant.parse("2026-10-14T01:05:00Z");

    private final Map<String, Double> state = new HashMap<>();
    private final NotificationService notifier = mock(NotificationService.class);
    private SleeveDrawdownGuard guard = newGuard();

    private SleeveDrawdownGuard newGuard() {
        return new SleeveDrawdownGuard(new SleeveStateStore(InMemoryPortfolioState.create(state)),
                new SleeveDrawdownProperties(0.12, 0.20), notifier, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    }

    private SleeveStateStore store() {
        return new SleeveStateStore(InMemoryPortfolioState.create(state));
    }

    private static SleeveEquity trend(double equity) {
        return sleeve(StrategyBucket.TREND, TREND_ALLOCATION, equity);
    }

    private static SleeveEquity sleeve(StrategyBucket bucket, double allocation, double equity) {
        return new SleeveEquity(bucket, allocation,
                new SleeveRealized(equity - allocation, 0, 1, 0, null), 0.0);
    }

    private double storedPeak() {
        return store().peak(StrategyBucket.TREND).orElseThrow().value();
    }

    // ── 최고 기록 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("처음 판정하면 최고 기록을 배분금(400만)으로 저장한다")
    void first_evaluation_starts_peak_at_allocation() {
        guard.evaluate(trend(3_950_000));

        assertThat(storedPeak()).isEqualTo(TREND_ALLOCATION);
    }

    @Test
    @DisplayName("최고 기록은 연속 2회 확인된 값(둘 중 낮은 값)으로만 오른다")
    void peak_rises_only_on_two_consecutive_readings() {
        guard.evaluate(trend(4_200_000));
        assertThat(storedPeak()).isEqualTo(TREND_ALLOCATION);   // 한 번 본 값으로는 안 올린다

        guard.evaluate(trend(4_300_000));
        assertThat(storedPeak()).isEqualTo(4_200_000);           // 둘 다 넘은 만큼만
    }

    @Test
    @DisplayName("한 번 튄 값은 최고 기록을 오염시키지 못한다 (결함 6 전고점 오염의 교훈)")
    void single_spike_cannot_raise_peak() {
        guard.evaluate(trend(4_000_000));
        guard.evaluate(trend(9_999_999));   // 튄 값
        guard.evaluate(trend(4_010_000));

        assertThat(storedPeak()).isEqualTo(4_010_000);
    }

    @Test
    @DisplayName("최고 기록은 저장돼 재시작 뒤에도 그대로 쓰인다 — 내려가지 않는다")
    void peak_is_persistent_and_monotonic() {
        guard.evaluate(trend(4_400_000));
        guard.evaluate(trend(4_400_000));

        guard = newGuard();   // 재시작
        guard.evaluate(trend(4_100_000));
        guard.evaluate(trend(4_100_000));

        assertThat(storedPeak()).isEqualTo(4_400_000);
    }

    @Test
    @DisplayName("배분금을 바꾸면 최고 기록도 같은 금액만큼 옮긴다 — 설정 변경으로 잠기지 않는다")
    void allocation_change_rebases_peak() {
        guard.evaluate(trend(4_100_000));
        guard.evaluate(trend(4_100_000));   // 최고 410만 (배분 400만 기준)

        SleeveDrawdownGuard.Decision d = guard.evaluate(sleeve(StrategyBucket.TREND, 3_000_000, 3_100_000));
        guard.evaluate(sleeve(StrategyBucket.TREND, 3_000_000, 3_100_000));

        assertThat(storedPeak()).isEqualTo(3_100_000);
        assertThat(d.outcome()).isEqualTo(SleeveDrawdownGuard.Outcome.WITHIN_LIMIT);
        assertThat(store().isLocked(StrategyBucket.TREND)).isFalse();
    }

    // ── 2회 연속 확인과 잠금 ──────────────────────────────────────────────────

    @Test
    @DisplayName("한도 초과 1회는 잠그지 않는다, 1분 뒤 2회째도 초과면 잠근다")
    void locks_only_after_two_consecutive_breaches() {
        SleeveDrawdownGuard.Decision first = guard.evaluate(trend(3_500_000));   // 낙폭 12.5%
        assertThat(first.outcome()).isEqualTo(SleeveDrawdownGuard.Outcome.BREACH_UNCONFIRMED);
        assertThat(store().isLocked(StrategyBucket.TREND)).isFalse();

        SleeveDrawdownGuard.Decision second = guard.evaluate(trend(3_490_000));
        assertThat(second.outcome()).isEqualTo(SleeveDrawdownGuard.Outcome.LOCKED_NOW);
        assertThat(store().isLocked(StrategyBucket.TREND)).isTrue();
    }

    @Test
    @DisplayName("사이에 한도 안쪽 값이 끼면 다시 처음부터 센다")
    void recovery_between_breaches_resets_the_count() {
        guard.evaluate(trend(3_500_000));
        guard.evaluate(trend(3_700_000));   // 7.5% — 한도 안쪽
        SleeveDrawdownGuard.Decision d = guard.evaluate(trend(3_500_000));

        assertThat(d.outcome()).isEqualTo(SleeveDrawdownGuard.Outcome.BREACH_UNCONFIRMED);
        assertThat(store().isLocked(StrategyBucket.TREND)).isFalse();
    }

    @Test
    @DisplayName("보류(resetStreak)가 끼면 연속이 끊긴다 — 하루 건너 두 번은 '연속'이 아니다")
    void reset_streak_breaks_the_sequence() {
        guard.evaluate(trend(3_500_000));
        guard.resetStreak(StrategyBucket.TREND);
        SleeveDrawdownGuard.Decision d = guard.evaluate(trend(3_500_000));

        assertThat(d.outcome()).isEqualTo(SleeveDrawdownGuard.Outcome.BREACH_UNCONFIRMED);
    }

    @Test
    @DisplayName("한도에 딱 닿아도(12.0%) 초과로 센다 — ADR '도달 시'")
    void exact_limit_counts_as_breach() {
        SleeveDrawdownGuard.Decision d = guard.evaluate(trend(3_520_000));

        assertThat(d.drawdown()).isEqualTo(0.12);
        assertThat(d.outcome()).isEqualTo(SleeveDrawdownGuard.Outcome.BREACH_UNCONFIRMED);
    }

    @Test
    @DisplayName("B동(VB)은 20%가 한도다 — 15% 낙폭으로는 잠기지 않는다")
    void b_sleeve_uses_twenty_percent() {
        guard.evaluate(sleeve(StrategyBucket.VB, 10_000_000, 8_500_000));
        SleeveDrawdownGuard.Decision d = guard.evaluate(sleeve(StrategyBucket.VB, 10_000_000, 8_500_000));

        assertThat(d.outcome()).isEqualTo(SleeveDrawdownGuard.Outcome.WITHIN_LIMIT);
        assertThat(d.limit()).isEqualTo(0.20);
    }

    @Test
    @DisplayName("잠그면 사유 숫자와 시각을 저장하고 텔레그램으로 알린다")
    void lock_persists_reason_and_notifies() {
        guard.evaluate(trend(3_500_000));
        guard.evaluate(trend(3_500_000));

        SleeveStateStore.LockState lock = store().lockState(StrategyBucket.TREND);
        assertThat(lock.lockedAt()).isEqualTo(NOW);
        assertThat(lock.drawdown()).isEqualTo(0.125);
        assertThat(lock.equity()).isEqualTo(3_500_000);
        assertThat(lock.limit()).isEqualTo(0.12);
        verify(notifier).sendCritical(contains("칸이 잠겼습니다"));
    }

    @Test
    @DisplayName("잠긴 동안에는 판정하지 않는다 — 최고 기록도 오르지 않는다")
    void locked_sleeve_is_not_evaluated() {
        guard.evaluate(trend(3_500_000));
        guard.evaluate(trend(3_500_000));

        SleeveDrawdownGuard.Decision d1 = guard.evaluate(trend(4_500_000));
        guard.evaluate(trend(4_500_000));

        assertThat(d1.outcome()).isEqualTo(SleeveDrawdownGuard.Outcome.ALREADY_LOCKED);
        assertThat(storedPeak()).isEqualTo(TREND_ALLOCATION);
    }

    // ── 해제 (사람만) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("해제하면 잠금이 풀리고 최고 기록을 지금 칸 자산으로 다시 잡는다 — 바로 다시 잠기지 않는다")
    void unlock_resets_peak_to_current_equity() {
        guard.evaluate(trend(3_500_000));
        guard.evaluate(trend(3_500_000));

        boolean unlocked = guard.unlock(trend(3_480_000), "손실 원인 확인 — 재가동");

        assertThat(unlocked).isTrue();
        assertThat(store().isLocked(StrategyBucket.TREND)).isFalse();
        assertThat(storedPeak()).isEqualTo(3_480_000);
        verify(notifier).sendCritical(contains("손실 원인 확인 — 재가동"));

        guard.evaluate(trend(3_470_000));
        SleeveDrawdownGuard.Decision d = guard.evaluate(trend(3_470_000));
        assertThat(d.outcome()).isEqualTo(SleeveDrawdownGuard.Outcome.WITHIN_LIMIT);
    }

    @Test
    @DisplayName("잠기지 않은 칸은 해제할 것이 없다 — 아무것도 바꾸지 않는다")
    void unlock_of_unlocked_sleeve_is_a_no_op() {
        boolean unlocked = guard.unlock(trend(3_000_000), "실수");

        assertThat(unlocked).isFalse();
        assertThat(store().peak(StrategyBucket.TREND)).isEmpty();
        verify(notifier, never()).sendCritical(anyString());
    }
}
