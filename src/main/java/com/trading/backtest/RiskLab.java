package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Risk Lab (§14) — 검증된 엣지를 MDD 기준 안으로 넣는 리스크 축소 스윕.
 *
 * <p>진입=enableEntry, 출구=P3(ATR1.0·타임컷OFF·20일·트레일 arm1%/trail3%) 고정. 사이징(1R
 * 비율)과 동시보유 종목수만 바꾼다 — 손익비·기대값은 사이징에 불변이고 MDD만 스케일다운되므로,
 * exit-lab에서 MDD만 아슬하게 넘긴 후보를 §4 안으로 넣는지 본다.
 * writeBaseline=false면 거버넌스 기준선을 건드리지 않는다.
 */
@Component
@Profile("backtest")
public class RiskLab {

    private static final Logger log = LoggerFactory.getLogger(RiskLab.class);

    private record RiskProfile(String name, double riskFraction, int maxPositions) {}

    private static final List<RiskProfile> RISK_PROFILES = List.of(
            new RiskProfile("RR0 기준(1.0R·동시5)",   0.01,  5),
            new RiskProfile("RR1 하프(0.5R·동시5)",    0.005, 5),
            new RiskProfile("RR2 하프·집중(0.5R·동시3)", 0.005, 3),
            new RiskProfile("RR3 쿼터(0.25R·동시5)",   0.0025, 5),
            new RiskProfile("RR4 하프·최집중(0.5R·동시2)", 0.005, 2));

    private final LabExecutor executor;
    private final LabComparisonReportWriter reportWriter;
    private final ExecutionKnobs knobs;

    public RiskLab(LabExecutor executor, LabComparisonReportWriter reportWriter, ExecutionKnobs knobs) {
        this.executor = executor;
        this.reportWriter = reportWriter;
        this.knobs = knobs;
    }

    public void run(LabScope scope, Runnable enableEntry, boolean writeBaseline) {
        enableEntry.run();
        knobs.applyExitProfile(ExitProfile.P3);

        List<ExitLabRow> rows = new ArrayList<>();
        for (RiskProfile rp : RISK_PROFILES) {
            knobs.applySizing(rp.riskFraction(), rp.maxPositions());
            ExitLabRow row = executor.evaluate(rp.name(), scope);
            log.info("[RiskLab] {} · {}: {} → {}", scope.slug(), rp.name(),
                    row.result().aggregateValidation().summaryLine(), ReportFormat.verdict(row.judgment()));
            rows.add(row);
        }
        // 기본값 복원
        knobs.resetSizing();
        knobs.resetExitProfile();

        reportWriter.writeRiskLabReport(scope, rows, writeBaseline);
    }
}
