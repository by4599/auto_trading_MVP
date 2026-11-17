package com.trading.bucket;

import com.trading.position.PortfolioStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 칸 낙폭 상한의 영속 상태 — portfolio_state(키 → 숫자)에 칸마다 저장, 재시작 후에도 남는다.
 */
@DisplayName("SleeveStateStore — 칸 최고 기록·잠금·굳힌 실현손익 영속화")
class SleeveStateStoreTest {

    private final Map<String, Double> values = new HashMap<>();
    private final SleeveStateStore store = new SleeveStateStore(InMemoryPortfolioState.create(values));

    @Test
    @DisplayName("키는 칸마다 따로 — SLEEVE_PEAK_TREND 처럼 칸 이름이 붙는다")
    void keys_are_per_bucket() {
        assertThat(SleeveStateStore.key("PEAK", StrategyBucket.TREND)).isEqualTo("SLEEVE_PEAK_TREND");
        assertThat(SleeveStateStore.key("PEAK", StrategyBucket.VB)).isEqualTo("SLEEVE_PEAK_VB");
    }

    @Test
    @DisplayName("최고 기록이 없으면 비어 있고, 저장하면 다음 조회(=재시작 후)에도 같은 값이 나온다")
    void peak_round_trip_survives_restart() {
        assertThat(store.peak(StrategyBucket.TREND)).isEmpty();

        store.savePeak(StrategyBucket.TREND, new SleeveStateStore.Peak(4_120_000, 4_000_000));

        SleeveStateStore restarted = new SleeveStateStore(InMemoryPortfolioState.create(values));
        assertThat(restarted.peak(StrategyBucket.TREND))
                .contains(new SleeveStateStore.Peak(4_120_000, 4_000_000));
        assertThat(restarted.peak(StrategyBucket.VB)).isEmpty();
    }

    @Test
    @DisplayName("잠금은 사유 숫자(낙폭·자산·한도)와 시각까지 함께 저장되고, 다른 칸은 잠기지 않는다")
    void lock_round_trip_with_reason_and_time() {
        Instant at = Instant.parse("2026-10-14T01:05:00Z");
        store.lock(StrategyBucket.TREND,
                new SleeveStateStore.LockState(true, at, 0.125, 3_500_000, 0.12));

        SleeveStateStore.LockState read = new SleeveStateStore(InMemoryPortfolioState.create(values))
                .lockState(StrategyBucket.TREND);
        assertThat(read.locked()).isTrue();
        assertThat(read.lockedAt()).isEqualTo(at);
        assertThat(read.drawdown()).isEqualTo(0.125);
        assertThat(read.equity()).isEqualTo(3_500_000);
        assertThat(read.limit()).isEqualTo(0.12);
        assertThat(store.isLocked(StrategyBucket.VB)).isFalse();
    }

    @Test
    @DisplayName("해제하면 잠금이 풀리고 최고 기록이 지정한 값으로 다시 잡힌다")
    void unlock_clears_lock_and_resets_peak() {
        store.lock(StrategyBucket.TREND,
                new SleeveStateStore.LockState(true, Instant.EPOCH, 0.13, 3_480_000, 0.12));

        store.unlock(StrategyBucket.TREND, new SleeveStateStore.Peak(3_480_000, 4_000_000));

        assertThat(store.isLocked(StrategyBucket.TREND)).isFalse();
        assertThat(store.peak(StrategyBucket.TREND))
                .contains(new SleeveStateStore.Peak(3_480_000, 4_000_000));
    }

    @Test
    @DisplayName("잠금·해제·최고 기록은 여러 줄을 한 번에(saveAll) 저장한다 — 반쯤 저장된 상태가 남지 않게")
    void writes_are_batched_into_one_save_all() {
        PortfolioStateRepository repo = mock(PortfolioStateRepository.class);
        when(repo.findAllById(anyIterable())).thenReturn(java.util.List.of());
        SleeveStateStore sut = new SleeveStateStore(repo);

        sut.lock(StrategyBucket.TREND,
                new SleeveStateStore.LockState(true, Instant.EPOCH, 0.13, 3_480_000, 0.12));

        verify(repo).saveAll(anyIterable());
    }

    @Test
    @DisplayName("굳힌 실현손익: 없으면 기본 시작일·0원, 저장하면 날짜·금액·건수가 그대로 돌아온다")
    void checkpoint_round_trip() {
        LocalDate start = LocalDate.of(2026, 7, 20);
        SleeveStateStore.Checkpoint empty = store.checkpoint(StrategyBucket.VB, start);
        assertThat(empty.until()).isEqualTo(start);
        assertThat(empty.frozen()).isEqualTo(SleeveRealized.none());

        SleeveRealized frozen = new SleeveRealized(-123_400, 2, 30, 7, null);
        store.saveCheckpoint(StrategyBucket.VB,
                new SleeveStateStore.Checkpoint(LocalDate.of(2026, 9, 14), frozen));

        SleeveStateStore.Checkpoint read = store.checkpoint(StrategyBucket.VB, start);
        assertThat(read.until()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(read.frozen()).isEqualTo(frozen);
    }
}
