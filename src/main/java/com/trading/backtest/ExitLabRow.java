package com.trading.backtest;

/** Exit Lab(§14) 출구 프로필 1개의 결과 — 프로필명 + Walk-Forward 검증 결과 + §4 판정 */
public record ExitLabRow(String profileName,
                         WalkForwardEngine.WalkForwardResult result,
                         BacktestReportWriter.Judgment judgment,
                         AppliedCost appliedCost) {

    /** 비용 스윕이 아닌 랩(exit-lab/risk-lab)용 — 적용 비용은 기본값 고정이라 표기하지 않는다 */
    public ExitLabRow(String profileName, WalkForwardEngine.WalkForwardResult result,
                      BacktestReportWriter.Judgment judgment) {
        this(profileName, result, judgment, null);
    }
}
