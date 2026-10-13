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
 * 돈치안 후보 강건성 랩 (2026-08, §15.5 후속) — 약세장 관문을 통과한 돈치안(+지수 MA120 필터)을
 * 고정하고 파라미터·사이징·추세기간·비용 축을 하나씩 흔든다.
 *
 * <p>공통 고정: 진입=Donchian / 출구=P3 / 사이징=지정값 / 지수 추세 MA120 필터 ON, 창은 약세장(stress).
 * 한 축만 뾰족하거나 비용에 급락하면 실전 후보 자격이 약해진다.
 */
@Component
@Profile("backtest")
public class DonchianLab {

    private static final Logger log = LoggerFactory.getLogger(DonchianLab.class);

    private record DonchianParamProfile(String name, int lookback, int trendMa) {}

    private static final List<DonchianParamProfile> DONCHIAN_PARAM_PROFILES = List.of(
            new DonchianParamProfile("D0 기준(고가20·추세120)", 20, 120),
            new DonchianParamProfile("D1 고가16 (-20%)",        16, 120),
            new DonchianParamProfile("D2 고가24 (+20%)",        24, 120),
            new DonchianParamProfile("D3 추세96 (-20%)",        20, 96),
            new DonchianParamProfile("D4 추세144 (+20%)",       20, 144));

    private static final String DONCHIAN_LABEL = "돈치안 돌파 + P3 + RR1 + 지수MA120";
    private static final String DONCHIAN_SLUG  = "DONCHIAN-P3-RR1-IDX120";

    /**
     * 민감도·비용 랩이 쓰는 사이징 고정값. §15.6에서 0.5R의 MDD 잣대가 경로 의존 노이즈로
     * 드러나(추세 기간을 흔들면 10.6~24.5% 요동) 분포를 한도에서 떼어놓은 SZ2로 재시험한다.
     */
    record DonchianSizing(String label, String slug, double riskFraction, int maxPositions) {}

    static final DonchianSizing SIZING_RR1 =
            new DonchianSizing(DONCHIAN_LABEL, DONCHIAN_SLUG, 0.005, 5);
    static final DonchianSizing SIZING_SZ2 =
            new DonchianSizing("돈치안 돌파 + P3 + SZ2(0.25R·동시5) + 지수MA120",
                    "DONCHIAN-P3-SZ2-IDX120", 0.0025, 5);

    /**
     * §15.5 후속 ① — 약세장 창 사이징 재스윕. 모든 불합격의 단일 원인이 MDD 한도 근접(여유
     * 0.6%p)이므로 1R 비율을 낮춰 여유를 벌 수 있는지 직접 확인한다. SZ0은 §15.2 G1
     * (1255건·PF 1.90·MDD 14.4%)을 재현해야 하는 회귀 앵커다.
     * ⚠ §15.1에서 사이징 효과가 비단조(슬롯 경합으로 체결 집합 변동)임이 관측됐다 — 기대는 낮게.
     */
    private record StressSizingProfile(String name, double riskFraction, int maxPositions) {}

    private static final List<StressSizingProfile> STRESS_SIZING_PROFILES = List.of(
            new StressSizingProfile("SZ0 0.5R·동시5 (G1 재현 앵커)", 0.005,  5),
            new StressSizingProfile("SZ1 0.35R·동시5",              0.0035, 5),
            new StressSizingProfile("SZ2 0.25R·동시5",              0.0025, 5),
            new StressSizingProfile("SZ3 0.35R·동시3",              0.0035, 3),
            new StressSizingProfile("SZ4 0.25R·동시3",              0.0025, 3));

    /**
     * §15.5 후속 ② — 종목 추세 기간 지도. §15.3에서 96은 붕괴(MDD 22.9%)·144가 최선(10.6%)이었다
     * — 144 주변이 평지인지 봉우리인지 본다. T0·T1·T2는 §15.3 D3·D0·D4의 재현 앵커.
     * 평지로 확인되기 전에는 기준값(120)을 바꾸지 않는다(커브 피팅 방지).
     */
    private static final List<Integer> TREND_MAP_PERIODS = List.of(96, 120, 144, 168, 192);

    private final LabExecutor executor;
    private final LabComparisonReportWriter reportWriter;
    private final ExecutionKnobs knobs;
    private final StrategyToggles toggles;

    public DonchianLab(LabExecutor executor, LabComparisonReportWriter reportWriter,
                       ExecutionKnobs knobs, StrategyToggles toggles) {
        this.executor = executor;
        this.reportWriter = reportWriter;
        this.knobs = knobs;
        this.toggles = toggles;
    }

    /** 공통 고정 — 진입=Donchian / 출구=P3 / 사이징=지정값 / 지수 추세 MA120 필터 ON */
    private void applyFixedWithIndexFilter(DonchianSizing sizing) {
        toggles.enableDonchianOnlyResetRsi();
        knobs.applyP3ExitWithHalfRisk();          // P3 출구 + 기본 사이징
        knobs.applySizing(sizing.riskFraction(), sizing.maxPositions());
        knobs.applyIndexTrend(true, 120);
    }

    /** Step 2 — 돈치안 자체 파라미터(고가기간·추세기간) ±20% 민감도 (약세장 창, 지수 MA120 ON) */
    public void runSensitivity(List<String> symbols, LocalDate from, LocalDate to,
                               DonchianSizing sizing) {
        applyFixedWithIndexFilter(sizing);
        LabScope scope = new LabScope(sizing.label(), sizing.slug(), symbols, from, to);
        int windowCount = LabExecutor.windowCount(scope);
        log.info("[DonchianSens] ══ 돈치안 파라미터 ±20% 민감도 (약세장 창, 지수 MA120 ON, {}) ══",
                sizing.slug());
        log.info("[DonchianSens] 기간 {}~{} · 종목 {}개 · Walk-Forward 윈도우 {}개 · 프로필 {}개",
                from, to, symbols.size(), windowCount, DONCHIAN_PARAM_PROFILES.size());

        List<ExitLabRow> rows = new ArrayList<>();
        for (DonchianParamProfile p : DONCHIAN_PARAM_PROFILES) {
            toggles.donchian().setLookback(p.lookback());
            toggles.donchian().setTrendMaPeriod(p.trendMa());
            ExitLabRow row = executor.evaluate(p.name(), scope);
            log.info("[DonchianSens] {}: {} → {}", p.name(),
                    row.result().aggregateValidation().summaryLine(), ReportFormat.verdict(row.judgment()));
            rows.add(row);
        }
        // 기본값 복원
        toggles.donchian().setLookback(20);
        toggles.donchian().setTrendMaPeriod(120);
        knobs.restoreRegimeDefaults();

        Path report = reportWriter.writeRiskLabReport(
                scope.withNames(sizing.label() + " · 파라미터 민감도", sizing.slug() + "-SENS"),
                rows, false);
        log.info("[DonchianSens] §4 민감도: D1~D4가 전부 완만(PF·MDD가 D0 근처)이면 강건. "
                + "한 칸만 좋고 인접이 무너지면 과최적화 지문");
        log.info("[DonchianSens] 리포트: {}", report.toAbsolutePath());
    }

    /** §15.5 후속 ① — 약세장 창 사이징 재스윕 (지수 MA120 ON) */
    public void runStressSizing(List<String> symbols, LocalDate from, LocalDate to) {
        applyFixedWithIndexFilter(SIZING_RR1);
        LabScope scope = new LabScope(DONCHIAN_LABEL, DONCHIAN_SLUG, symbols, from, to);
        log.info("[DonchianSizing] ══ 약세장 창 사이징 재스윕 (지수 MA120 ON, §15.5 후속①) ══");
        log.info("[DonchianSizing] 기간 {}~{} · 종목 {}개 · 프로필 {}개 — SZ0가 §15.2 G1을 재현 못 하면 무효",
                from, to, symbols.size(), STRESS_SIZING_PROFILES.size());

        List<ExitLabRow> rows = new ArrayList<>();
        for (StressSizingProfile p : STRESS_SIZING_PROFILES) {
            knobs.applySizing(p.riskFraction(), p.maxPositions());
            ExitLabRow row = executor.evaluate(p.name(), scope);
            log.info("[DonchianSizing] {}: {} → {}", p.name(),
                    row.result().aggregateValidation().summaryLine(), ReportFormat.verdict(row.judgment()));
            rows.add(row);
        }
        knobs.resetSizing();
        knobs.restoreRegimeDefaults();

        Path report = reportWriter.writeRiskLabReport(
                scope.withNames(DONCHIAN_LABEL + " · 약세장 사이징 재스윕", DONCHIAN_SLUG + "-SIZING"),
                rows, false);
        log.info("[DonchianSizing] 리포트: {}", report.toAbsolutePath());
    }

    /** §15.5 후속 ② — 종목 추세 기간 지도 (96~192, 지수 MA120 ON·RR1 고정) */
    public void runTrendMap(List<String> symbols, LocalDate from, LocalDate to) {
        applyFixedWithIndexFilter(SIZING_RR1);
        LabScope scope = new LabScope(DONCHIAN_LABEL, DONCHIAN_SLUG, symbols, from, to);
        log.info("[DonchianTrendMap] ══ 종목 추세 기간 지도 (지수 MA120 ON, §15.5 후속②) ══");
        log.info("[DonchianTrendMap] 기간 {}~{} · 종목 {}개 · 스윕 {} — 96·120·144는 §15.3 재현 앵커",
                from, to, symbols.size(), TREND_MAP_PERIODS);

        List<ExitLabRow> rows = new ArrayList<>();
        for (int i = 0; i < TREND_MAP_PERIODS.size(); i++) {
            int period = TREND_MAP_PERIODS.get(i);
            toggles.donchian().setTrendMaPeriod(period);
            String name = String.format("T%d 추세%d일", i, period);
            ExitLabRow row = executor.evaluate(name, scope);
            log.info("[DonchianTrendMap] {}: {} → {}", name,
                    row.result().aggregateValidation().summaryLine(), ReportFormat.verdict(row.judgment()));
            rows.add(row);
        }
        toggles.donchian().setTrendMaPeriod(120);
        knobs.restoreRegimeDefaults();

        Path report = reportWriter.writeRiskLabReport(
                scope.withNames(DONCHIAN_LABEL + " · 추세 기간 지도", DONCHIAN_SLUG + "-TRENDMAP"),
                rows, false);
        log.info("[DonchianTrendMap] 리포트: {}", report.toAbsolutePath());
    }

    /** Step 3 — 돈치안 왕복 비용 0.41→0.80% 민감도 (약세장 창, 지수 MA120 ON) */
    public void runCostLab(List<String> symbols, LocalDate from, LocalDate to,
                           DonchianSizing sizing) {
        applyFixedWithIndexFilter(sizing);
        LabScope scope = new LabScope(sizing.label(), sizing.slug(), symbols, from, to);
        int windowCount = LabExecutor.windowCount(scope);
        log.info("[DonchianCost] ══ 돈치안 왕복 비용 상향 민감도 (약세장 창, 지수 MA120 ON, {}) ══",
                sizing.slug());
        log.info("[DonchianCost] 기간 {}~{} · 종목 {}개 · Walk-Forward 윈도우 {}개 · 비용 {}",
                from, to, symbols.size(), windowCount,
                CostProfile.SWEEP.stream().map(c -> ReportFormat.pct(c.roundTrip())).toList());

        List<ExitLabRow> rows = new ArrayList<>();
        for (CostProfile cp : CostProfile.SWEEP) {
            knobs.setRoundTripCost(cp.roundTrip());
            AppliedCost applied = knobs.appliedCost();
            ExitLabRow row = executor.evaluate(cp.name(), scope, applied);
            log.info("[DonchianCost] {} (왕복 {}): {} → {}",
                    cp.name(), ReportFormat.pct(applied.roundTripCost()),
                    row.result().aggregateValidation().summaryLine(), ReportFormat.verdict(row.judgment()));
            rows.add(row);
        }
        // 기본값 복원
        knobs.resetCostDefaults();
        knobs.restoreRegimeDefaults();

        Path report = reportWriter.writeCostLabReport(scope, rows);
        log.info("[DonchianCost] 리포트: {}", report.toAbsolutePath());
    }
}
