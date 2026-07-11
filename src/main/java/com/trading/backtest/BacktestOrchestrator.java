package com.trading.backtest;

import com.trading.strategy.FilterProperties;
import com.trading.strategy.StrategyParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 백테스트 진입점 (B-3) — --spring.profiles.active=backtest 로 부팅 시 실행.
 *
 * full 모드 순서 (설계 문서 §3):
 *   백필 → ① K 민감도 (전기간, 참고) → ② Walk-Forward 기준선 (필터 OFF)
 *   → ③ 필터 A/B (기준선의 윈도우별 선택 K 재사용 — 필터는 튜닝 파라미터가 아니므로
 *     재학습 없이 검증 구간만 재생, 조합 폭발 방지)
 *   → ④ 리포트 + 합격 시 기준선 파일 → 종료
 */
@Component
@Profile("backtest")
public class BacktestOrchestrator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestOrchestrator.class);

    private static final List<Double> K_CANDIDATES = List.of(0.4, 0.5, 0.6);

    private final CandleBackfillService backfillService;
    private final BacktestRunner runner;
    private final WalkForwardEngine walkForwardEngine;
    private final BacktestReportWriter reportWriter;
    private final StrategyParameters strategyParameters;
    private final FilterProperties filters;
    private final BacktestDataProperties properties;
    private final Clock clock;
    private final ConfigurableApplicationContext context;

    public BacktestOrchestrator(CandleBackfillService backfillService,
                                BacktestRunner runner,
                                WalkForwardEngine walkForwardEngine,
                                BacktestReportWriter reportWriter,
                                StrategyParameters strategyParameters,
                                FilterProperties filters,
                                BacktestDataProperties properties,
                                Clock clock,
                                ConfigurableApplicationContext context) {
        this.backfillService = backfillService;
        this.runner = runner;
        this.walkForwardEngine = walkForwardEngine;
        this.reportWriter = reportWriter;
        this.strategyParameters = strategyParameters;
        this.filters = filters;
        this.properties = properties;
        this.clock = clock;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        int exitCode = 0;
        try {
            execute();
        } catch (Exception e) {
            log.error("[Orchestrator] 백테스트 실패", e);
            exitCode = 1;
        }
        // 스케줄러 스레드(비데몬)가 JVM을 붙잡으므로 명시 종료
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    private void execute() {
        log.info("[Orchestrator] candle_history 백필 검사 시작");
        int saved = backfillService.backfillAll();
        log.info("[Orchestrator] 백필 완료: 신규 {}건", saved);

        List<String> symbols = backfillService.targetSymbols();
        LocalDate from = LocalDate.now(clock).minusYears(properties.getYears());
        LocalDate to = backfillService.rangeTo();

        if ("smoke".equalsIgnoreCase(properties.getMode())) {
            BacktestRunner.RunResult smoke = runner.run(
                    new BacktestRunner.RunConfig("SMOKE-K0.5", symbols, from, to));
            log.info("[Orchestrator] 스모크 결과: {}", smoke.metrics().summaryLine());
            return;
        }

        // ① K 민감도 — 전기간 (참고용, §4 민감도 검사의 분모)
        Map<Double, BacktestMetrics> kSensitivity = new LinkedHashMap<>();
        for (double k : K_CANDIDATES) {
            strategyParameters.setK(k);
            BacktestRunner.RunResult r = runner.run(new BacktestRunner.RunConfig(
                    String.format("FULL-K%.1f", k), symbols, from, to));
            kSensitivity.put(k, r.metrics());
        }
        strategyParameters.setK(0.5);

        // ② Walk-Forward 기준선 (필터 전부 OFF)
        allFiltersOff();
        WalkForwardEngine.WalkForwardResult baseline =
                walkForwardEngine.run(symbols, from, to, K_CANDIDATES, null);
        List<Double> chosenKs = baseline.chosenKs();

        // ③ 필터 A/B — 기준선 선택 K 재사용, 검증 구간만 재생
        List<BacktestReportWriter.FilterVariant> variants = new ArrayList<>();
        variants.add(runFilterVariant("트레일링 스톱 1% (+3% 후)", baseline, () -> {
            filters.getTrailingStop().setEnabled(true);
            filters.getTrailingStop().setTrailPct(0.01);
        }, symbols, from, to, chosenKs));
        variants.add(runFilterVariant("트레일링 스톱 2% (+3% 후)", baseline, () -> {
            filters.getTrailingStop().setEnabled(true);
            filters.getTrailingStop().setTrailPct(0.02);
        }, symbols, from, to, chosenKs));
        variants.add(runFilterVariant("지수 레짐 (KOSPI 갭다운 진입 금지)", baseline,
                () -> filters.getIndexRegime().setEnabled(true),
                symbols, from, to, chosenKs));
        allFiltersOff();

        // ④ 리포트 + 기준선
        BacktestReportWriter.ReportData data = new BacktestReportWriter.ReportData(
                symbols, from, to, kSensitivity, baseline, variants);
        BacktestReportWriter.Judgment judgment = reportWriter.judge(data);
        reportWriter.writeReport(data, judgment);

        if (judgment.pass()) {
            List<BacktestReportWriter.FilterVariant> adopted = variants.stream()
                    .filter(BacktestReportWriter.FilterVariant::adopted).toList();
            reportWriter.writeBaseline(data, adopted);
        } else {
            log.warn("[Orchestrator] 불합격 — 기준선 파일을 쓰지 않는다: {}", judgment.reasons());
        }
    }

    private BacktestReportWriter.FilterVariant runFilterVariant(
            String name, WalkForwardEngine.WalkForwardResult baseline, Runnable enable,
            List<String> symbols, LocalDate from, LocalDate to, List<Double> chosenKs) {
        allFiltersOff();
        enable.run();
        WalkForwardEngine.WalkForwardResult result =
                walkForwardEngine.run(symbols, from, to, K_CANDIDATES, chosenKs);

        BacktestMetrics base = baseline.aggregateValidation();
        BacktestMetrics var = result.aggregateValidation();
        boolean adopted = var.profitFactor() > base.profitFactor()
                && var.maxDrawdown() < base.maxDrawdown();
        String note = adopted ? "✅ 채택 후보 (PF·MDD 동반 우위)"
                : "보류 (동반 우위 아님 — §3.3)";
        log.info("[Orchestrator] 필터 A/B {}: {} → {}", name, var.summaryLine(), note);
        return new BacktestReportWriter.FilterVariant(name, result, adopted, note);
    }

    private void allFiltersOff() {
        filters.getEntryWindow().setEnabled(false);
        filters.getVolumeConfirm().setEnabled(false);
        filters.getTrailingStop().setEnabled(false);
        filters.getTrailingStop().setArmProfitPct(0.03);
        filters.getIndexRegime().setEnabled(false);
    }
}
