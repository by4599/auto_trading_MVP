package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * B동(당일 단타) 진입 조건 A/B (ADR-001 개정 결정 2).
 *
 * <p>진입=VB, 출구·사이징은 <b>지금 모의투자가 쓰는 값</b>으로 고정한다 — 이 실험의 질문은
 * "출구를 바꾸면 나아지나"(그건 §14에서 이미 답함)가 아니라 "같은 방식으로 돌리되 살 종목을
 * 더 가려내면 나아지나"이기 때문이다. B0(필터 OFF)는 현행 B동 자체의 약세장 성적이다.
 */
@Component
@Profile("backtest")
public class VbFilterLab {

    private static final Logger log = LoggerFactory.getLogger(VbFilterLab.class);

    private record VbFilterProfile(String name, boolean indexTrend, int maPeriod, boolean volumeConfirm) {}

    private static final List<VbFilterProfile> VB_FILTER_PROFILES = List.of(
            new VbFilterProfile("B0 필터OFF (현행 B동)",      false, 120, false),
            new VbFilterProfile("B1 지수 MA120 이탈 시 금지",  true,  120, false),
            new VbFilterProfile("B2 거래량 확인",              false, 120, true),
            new VbFilterProfile("B3 지수MA120 + 거래량",       true,  120, true));

    private static final String VB_LAB_LABEL = "VB 진입 + 현행 당일 출구(ATR1.5·타임컷·트레일1%) + 1.0R";
    private static final String VB_LAB_SLUG  = "VB-DAYTRADE-FILTERS";

    /**
     * B동 비용 하향 스윕 — VB의 마이너스가 "기법이 틀려서"인지 "마찰이 먹어서"인지 가른다.
     *
     * <p>슬리피지만 낮출 수 있다(수수료·매도 제세는 법정 확정값). 0.21%가 물리적 바닥이며,
     * 지수선물은 매도 제세가 없어 왕복 0.02% 수준 — <b>이 모델로는 표현조차 안 되는 영역</b>이다.
     */
    private static final List<Double> VB_COST_SWEEP = List.of(0.0041, 0.0031, 0.0025, 0.0021);

    private final LabExecutor executor;
    private final LabComparisonReportWriter reportWriter;
    private final ExecutionKnobs knobs;
    private final StrategyToggles toggles;

    public VbFilterLab(LabExecutor executor, LabComparisonReportWriter reportWriter,
                       ExecutionKnobs knobs, StrategyToggles toggles) {
        this.executor = executor;
        this.reportWriter = reportWriter;
        this.knobs = knobs;
        this.toggles = toggles;
    }

    /** 진입 필터 A/B — 출구·사이징은 현행 paper 값으로 고정하고 진입 필터만 스윕 */
    public void runFilterLab(List<String> symbols, LocalDate from, LocalDate to) {
        toggles.enableVbOnlyResetAll();
        knobs.resetSizing();

        LabScope scope = new LabScope(VB_LAB_LABEL, VB_LAB_SLUG, symbols, from, to);
        int windowCount = LabExecutor.windowCount(scope);
        log.info("[VbFilterLab] ══ B동 진입 조건 A/B (약세장 창, ADR-001 개정 결정 2) ══");
        log.info("[VbFilterLab] 고정: {} · 기간 {}~{} · 윈도우 {}개 · 종목 {}개",
                VB_LAB_LABEL, from, to, windowCount, symbols.size());
        log.info("[VbFilterLab] B0는 현행 B동의 약세장 성적 그 자체 — 이긴 필터가 없으면 존치를 재검토한다");

        List<ExitLabRow> rows = new ArrayList<>();
        for (VbFilterProfile p : VB_FILTER_PROFILES) {
            // 현행 paper 출구: ATR1.5 · 15:15 타임컷 ON · 다일 보유 없음 · 트레일 arm3%/trail1%
            knobs.applyExitProfile(ExitProfile.B_DONG_CURRENT);
            knobs.applyIndexTrend(p.indexTrend(), p.maPeriod());
            knobs.filters().getVolumeConfirm().setEnabled(p.volumeConfirm());

            ExitLabRow row = executor.evaluate(p.name(), scope);
            log.info("[VbFilterLab] {}: {} → {}", p.name(),
                    row.result().aggregateValidation().summaryLine(), ReportFormat.verdict(row.judgment()));
            rows.add(row);
        }
        knobs.restoreRegimeDefaults();
        knobs.filters().getVolumeConfirm().setEnabled(false);

        Path report = reportWriter.writeRiskLabReport(scope, rows, false);
        log.info("[VbFilterLab] 리포트: {}", report.toAbsolutePath());
    }

    /** 비용 하향 스윕 — 기대값의 부호가 뒤집히는 지점을 찾는다 */
    public void runCostLab(List<String> symbols, LocalDate from, LocalDate to) {
        toggles.enableVbOnlyResetAll();
        knobs.resetSizing();

        LabScope scope = new LabScope(VB_LAB_LABEL, VB_LAB_SLUG, symbols, from, to);
        log.info("[VbCostLab] ══ B동 왕복 비용 하향 스윕 (약세장 24창, 종목 {}개) ══", symbols.size());
        log.info("[VbCostLab] 고정: {} · 법정 바닥 0.21%(수수료 0.03 + 매도 제세 0.18)", VB_LAB_LABEL);

        List<ExitLabRow> rows = new ArrayList<>();
        for (double cost : VB_COST_SWEEP) {
            knobs.applyExitProfile(ExitProfile.B_DONG_CURRENT);
            knobs.setRoundTripCost(cost);
            AppliedCost applied = knobs.appliedCost();

            String name = String.format("왕복 %s (슬리피지 편도 %s)",
                    ReportFormat.pct(cost), ReportFormat.pct(applied.slippageRate()));
            ExitLabRow row = executor.evaluate(name, scope, applied);
            log.info("[VbCostLab] {}: {} → {}", name,
                    row.result().aggregateValidation().summaryLine(), ReportFormat.verdict(row.judgment()));
            rows.add(row);
        }
        knobs.resetCostDefaults();
        knobs.restoreRegimeDefaults();

        Path report = reportWriter.writeCostLabReport(
                scope.withNames(VB_LAB_LABEL + " · 비용 하향", VB_LAB_SLUG + "-COSTDOWN"), rows);
        log.info("[VbCostLab] 리포트: {}", report.toAbsolutePath());
    }
}
