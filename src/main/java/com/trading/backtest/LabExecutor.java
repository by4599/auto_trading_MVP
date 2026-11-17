package com.trading.backtest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 랩 프로필 1개 실행 — Walk-Forward 재생 + §4 판정을 한 줄로 묶는다.
 *
 * <p>랩마다 똑같이 반복하던 3단계(walkForward.run → ReportData 조립 → judge)를 모은 것이다.
 * K는 고정 0.5 — 랩의 진입 전략(MA·돈치안·RSI 등)은 VB의 K를 쓰지 않으므로 단일값으로 호출해
 * 의미 없는 3배 반복을 피한다(이전 동작과 동일).
 */
@Component
@Profile("backtest")
public class LabExecutor {

    static final List<Double> FIXED_K = List.of(0.5);

    private final WalkForwardEngine walkForwardEngine;
    private final BacktestReportWriter reportWriter;

    public LabExecutor(WalkForwardEngine walkForwardEngine, BacktestReportWriter reportWriter) {
        this.walkForwardEngine = walkForwardEngine;
        this.reportWriter = reportWriter;
    }

    public ExitLabRow evaluate(String profileName, LabScope scope) {
        return evaluate(profileName, scope, null);
    }

    /** 비용 스윕용 — 실제 적용 비용을 행에 함께 싣는다 */
    public ExitLabRow evaluate(String profileName, LabScope scope, AppliedCost applied) {
        WalkForwardEngine.WalkForwardResult result = walkForwardEngine.run(
                scope.symbols(), scope.from(), scope.to(), FIXED_K, null);
        BacktestReportWriter.ReportData judgeData = new BacktestReportWriter.ReportData(
                scope.symbols(), scope.from(), scope.to(), Map.of(), result, List.of(),
                scope.label(), scope.slug());
        return new ExitLabRow(profileName, result, reportWriter.judge(judgeData), applied);
    }

    /** Walk-Forward 6/3/3 윈도우 개수 (배너용) */
    public static int windowCount(LabScope scope) {
        return WalkForwardEngine.windows(scope.from(), scope.to()).size();
    }
}
