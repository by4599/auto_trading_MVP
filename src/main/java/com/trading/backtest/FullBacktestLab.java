package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B-3 full 모드 (설계 문서 §3) — VB 기준 전체 검증.
 *
 * <p>① K 민감도(전기간, 참고) → ② Walk-Forward 기준선(필터 OFF)
 * → ③ 필터 A/B (기준선의 윈도우별 선택 K 재사용 — 필터는 튜닝 파라미터가 아니므로
 * 재학습 없이 검증 구간만 재생, 조합 폭발 방지) → ④ 리포트 + 합격 시 기준선 파일.
 */
@Component
@Profile("backtest")
public class FullBacktestLab {

    private static final Logger log = LoggerFactory.getLogger(FullBacktestLab.class);

    private static final List<Double> K_CANDIDATES = List.of(0.4, 0.5, 0.6);

    /** 필터 A/B 1회분의 고정 조건 — 기준선 결과와 그 기준선이 고른 K를 그대로 재사용한다 */
    private record VariantContext(WalkForwardEngine.WalkForwardResult baseline,
                                  List<String> symbols, LocalDate from, LocalDate to,
                                  List<Double> chosenKs) {}

    private final BacktestRunner runner;
    private final WalkForwardEngine walkForwardEngine;
    private final BacktestReportWriter reportWriter;
    private final StrategyToggles toggles;
    private final ExecutionKnobs knobs;
    private final CandleCoverageChecker coverageChecker;
    private final BacktestDataProperties properties;

    public FullBacktestLab(BacktestRunner runner,
                           WalkForwardEngine walkForwardEngine,
                           BacktestReportWriter reportWriter,
                           StrategyToggles toggles,
                           ExecutionKnobs knobs,
                           CandleCoverageChecker coverageChecker,
                           BacktestDataProperties properties) {
        this.runner = runner;
        this.walkForwardEngine = walkForwardEngine;
        this.reportWriter = reportWriter;
        this.toggles = toggles;
        this.knobs = knobs;
        this.coverageChecker = coverageChecker;
        this.properties = properties;
    }

    public void run(List<String> symbols, LocalDate from, LocalDate to) {
        // 채점 전 커버리지 검사 — full 모드는 후보 랩 라우터를 지나지 않아 관문이 빠져 있었다
        // (§14.5). 창이 부동(rangeTo)이라 백필 슬랙만큼 꼬리가 밀릴 수 있다.
        coverageChecker.verify("full", symbols, to, properties.isWriteBaseline());

        // VB(방식1) 기준 실행 — 방식2·3이 같이 켜져 신호가 섞이지 않도록 명시적으로 끈다
        toggles.enableVbOnly();

        Map<Double, BacktestMetrics> kSensitivity = runKSensitivity(symbols, from, to);

        // ② Walk-Forward 기준선 (필터 전부 OFF)
        knobs.allFiltersOff();
        WalkForwardEngine.WalkForwardResult baseline =
                walkForwardEngine.run(symbols, from, to, K_CANDIDATES, null);
        VariantContext ctx = new VariantContext(
                baseline, symbols, from, to, baseline.chosenKs());

        List<BacktestReportWriter.FilterVariant> variants = runFilterVariants(ctx);
        knobs.allFiltersOff();

        writeReport(new BacktestReportWriter.ReportData(
                symbols, from, to, kSensitivity, baseline, variants));
    }

    /** ① K 민감도 — 전기간 (참고용, §4 민감도 검사의 분모) */
    private Map<Double, BacktestMetrics> runKSensitivity(List<String> symbols,
                                                         LocalDate from, LocalDate to) {
        Map<Double, BacktestMetrics> kSensitivity = new LinkedHashMap<>();
        for (double k : K_CANDIDATES) {
            toggles.setK(k);
            BacktestRunner.RunResult r = runner.run(new BacktestRunner.RunConfig(
                    String.format("FULL-K%.1f", k), symbols, from, to));
            kSensitivity.put(k, r.metrics());
        }
        toggles.setK(0.5);
        return kSensitivity;
    }

    /** ③ 필터 A/B — 기준선 선택 K 재사용, 검증 구간만 재생. §3.3: 조합 탐색은 상위 2개까지만 */
    private List<BacktestReportWriter.FilterVariant> runFilterVariants(VariantContext ctx) {
        List<BacktestReportWriter.FilterVariant> variants = new ArrayList<>();
        variants.add(runFilterVariant("트레일링 스톱 1% (+3% 후)", ctx, () -> {
            knobs.filters().getTrailingStop().setEnabled(true);
            knobs.filters().getTrailingStop().setTrailPct(0.01);
        }));
        variants.add(runFilterVariant("트레일링 스톱 2% (+3% 후)", ctx, () -> {
            knobs.filters().getTrailingStop().setEnabled(true);
            knobs.filters().getTrailingStop().setTrailPct(0.02);
        }));
        variants.add(runFilterVariant("지수 레짐 (KOSPI 갭다운 진입 금지)", ctx,
                () -> knobs.filters().getIndexRegime().setEnabled(true)));
        variants.add(runFilterVariant("공시 쿨다운 5일 (이벤트성 공시 후 진입 금지)", ctx, () -> {
            knobs.filters().getDisclosureCooldown().setEnabled(true);
            knobs.filters().getDisclosureCooldown().setCooldownDays(5);
        }));
        variants.add(runFilterVariant("공시 쿨다운 3일", ctx, () -> {
            knobs.filters().getDisclosureCooldown().setEnabled(true);
            knobs.filters().getDisclosureCooldown().setCooldownDays(3);
        }));
        variants.add(runFilterVariant("조합: 트레일링 1% + 공시 쿨다운 5일", ctx, () -> {
            knobs.filters().getTrailingStop().setEnabled(true);
            knobs.filters().getTrailingStop().setTrailPct(0.01);
            knobs.filters().getDisclosureCooldown().setEnabled(true);
            knobs.filters().getDisclosureCooldown().setCooldownDays(5);
        }));
        return variants;
    }

    private BacktestReportWriter.FilterVariant runFilterVariant(String name, VariantContext ctx,
                                                                Runnable enable) {
        knobs.allFiltersOff();
        enable.run();
        WalkForwardEngine.WalkForwardResult result = walkForwardEngine.run(
                ctx.symbols(), ctx.from(), ctx.to(), K_CANDIDATES, ctx.chosenKs());

        BacktestMetrics base = ctx.baseline().aggregateValidation();
        BacktestMetrics var = result.aggregateValidation();
        boolean adopted = var.profitFactor() > base.profitFactor()
                && var.maxDrawdown() < base.maxDrawdown();
        String note = adopted ? "✅ 채택 후보 (PF·MDD 동반 우위)"
                : "보류 (동반 우위 아님 — §3.3)";
        log.info("[Orchestrator] 필터 A/B {}: {} → {}", name, var.summaryLine(), note);
        return new BacktestReportWriter.FilterVariant(name, result, adopted, note);
    }

    /** ④ 리포트 + 기준선 */
    private void writeReport(BacktestReportWriter.ReportData data) {
        BacktestReportWriter.Judgment judgment = reportWriter.judge(data);
        reportWriter.writeReport(data, judgment);

        if (judgment.pass()) {
            List<BacktestReportWriter.FilterVariant> adopted = data.filterVariants().stream()
                    .filter(BacktestReportWriter.FilterVariant::adopted).toList();
            reportWriter.writeBaseline(data, adopted);
        } else {
            log.warn("[Orchestrator] 불합격 — 기준선 파일을 쓰지 않는다: {}", judgment.reasons());
        }
    }
}
