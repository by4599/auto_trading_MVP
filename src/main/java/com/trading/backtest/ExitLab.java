package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Exit Lab (BACKTEST-DESIGN §14) — 손익비 재설계(다일 보유).
 *
 * <p>진입 로직은 고정(enableEntry)하고 출구 프로필만 스윕한다. K는 VB 기본 0.5로 고정
 * (출구 효과를 격리하려고 진입 파라미터는 건드리지 않는다). 프로필은 5개로 제한(§3.3).
 */
@Component
@Profile("backtest")
public class ExitLab {

    private static final Logger log = LoggerFactory.getLogger(ExitLab.class);

    static final List<ExitProfile> EXIT_PROFILES = List.of(
            new ExitProfile("P0 기준선(ATR1.5·타임컷ON)",           1.5, true,  0,  false, 0,    0),
            new ExitProfile("P1 타이트손절·당일(ATR1.0·타임컷ON)",  1.0, true,  0,  false, 0,    0),
            new ExitProfile("P2 다일·느슨트레일(ATR1.0·20일·arm2%/trail5%)", 1.0, false, 20, true,  0.02, 0.05),
            new ExitProfile("P3 다일·조인트레일(ATR1.0·20일·arm1%/trail3%)", 1.0, false, 20, true,  0.01, 0.03),
            new ExitProfile("P4 다일·순수손절(ATR1.0·20일·트레일OFF)",       1.0, false, 20, false, 0,    0));

    private final LabExecutor executor;
    private final LabComparisonReportWriter reportWriter;
    private final ExecutionKnobs knobs;

    public ExitLab(LabExecutor executor, LabComparisonReportWriter reportWriter, ExecutionKnobs knobs) {
        this.executor = executor;
        this.reportWriter = reportWriter;
        this.knobs = knobs;
    }

    public void run(LabScope scope, Runnable enableEntry) {
        enableEntry.run();
        List<ExitLabRow> rows = new ArrayList<>();
        for (ExitProfile p : EXIT_PROFILES) {
            knobs.applyExitProfile(p);
            ExitLabRow row = executor.evaluate(p.name(), scope);
            log.info("[ExitLab] {} · {}: {} → {}", scope.label(), p.name(),
                    row.result().aggregateValidation().summaryLine(), ReportFormat.verdict(row.judgment()));
            rows.add(row);
        }
        knobs.resetExitProfile();
        reportWriter.writeExitLabReport(scope, rows);
    }
}
