package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BACKTEST-DESIGN §13 — 방식2·3 소급 검증 (K/필터 개념이 없는 단일 전략 재생).
 *
 * <p>{@link WalkForwardEngine#run}은 StrategyParameters.k만 스윕한다 — MA돌파·스캘핑은 이
 * 파라미터를 쓰지 않으므로(VB는 호출 전에 꺼져 있음) 단일값 List.of(0.5)로 호출해
 * 의미 없는 3배 반복을 피한다.
 */
@Component
@Profile("backtest")
public class SingleStrategyLab {

    private static final Logger log = LoggerFactory.getLogger(SingleStrategyLab.class);

    private final WalkForwardEngine walkForwardEngine;
    private final BacktestReportWriter reportWriter;
    private final StrategyToggles toggles;
    private final CandleCoverageChecker coverageChecker;
    private final BacktestDataProperties properties;

    public SingleStrategyLab(WalkForwardEngine walkForwardEngine,
                             BacktestReportWriter reportWriter,
                             StrategyToggles toggles,
                             CandleCoverageChecker coverageChecker,
                             BacktestDataProperties properties) {
        this.walkForwardEngine = walkForwardEngine;
        this.reportWriter = reportWriter;
        this.toggles = toggles;
        this.coverageChecker = coverageChecker;
        this.properties = properties;
    }

    /**
     * 채점 전 커버리지 검사 — ma-breakout·scalping은 후보 랩 라우터를 지나지 않아 관문이
     * 빠져 있었다(§14.5). 창이 부동(rangeTo)이라 백필 슬랙만큼 꼬리가 밀릴 수 있다.
     */
    private void verifyCoverage(LabScope scope) {
        coverageChecker.verify(scope.slug(), scope.symbols(), scope.to(),
                properties.isWriteBaseline());
    }

    /** 단일 변형 실행(MA돌파 등) — variants는 있으면 "변형 비교" 섹션에 채워진다 */
    public void runSingle(LabScope scope, Runnable enableOnly,
                          List<BacktestReportWriter.FilterVariant> variants) {
        verifyCoverage(scope);
        enableOnly.run();
        WalkForwardEngine.WalkForwardResult result = walkForward(scope);
        writeSingleStrategyReport(scope, result, variants);
    }

    /** 스캘핑(방식3) — 자체 파라미터 4종(windowSize/pullbackPct/reboundPct/takeProfitPct) ±20% 민감도 포함 */
    public void runScalping(List<String> symbols, LocalDate from, LocalDate to) {
        LabScope scope = new LabScope(
                "눌림목 반등 스캘핑(SCALPING_MOMENTUM)", "SCALPING", symbols, from, to);
        verifyCoverage(scope);
        resetScalpingDefaults();
        WalkForwardEngine.WalkForwardResult baseline = walkForward(scope);

        List<BacktestReportWriter.FilterVariant> variants = new ArrayList<>();
        variants.add(scalpingVariant("windowSize -20% (8틱)", baseline, scope,
                () -> toggles.scalping().setWindowSize(8)));
        variants.add(scalpingVariant("windowSize +20% (12틱)", baseline, scope,
                () -> toggles.scalping().setWindowSize(12)));
        variants.add(scalpingVariant("pullbackPct -20% (0.4%)", baseline, scope,
                () -> toggles.scalping().setPullbackPct(0.004)));
        variants.add(scalpingVariant("pullbackPct +20% (0.6%)", baseline, scope,
                () -> toggles.scalping().setPullbackPct(0.006)));
        variants.add(scalpingVariant("reboundPct -20% (0.24%)", baseline, scope,
                () -> toggles.scalping().setReboundPct(0.0024)));
        variants.add(scalpingVariant("reboundPct +20% (0.36%)", baseline, scope,
                () -> toggles.scalping().setReboundPct(0.0036)));
        variants.add(scalpingVariant("takeProfitPct -20% (0.64%)", baseline, scope,
                () -> toggles.scalping().setTakeProfitPct(0.0064)));
        variants.add(scalpingVariant("takeProfitPct +20% (0.96%)", baseline, scope,
                () -> toggles.scalping().setTakeProfitPct(0.0096)));
        resetScalpingDefaults();

        writeSingleStrategyReport(scope, baseline, variants);
    }

    private BacktestReportWriter.FilterVariant scalpingVariant(
            String name, WalkForwardEngine.WalkForwardResult baseline,
            LabScope scope, Runnable perturb) {
        resetScalpingDefaults();
        perturb.run();
        WalkForwardEngine.WalkForwardResult result = walkForward(scope);
        resetScalpingDefaults();

        BacktestMetrics base = baseline.aggregateValidation();
        BacktestMetrics var = result.aggregateValidation();
        boolean adopted = var.profitFactor() > base.profitFactor()
                && var.maxDrawdown() < base.maxDrawdown();
        String note = adopted ? "기준 대비 PF·MDD 동반 개선" : "기준 대비 우위 아님 (민감도 참고용)";
        log.info("[Orchestrator] 스캘핑 변형 {}: {} → {}", name, var.summaryLine(), note);
        return new BacktestReportWriter.FilterVariant(name, result, adopted, note);
    }

    private void resetScalpingDefaults() {
        toggles.scalping().setWindowSize(10);
        toggles.scalping().setPullbackPct(0.005);
        toggles.scalping().setReboundPct(0.003);
        toggles.scalping().setTakeProfitPct(0.008);
    }

    private WalkForwardEngine.WalkForwardResult walkForward(LabScope scope) {
        return walkForwardEngine.run(
                scope.symbols(), scope.from(), scope.to(), LabExecutor.FIXED_K, null);
    }

    private void writeSingleStrategyReport(LabScope scope,
                                           WalkForwardEngine.WalkForwardResult result,
                                           List<BacktestReportWriter.FilterVariant> variants) {
        BacktestReportWriter.ReportData data = new BacktestReportWriter.ReportData(
                scope.symbols(), scope.from(), scope.to(), Map.of(), result, variants,
                scope.label(), scope.slug());
        BacktestReportWriter.Judgment judgment = reportWriter.judge(data);
        reportWriter.writeReport(data, judgment);
        if (judgment.pass()) {
            reportWriter.writeBaseline(data, variants.stream()
                    .filter(BacktestReportWriter.FilterVariant::adopted).toList());
        } else {
            log.warn("[Orchestrator] {} 불합격 — 기준선 파일을 쓰지 않는다: {}",
                    scope.label(), judgment.reasons());
        }
    }
}
