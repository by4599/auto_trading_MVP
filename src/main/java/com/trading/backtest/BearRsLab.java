package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Bear RS Lab (55_quant, 2026-11-17) — 하락장 상대강도 예외의 사전 등록 격자를 지금 paper A동 설정(D0′)에서 돌린다.
 *
 * <p>D0′ = 돈치안(고가20·추세120) · 진입가 −15% 고정 손절 · 수량 ATR×1.0 기준 0.25R · 고가 종목 1주 매수(1주 리스크 ≤ 1R×3) ·
 * 트레일 +1%/3% · 최대 20일 · 동시5 · 지수 MA120 · 기본 비용(왕복 0.43%) — {@code stop-size-cap-lab}의 상한 3배 칸과 같은 구성이다.
 * 진입일 손절 판정 가정(당일 S / 익일 X)은 {@link StopWidthProfile} F15-S/F15-X로 받는다.
 */
@Component
@Profile("backtest")
public class BearRsLab {

    private static final Logger log = LoggerFactory.getLogger(BearRsLab.class);

    static final double RISK_FRACTION = 0.0025;
    static final double MIN_ONE_SHARE_CAP = 3.0;

    private final StopWidthLab stopWidthLab;
    private final LabExecutor executor;
    private final ExecutionKnobs knobs;
    private final ScaleInRunStats stats;
    private final ScaleInReportWriter reportWriter;
    private final BearRsGate gate;

    public BearRsLab(StopWidthLab stopWidthLab, LabExecutor executor, ExecutionKnobs knobs,
                     ScaleInRunStats stats, ScaleInReportWriter reportWriter, BearRsGate gate) {
        this.stopWidthLab = stopWidthLab;
        this.executor = executor;
        this.knobs = knobs;
        this.stats = stats;
        this.reportWriter = reportWriter;
        this.gate = gate;
    }

    public Path run(LabScope scope, StopWidthProfile exit, List<BearRsProfile> profiles) {
        log.info("[BearRS] ══ {} · 출구 {} · {}~{} · 종목 {}개 · 창 {}개 · 프로필 {}개 ══", scope.slug(), exit.code(),
                scope.from(), scope.to(), scope.symbols().size(), LabExecutor.windowCount(scope), profiles.size());
        List<ScaleInReportWriter.Row> rows = new ArrayList<>();
        List<BearRsTradeStats.Summary> bear = new ArrayList<>();
        try {
            for (BearRsProfile p : profiles) {
                ScaleInReportWriter.Row row = evaluate(p, exit, scope);
                rows.add(row);
                bear.add(BearRsTradeStats.summarize(p.code(), row.lab().result().windows(), gate::isBearDay));
            }
        } finally {
            gate.disable();
            stopWidthLab.restoreDefaults();
            stopWidthLab.useSizing(StopWidthLab.SZ2_RISK_FRACTION, false);
        }
        Path report = reportWriter.write("BEARRS", "Bear RS Lab (55_quant)", scope, rows);
        BearRsTradeStats.append(report, bear);
        log.info("[BearRS] 리포트: {}", report.toAbsolutePath());
        return report;
    }

    private ScaleInReportWriter.Row evaluate(BearRsProfile p, StopWidthProfile exit, LabScope scope) {
        stopWidthLab.useSizing(RISK_FRACTION, true, MIN_ONE_SHARE_CAP);
        stopWidthLab.apply(exit);                       // D0′ 전부 — 지수 MA120 ON 포함
        knobs.applyIndexTrend(p.indexFilter(), 120);    // 예외·NOF 칸만 지수 필터를 끈다
        if (p.gate() == null) gate.disable();
        else gate.configure(p.gate(), scope.symbols());
        stats.clear();
        long start = System.nanoTime();
        AppliedCost applied = exit.roundTripCost() > 0 ? knobs.appliedCost() : null;
        ExitLabRow row = executor.evaluate(p.name(), scope, applied);
        log.info("[BearRS] {}-{} ({}초): {} → {} · 사건 {}", p.code(), exit.code(), ReportFormat.elapsedSec(start),
                row.result().aggregateValidation().summaryLine(), ReportFormat.verdict(row.judgment()), stats.counters());
        return new ScaleInReportWriter.Row(p.code(), p.name(), row, stats.averageCashRatio(), stats.counters());
    }
}
