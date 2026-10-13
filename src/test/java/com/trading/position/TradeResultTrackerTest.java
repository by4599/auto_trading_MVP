package com.trading.position;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 연속 손실 카운터 (F-5 나머지) 단위 테스트.
 * PortfolioStateRepository(인터페이스)를 인메모리 맵으로 스텁해 영속화 동작까지 검증한다.
 *
 * 집계 단위는 라운드트립(진입~전량 청산) 1회다 — 조각 체결 분해는 호출부(FillStateUpdater)가
 * 맡고, 여기는 "확정된 한 매매의 손익"만 받는다.
 */
@DisplayName("TradeResultTracker — 실현손익 기반 연속 손실 카운터")
class TradeResultTrackerTest {

    private final Map<String, PortfolioState> store = new HashMap<>();
    private TradeResultTracker tracker;

    @BeforeEach
    void setUp() {
        store.clear();
        PortfolioStateRepository repo = mock(PortfolioStateRepository.class);
        when(repo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        when(repo.save(org.mockito.ArgumentMatchers.any(PortfolioState.class)))
                .thenAnswer(inv -> {
                    PortfolioState s = inv.getArgument(0);
                    store.put(s.getStateKey(), s);
                    return s;
                });
        tracker = new TradeResultTracker(repo);
    }

    @Test
    @DisplayName("손실 라운드트립 → 카운트 1 증가")
    void loss_increments_streak() {
        tracker.recordRoundTrip("005930", -1_000.0);

        assertThat(tracker.getConsecutiveLossCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("연속 손실 3회 → 카운트 3 누적")
    void three_losses_accumulate() {
        tracker.recordRoundTrip("005930", -1_000.0);
        tracker.recordRoundTrip("005930", -1_000.0);
        tracker.recordRoundTrip("005930", -1_000.0);

        assertThat(tracker.getConsecutiveLossCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("수익 라운드트립 → 스트릭 0으로 리셋")
    void profit_resets_streak() {
        tracker.recordRoundTrip("005930", -1_000.0);
        tracker.recordRoundTrip("005930", -1_000.0);
        tracker.recordRoundTrip("005930", +1_000.0);

        assertThat(tracker.getConsecutiveLossCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("본전 라운드트립(realized == 0) → 손실 아님, 스트릭 리셋")
    void break_even_resets_streak() {
        tracker.recordRoundTrip("005930", -1_000.0);
        tracker.recordRoundTrip("005930", 0.0);

        assertThat(tracker.getConsecutiveLossCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("기록 없음 → 카운트 0")
    void empty_state_returns_zero() {
        assertThat(tracker.getConsecutiveLossCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("resetStreak() → 카운트 0으로 영속화")
    void reset_streak_persists_zero() {
        tracker.recordRoundTrip("005930", -1_000.0);
        tracker.resetStreak();

        assertThat(tracker.getConsecutiveLossCount()).isEqualTo(0);
        assertThat(store.get(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT).getStateValue()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("카운트는 portfolio_state에 영속화된다 (재시작 안전)")
    void streak_survives_new_tracker_instance() {
        tracker.recordRoundTrip("005930", -1_000.0);
        tracker.recordRoundTrip("005930", -1_000.0);

        // 재시작 시뮬레이션: 같은 저장소로 새 인스턴스 생성
        PortfolioStateRepository repo = mock(PortfolioStateRepository.class);
        when(repo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        TradeResultTracker restarted = new TradeResultTracker(repo);

        assertThat(restarted.getConsecutiveLossCount()).isEqualTo(2);
    }
}
