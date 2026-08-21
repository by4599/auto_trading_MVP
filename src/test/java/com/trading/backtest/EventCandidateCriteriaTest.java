package com.trading.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-4 CANDIDATE 판정 기준 — 표본 ≥ 30건 AND D+5 하위 25% 수익률 > 왕복 비용(0.41%).
 *
 * 레지스트리 기록({@code EventRegistryUpdater})과 리포트 표기({@code EventReportWriter})가
 * 같은 기준을 보게 하려고 한곳으로 뽑은 판정이라, 경계값이 뒤집히면 두 곳이 함께 틀린다.
 */
@DisplayName("EventCandidateCriteria — B-4 CANDIDATE 문턱")
class EventCandidateCriteriaTest {

    private static EventStatsBacktester.Quantiles d5(double p25) {
        return new EventStatsBacktester.Quantiles(0, p25, 0, 0);
    }

    @Test
    @DisplayName("표본 30건 이상 + D+5 p25가 왕복비용 초과 → CANDIDATE")
    void meets_when_samples_and_p25_pass() {
        assertThat(EventCandidateCriteria.meets(30, d5(0.005))).isTrue();
    }

    @Test
    @DisplayName("표본이 29건이면 수익률이 아무리 좋아도 미달")
    void fails_below_min_samples() {
        assertThat(EventCandidateCriteria.meets(29, d5(0.05))).isFalse();
    }

    @Test
    @DisplayName("표본이 충분해도 D+5 p25가 왕복비용 이하면 미달")
    void fails_when_p25_below_cost() {
        assertThat(EventCandidateCriteria.meets(100, d5(0.001))).isFalse();
    }

    @Test
    @DisplayName("D+5 p25가 왕복비용과 같으면 미달 — 비용을 넘어야 한다")
    void fails_when_p25_equals_cost() {
        assertThat(EventCandidateCriteria.meets(100, d5(BacktestCosts.ROUND_TRIP_COST))).isFalse();
    }
}
