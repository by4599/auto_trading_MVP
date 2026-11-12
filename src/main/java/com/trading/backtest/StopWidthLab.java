package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stop Width Lab (56_quant, 2026-11-13) — 손절선 장착 결함을 고친 엔진에서 A동(돈치안 + 트레일 + 최대 20일 · 0.25R · 동시5 ·
 * 지수 MA120)의 ATR 손절 폭을 스윕한다. 격자는 {@link StopWidthProfile#grid()}에 결과 전에 고정했다.
 */
@Component
@Profile("backtest")
public class StopWidthLab {

    private static final Logger log = LoggerFactory.getLogger(StopWidthLab.class);

    static final double SZ2_RISK_FRACTION = 0.0025;

    private final LabExecutor executor;
    private final ExecutionKnobs knobs;
    private final StrategyToggles toggles;
    private final BacktestStopRules stopRules;
    private final ScaleInRunStats stats;
    private final ScaleInReportWriter reportWriter;

    public StopWidthLab(LabExecutor executor, ExecutionKnobs knobs, StrategyToggles toggles,
                        BacktestStopRules stopRules, ScaleInRunStats stats, ScaleInReportWriter reportWriter) {
        this.executor = executor;
        this.knobs = knobs;
        this.toggles = toggles;
        this.stopRules = stopRules;
        this.stats = stats;
        this.reportWriter = reportWriter;
    }

    public Path run(LabScope scope, List<StopWidthProfile> profiles, String phase) {
        log.info("[StopWidth] ══ {} — {} · {}~{} · 종목 {}개 · 창 {}개 · 프로필 {}개 ══", phase, scope.slug(),
                scope.from(), scope.to(), scope.symbols().size(), LabExecutor.windowCount(scope), profiles.size());
        List<ScaleInReportWriter.Row> rows = new ArrayList<>();
        try {
            for (StopWidthProfile p : profiles) {
                rows.add(evaluate(p, scope));
            }
        } finally {
            restoreDefaults();
        }
        Path report = reportWriter.write("STOPWIDTH", "Stop Width Lab", scope, rows);
        log.info("[StopWidth] 리포트: {}", report.toAbsolutePath());
        return report;
    }

    private ScaleInReportWriter.Row evaluate(StopWidthProfile p, LabScope scope) {
        apply(p);
        stats.clear();
        long start = System.nanoTime();
        AppliedCost applied = p.roundTripCost() > 0 ? knobs.appliedCost() : null;
        ExitLabRow row = executor.evaluate(p.name(), scope, applied);
        log.info("[StopWidth] {} ({}초): {} → {}", p.code(), ReportFormat.elapsedSec(start),
                row.result().aggregateValidation().summaryLine(), ReportFormat.verdict(row.judgment()));
        return new ScaleInReportWriter.Row(p.code(), p.name(), row, stats.averageCashRatio(), stats.counters());
    }

    /** 매 칸 기본값에서 출발 — 앞 칸의 잔재가 남지 않게 */
    void apply(StopWidthProfile p) {
        toggles.enableDonchianOnlyResetRsi();
        knobs.applyP3ExitWithHalfRisk();                  // 트레일 +1%/3% · 최대 20일 · ATR 배수 1.0
        knobs.applySizing(SZ2_RISK_FRACTION, 5);
        knobs.applyIndexTrend(true, 120);
        knobs.setAtrStopMultiplier(p.sizingMult());       // 수량(그리고 1차 장착 손절선)의 배수
        toggles.donchian().setLookback(p.lookback());
        toggles.donchian().setTrendMaPeriod(p.trendMa());
        knobs.resetCostDefaults();
        if (p.roundTripCost() > 0) knobs.setRoundTripCost(p.roundTripCost());
        stopRules.resetDefaults();
        stopRules.setEntryDayStop(p.entryDay());
        stopRules.setStopMultiplier(p.stopMult());        // NaN = 사이징 배수 그대로, 0 = 손절 없음
    }

    private void restoreDefaults() {
        stopRules.resetDefaults();
        toggles.donchian().setLookback(20);
        toggles.donchian().setTrendMaPeriod(120);
        knobs.resetCostDefaults();
        knobs.restoreRegimeDefaults();
    }
}
