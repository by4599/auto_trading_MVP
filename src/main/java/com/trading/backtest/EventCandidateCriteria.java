package com.trading.backtest;

/**
 * B-4 CANDIDATE 판정 기준 (설계 S3) — 표본 ≥ 30건 AND D+5 하위 25% 수익률 > 왕복 비용.
 *
 * <p>레지스트리 갱신과 리포트 표기가 <b>같은 기준</b>을 보도록 한곳에 둔다.
 * PROMOTED 승격은 사람만 한다(게이트 G2) — 여기서는 절대 하지 않는다.
 */
final class EventCandidateCriteria {

    static final int MIN_SAMPLES_FOR_CANDIDATE = 30;

    private EventCandidateCriteria() {
    }

    static boolean meets(EventStatsBacktester.EventStat s) {
        return meets(s.samples(), s.at(5));
    }

    static boolean meets(int samples, EventStatsBacktester.Quantiles d5) {
        return samples >= MIN_SAMPLES_FOR_CANDIDATE && d5.p25() > BacktestCosts.ROUND_TRIP_COST;
    }
}
