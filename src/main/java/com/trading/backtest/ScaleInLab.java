package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scale-In Lab (2026-11-12, 사용자 요청 연구) — "계좌 100% 활용 + 3번 나눠 사기"가 A동(돈치안 + P3)의
 * §4 판정을 통과하는가.
 *
 * <p>고정: 진입=돈치안(고가20·추세120) / 출구=P3(ATR1.0 손절·+1% 후 3% 트레일·최대 20거래일) / 동시 최대 5 /
 * 왕복 0.41% / 약세장 6.5년 창(24창). 바뀌는 것: 사이징(금액 비중 20%×5) · 분할 여부 · 지수 필터 처리.
 * 격자는 {@link ScaleInProfile#mainGrid()}에 결과 전에 고정했다.
 */
@Component
@Profile("backtest")
public class ScaleInLab {

    private static final Logger log = LoggerFactory.getLogger(ScaleInLab.class);

    static final ScaleInProfile ANCHOR_SZ0 = ScaleInProfile.anchor("SZ0", "앵커 SZ0 0.5R·동시5·MA120", 0.005);
    static final ScaleInProfile ANCHOR_SZ2 = ScaleInProfile.anchor("SZ2", "앵커 SZ2 0.25R·동시5·MA120", 0.0025);

    static final double COST_PLUS = 0.0050;  // 왕복 +0.09%p
    static final double COST_HIGH = 0.0080;

    private final LabExecutor executor;
    private final ExecutionKnobs knobs;
    private final StrategyToggles toggles;
    private final ScaleInProperties scaleIn;
    private final ScaleInRunStats stats;
    private final ScaleInReportWriter reportWriter;
    private final BacktestStopRules stopRules;

    public ScaleInLab(LabExecutor executor, ExecutionKnobs knobs, StrategyToggles toggles,
                      ScaleInProperties scaleIn, ScaleInRunStats stats, ScaleInReportWriter reportWriter,
                      BacktestStopRules stopRules) {
        this.executor = executor;
        this.knobs = knobs;
        this.toggles = toggles;
        this.scaleIn = scaleIn;
        this.stats = stats;
        this.reportWriter = reportWriter;
        this.stopRules = stopRules;
    }

    /** 회귀 앵커만 — 현재 엔진(손절선 장착 수정 후)의 SZ0·SZ2 */
    public Path runAnchors(LabScope scope) {
        return runAll(scope.withNames(scope.label() + " · 회귀 앵커", scope.slug() + "-ANCHOR"),
                List.of(ANCHOR_SZ0, ANCHOR_SZ2), "앵커");
    }

    /** 본 격자 15칸 + 비교용 SZ2 1행 */
    public Path runMain(LabScope scope) {
        List<ScaleInProfile> profiles = new ArrayList<>();
        profiles.add(ANCHOR_SZ2);
        profiles.addAll(ScaleInProfile.mainGrid());
        return runAll(scope, profiles, "본 격자");
    }

    /** 합격권 프로필의 파라미터 ±20% · 왕복 비용 민감도 */
    public Path runSensitivity(LabScope scope, ScaleInProfile base) {
        List<ScaleInProfile> profiles = sensitivityGrid(base);
        return runAll(scope.withNames(scope.label() + " · 민감도 " + base.code(),
                scope.slug() + "-SENS-" + base.code()), profiles, "민감도");
    }

    static List<ScaleInProfile> sensitivityGrid(ScaleInProfile base) {
        String c = base.code();
        List<ScaleInProfile> grid = new ArrayList<>();
        grid.add(base.renamed(c + "-base", base.name() + " (본 실행 재현)"));
        grid.add(base.withTargetWeight(0.16).renamed(c + "-W16", "종목 비중 16% (−20%)"));
        grid.add(base.withTargetWeight(0.24).renamed(c + "-W24", "종목 비중 24% (+20%)"));
        if (base.tranches() >= 2) {
            grid.add(base.withStepAtr(base.stepAtr() * 0.8).renamed(c + "-Km", "k ×0.8 (−20%)"));
            grid.add(base.withStepAtr(base.stepAtr() * 1.2).renamed(c + "-Kp", "k ×1.2 (+20%)"));
        }
        grid.add(base.withDonchian(16, 120).renamed(c + "-L16", "돈치안 고가16 (−20%)"));
        grid.add(base.withDonchian(24, 120).renamed(c + "-L24", "돈치안 고가24 (+20%)"));
        grid.add(base.withDonchian(20, 96).renamed(c + "-T96", "종목 추세96 (−20%)"));
        grid.add(base.withDonchian(20, 144).renamed(c + "-T144", "종목 추세144 (+20%)"));
        grid.add(base.withRoundTripCost(COST_PLUS).renamed(c + "-C050", "왕복 비용 0.50% (+0.09%p)"));
        grid.add(base.withRoundTripCost(COST_HIGH).renamed(c + "-C080", "왕복 비용 0.80%"));
        return grid;
    }

    private Path runAll(LabScope scope, List<ScaleInProfile> profiles, String phase) {
        log.info("[ScaleIn] ══ {} — {} · {}~{} · 종목 {}개 · 창 {}개 · 프로필 {}개 ══", phase, scope.slug(),
                scope.from(), scope.to(), scope.symbols().size(), LabExecutor.windowCount(scope), profiles.size());
        List<ScaleInReportWriter.Row> rows = new ArrayList<>();
        try {
            for (ScaleInProfile p : profiles) {
                rows.add(evaluate(p, scope));
            }
        } finally {
            restoreDefaults();
        }
        Path report = reportWriter.write(scope, rows);
        log.info("[ScaleIn] 리포트: {}", report.toAbsolutePath());
        return report;
    }

    private ScaleInReportWriter.Row evaluate(ScaleInProfile p, LabScope scope) {
        apply(p);
        stats.clear();
        long start = System.nanoTime();
        AppliedCost applied = p.roundTripCost() > 0 ? knobs.appliedCost() : null;
        ExitLabRow row = executor.evaluate(p.name(), scope, applied);
        ScaleInReportWriter.Row result = new ScaleInReportWriter.Row(
                p.code(), p.name(), row, stats.averageCashRatio(), stats.counters());
        log.info("[ScaleIn] {} ({}초): {} · 현금비중 {} → {}", p.code(), ReportFormat.elapsedSec(start),
                row.result().aggregateValidation().summaryLine(),
                String.format("%.1f%%", result.cashRatio() * 100), ReportFormat.verdict(row.judgment()));
        return result;
    }

    /** 프로필 1칸 세팅 — 매번 기본값에서 출발해 앞 프로필의 잔재가 남지 않게 한다 */
    void apply(ScaleInProfile p) {
        toggles.enableDonchianOnlyResetRsi();
        knobs.applyP3ExitWithHalfRisk();
        knobs.applySizing(p.riskFraction(), 5);
        toggles.donchian().setLookback(p.lookback());
        toggles.donchian().setTrendMaPeriod(p.trendMa());
        knobs.applyIndexTrend(p.indexMode() == ScaleInProfile.IndexMode.A_MA120, 120);
        knobs.resetCostDefaults();
        if (p.roundTripCost() > 0) knobs.setRoundTripCost(p.roundTripCost());

        stopRules.resetDefaults();
        scaleIn.resetDefaults();
        scaleIn.setEnabled(p.scaleIn());
        scaleIn.setTargetWeight(p.targetWeight());
        scaleIn.setTranches(p.tranches());
        scaleIn.setStepAtr(p.stepAtr());
        scaleIn.setStopBasis(p.stopBasis());
        scaleIn.setIndexGate(p.indexMode() == ScaleInProfile.IndexMode.C_FIRST_ONLY
                ? ScaleInProperties.IndexGate.FIRST_TRANCHE_ONLY : ScaleInProperties.IndexGate.RULE);
        scaleIn.setIndexMaPeriod(120);
    }

    private void restoreDefaults() {
        stopRules.resetDefaults();
        scaleIn.resetDefaults();
        toggles.donchian().setLookback(20);
        toggles.donchian().setTrendMaPeriod(120);
        knobs.resetCostDefaults();
        knobs.restoreRegimeDefaults();
    }
}
