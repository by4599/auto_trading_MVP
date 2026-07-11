package com.trading.risk;

import com.trading.position.Account;
import com.trading.position.PortfolioState;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.TradeResultTracker;
import com.trading.signal.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 연속 손실 3회 → 1시간 매수 중지 (F-5).
 * TradeResultTracker는 구체 클래스라 목킹 불가(Java 25 Mockito) —
 * 인메모리 저장소 스텁 위에 실객체로 구성한다.
 */
@DisplayName("ConsecutiveLossRule — 연속 손실 3회 시 1시간 매수 중지")
class ConsecutiveLossRuleTest {

    private final Map<String, PortfolioState> store = new HashMap<>();
    private TradeResultTracker tracker;
    private ConsecutiveLossRule rule;

    @BeforeEach
    void setUp() {
        store.clear();
        PortfolioStateRepository repo = mock(PortfolioStateRepository.class);
        when(repo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        when(repo.save(any(PortfolioState.class)))
                .thenAnswer(inv -> {
                    PortfolioState s = inv.getArgument(0);
                    store.put(s.getStateKey(), s);
                    return s;
                });
        tracker = new TradeResultTracker(repo);
        rule = new ConsecutiveLossRule(tracker,
                java.time.Clock.system(java.time.ZoneId.of("Asia/Seoul")));
    }

    private static Account accountWithLossCount(int losses) {
        return new Account(1_000_000.0, 0.0, losses, List.of());
    }

    @Test
    @DisplayName("연속 손실 2회 → 매수 통과")
    void passes_below_threshold() {
        RiskResult result = rule.validate(
                Signal.buy("005930", "test"), accountWithLossCount(2));

        assertThat(result.isPass()).isTrue();
    }

    @Test
    @DisplayName("연속 손실 3회 → 매수 거부 + 1시간 차단 개시")
    void blocks_at_threshold() {
        RiskResult first = rule.validate(
                Signal.buy("005930", "test"), accountWithLossCount(3));
        assertThat(first.isPass()).isFalse();

        // 차단 창 내 재시도 — 카운트가 리셋됐어도 시간 차단은 유지된다
        RiskResult second = rule.validate(
                Signal.buy("005930", "test"), accountWithLossCount(0));
        assertThat(second.isPass()).isFalse();
    }

    @Test
    @DisplayName("차단 개시 시 스트릭 리셋 — 1시간 후 무기한 재차단 방지")
    void resets_streak_when_block_engages() {
        tracker.recordSellFill("005930", 1, 72_000.0, 73_000.0);
        tracker.recordSellFill("005930", 1, 71_000.0, 72_000.0);
        tracker.recordSellFill("005930", 1, 70_000.0, 71_000.0);
        assertThat(tracker.getConsecutiveLossCount()).isEqualTo(3);

        rule.validate(Signal.buy("005930", "test"), accountWithLossCount(3));

        // 차단이 걸리는 순간 스트릭이 0으로 리셋 — 차단 해제 후 즉시 재차단되지 않는다
        assertThat(tracker.getConsecutiveLossCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("SELL 신호는 차단 중에도 통과 (출구는 막지 않는다)")
    void sell_signals_always_pass() {
        rule.validate(Signal.buy("005930", "test"), accountWithLossCount(3)); // 차단 개시

        RiskResult sell = rule.validate(
                Signal.sell("005930", "TimeCut-1515"), accountWithLossCount(0));

        assertThat(sell.isPass()).isTrue();
    }
}
