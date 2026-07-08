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
    @DisplayName("손실 매도 → 카운트 1 증가")
    void loss_increments_streak() {
        tracker.recordSellFill("005930", 1, 72_000.0, 73_000.0); // -1,000

        assertThat(tracker.getConsecutiveLossCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("연속 손실 3회 → 카운트 3 누적")
    void three_losses_accumulate() {
        tracker.recordSellFill("005930", 1, 72_000.0, 73_000.0);
        tracker.recordSellFill("005930", 1, 71_000.0, 72_000.0);
        tracker.recordSellFill("005930", 1, 70_000.0, 71_000.0);

        assertThat(tracker.getConsecutiveLossCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("수익 매도 → 스트릭 0으로 리셋")
    void profit_resets_streak() {
        tracker.recordSellFill("005930", 1, 72_000.0, 73_000.0);
        tracker.recordSellFill("005930", 1, 71_000.0, 72_000.0);
        tracker.recordSellFill("005930", 1, 74_000.0, 73_000.0); // +1,000

        assertThat(tracker.getConsecutiveLossCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("본전 매도(realized == 0) → 손실 아님, 스트릭 리셋")
    void break_even_resets_streak() {
        tracker.recordSellFill("005930", 1, 72_000.0, 73_000.0);
        tracker.recordSellFill("005930", 1, 73_000.0, 73_000.0); // 0

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
        tracker.recordSellFill("005930", 1, 72_000.0, 73_000.0);
        tracker.resetStreak();

        assertThat(tracker.getConsecutiveLossCount()).isEqualTo(0);
        assertThat(store.get(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT).getStateValue()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("카운트는 portfolio_state에 영속화된다 (재시작 안전)")
    void streak_survives_new_tracker_instance() {
        tracker.recordSellFill("005930", 1, 72_000.0, 73_000.0);
        tracker.recordSellFill("005930", 1, 71_000.0, 72_000.0);

        // 재시작 시뮬레이션: 같은 저장소로 새 인스턴스 생성
        PortfolioStateRepository repo = mock(PortfolioStateRepository.class);
        when(repo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        TradeResultTracker restarted = new TradeResultTracker(repo);

        assertThat(restarted.getConsecutiveLossCount()).isEqualTo(2);
    }
}
