package com.trading.backtest;

import com.trading.risk.RiskLimitsProperties;
import com.trading.strategy.FilterProperties;
import com.trading.strategy.DonchianProperties;
import com.trading.strategy.MaBreakoutProperties;
import com.trading.strategy.ScalpingProperties;
import com.trading.strategy.StrategyParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
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
    private final EventBacktestPipeline eventPipeline;
    private final LiquidityScreener liquidityScreener;
    private final MaBreakoutProperties maBreakoutProperties;
    private final ScalpingProperties scalpingProperties;
    private final DonchianProperties donchianProperties;
    private final RiskLimitsProperties riskLimits;
    private final ExitLabProperties exitLab;
    private final BacktestCostProperties costProperties;
    private final LowVolCrashBacktester lowVolCrashBacktester;

    public BacktestOrchestrator(CandleBackfillService backfillService,
                                BacktestRunner runner,
                                WalkForwardEngine walkForwardEngine,
                                BacktestReportWriter reportWriter,
                                StrategyParameters strategyParameters,
                                FilterProperties filters,
                                BacktestDataProperties properties,
                                Clock clock,
                                ConfigurableApplicationContext context,
                                EventBacktestPipeline eventPipeline,
                                LiquidityScreener liquidityScreener,
                                MaBreakoutProperties maBreakoutProperties,
                                ScalpingProperties scalpingProperties,
                                DonchianProperties donchianProperties,
                                RiskLimitsProperties riskLimits,
                                ExitLabProperties exitLab,
                                BacktestCostProperties costProperties,
                                LowVolCrashBacktester lowVolCrashBacktester) {
        this.backfillService = backfillService;
        this.runner = runner;
        this.walkForwardEngine = walkForwardEngine;
        this.reportWriter = reportWriter;
        this.strategyParameters = strategyParameters;
        this.filters = filters;
        this.properties = properties;
        this.clock = clock;
        this.context = context;
        this.eventPipeline = eventPipeline;
        this.liquidityScreener = liquidityScreener;
        this.maBreakoutProperties = maBreakoutProperties;
        this.scalpingProperties = scalpingProperties;
        this.donchianProperties = donchianProperties;
        this.riskLimits = riskLimits;
        this.exitLab = exitLab;
        this.costProperties = costProperties;
        this.lowVolCrashBacktester = lowVolCrashBacktester;
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

        LocalDate from = LocalDate.now(clock).minusYears(properties.getYears());
        LocalDate to = backfillService.rangeTo();
        // 유동성 필터 (B-3 유니버스 확장 재검증) — 중소형 후보 중 거래대금 미달 제외.
        // 이벤트 모드(events)는 별도 표본 로직(EventBacktestPipeline)을 쓰므로 영향 없다.
        List<String> symbols = liquidityScreener.filter(
                backfillService.targetSymbols(), to, properties.getMinDailyTradingValue());

        if ("events".equalsIgnoreCase(properties.getMode())) {
            // B-4: 공시 이벤트 유형별 반응 통계 (--backtest.mode=events)
            eventPipeline.run();
            return;
        }

        if ("smoke".equalsIgnoreCase(properties.getMode())) {
            BacktestRunner.RunResult smoke = runner.run(
                    new BacktestRunner.RunConfig("SMOKE-K0.5", symbols, from, to));
            log.info("[Orchestrator] 스모크 결과: {}", smoke.metrics().summaryLine());
            return;
        }

        // 방식2·3(BACKTEST-DESIGN §13) 소급 검증 — VB와 신호가 섞이지 않도록 하나만 켠다
        if ("ma-breakout".equalsIgnoreCase(properties.getMode())) {
            runSingleStrategyMode("이동평균 정배열 돌파(MA_BREAKOUT)", "MA", symbols, from, to, () -> {
                strategyParameters.setEnabled(false);
                maBreakoutProperties.setEnabled(true);
                scalpingProperties.setEnabled(false);
            }, List.of());
            return;
        }

        if ("scalping".equalsIgnoreCase(properties.getMode())) {
            strategyParameters.setEnabled(false);
            maBreakoutProperties.setEnabled(false);
            scalpingProperties.setEnabled(true);
            runScalpingMode(symbols, from, to);
            return;
        }

        // Exit Lab (BACKTEST-DESIGN §14) — 진입 고정, 출구 프로필 스윕 (손익비 재설계)
        if ("exit-lab".equalsIgnoreCase(properties.getMode())) {
            runExitLab("변동성 돌파(VB)", "VB", symbols, from, to, () -> {
                strategyParameters.setEnabled(true);
                maBreakoutProperties.setEnabled(false);
                scalpingProperties.setEnabled(false);
            });
            runExitLab("이동평균 정배열 돌파(MA_BREAKOUT)", "MA", symbols, from, to, () -> {
                strategyParameters.setEnabled(false);
                maBreakoutProperties.setEnabled(true);
                scalpingProperties.setEnabled(false);
            });
            return;
        }

        // Donchian 돌파(전략1=VB 대체 후보, 2026-08) — 진입만 Donchian으로 바꿔 검증된
        // exit-lab(P0~P4) 스윕을 그대로 태운다. §14의 MA 검증 경로와 동일 절차로 비교.
        if ("donchian".equalsIgnoreCase(properties.getMode())) {
            runExitLab("돈치안 돌파(DONCHIAN)", "DONCHIAN", symbols, from, to, () -> {
                strategyParameters.setEnabled(false);
                maBreakoutProperties.setEnabled(false);
                scalpingProperties.setEnabled(false);
                donchianProperties.setEnabled(true);
            });
            return;
        }

        // Risk Lab (BACKTEST-DESIGN §14) — MA 진입 + P3 다일 트레일링 고정, 사이징·동시보유 스윕.
        // §14.1 후보 검증은 전역 symbols/from/to(부동)가 아니라 고정 후보 설정으로 재현한다.
        if ("risk-lab".equalsIgnoreCase(properties.getMode())) {
            List<String> candidateSymbols = prepareCandidateUniverse(
                    "risk-lab", properties.getCandidateFrom(), properties.getCandidateTo());
            runRiskLab(candidateSymbols, properties.getCandidateFrom(), properties.getCandidateTo());
            return;
        }

        // Donchian Risk Lab (2026-08) — Donchian P3가 exit-lab에서 MDD 15.6%로 0.6%p만 초과했다.
        // 진입=Donchian, 출구=P3 고정, 사이징만 스윕해 MDD를 §4(≤15%) 안으로 넣는지 본다.
        // MA와 동일한 54-후보/후보창을 써서 apples-to-apples 비교. 기준선은 안 건드린다(writeBaseline=false).
        if ("donchian-risk-lab".equalsIgnoreCase(properties.getMode())) {
            List<String> candidateSymbols = prepareCandidateUniverse(
                    "donchian-risk-lab", properties.getCandidateFrom(), properties.getCandidateTo());
            runRiskLab("돈치안 돌파 + P3 다일 트레일링", "DONCHIAN-P3", () -> {
                strategyParameters.setEnabled(false);
                maBreakoutProperties.setEnabled(false);
                scalpingProperties.setEnabled(false);
                donchianProperties.setEnabled(true);
            }, false, candidateSymbols, properties.getCandidateFrom(), properties.getCandidateTo());
            return;
        }

        // Cost Lab (BACKTEST-DESIGN §14.1) — 검증 후보(MA+P3+RR1) 고정, 왕복 거래비용만 상향 스윕.
        if ("cost-lab".equalsIgnoreCase(properties.getMode())) {
            List<String> candidateSymbols = prepareCandidateUniverse(
                    "cost-lab", properties.getCandidateFrom(), properties.getCandidateTo());
            runCostLab(candidateSymbols, properties.getCandidateFrom(), properties.getCandidateTo());
            return;
        }

        // Crash-Vol (BACKLOG 2026-07-24 탐색) — 지수 급락 직후 저변동성 종목 전방수익률 측정.
        // 유니버스는 후보 54종목 그대로 쓰되 기간은 전용 crash-vol-from/to(2020-01~)를 쓴다 —
        // §14.1 판정 창(2023-07~)에는 진짜 하락장이 없어 코로나·금리쇼크를 못 본다.
        // cost-lab·risk-lab의 candidate-from/to는 재현 기준선이므로 건드리지 않는다.
        if ("crash-vol".equalsIgnoreCase(properties.getMode())) {
            List<String> candidateSymbols = prepareCandidateUniverse(
                    "crash-vol", properties.getCrashVolFrom(), properties.getCrashVolTo());
            runCrashVol(candidateSymbols, properties.getCrashVolFrom(), properties.getCrashVolTo());
            return;
        }

        // Regime Lab (BACKTEST-DESIGN §14.4) — 지수 하락 추세 진입금지 필터 A/B (측정, 채택 아님).
        // §14.3에서 후보를 무너뜨린 2022 휩쏘(하락 추세 반등마다 진입해 잘림)가 이 필터로
        // 제거되는지 본다. 창은 약세장을 포함하는 전용 stress-from/to —
        // candidate-from/to(§14.1 재현 기준선)는 건드리지 않는다.
        if ("regime-lab".equalsIgnoreCase(properties.getMode())) {
            List<String> candidateSymbols = prepareCandidateUniverse(
                    "regime-lab", properties.getStressFrom(), properties.getStressTo());
            runRegimeLab(candidateSymbols, properties.getStressFrom(), properties.getStressTo());
            return;
        }

        // Regime Sens (BACKTEST-DESIGN §14.4 민감도) — regime-lab의 승자 MA120 ±20%(MA96·MA144)
        // 단일 파라미터 민감도. 고정 조건·창은 regime-lab과 동일, MA 기간만 스윕한다.
        if ("regime-sens".equalsIgnoreCase(properties.getMode())) {
            List<String> candidateSymbols = prepareCandidateUniverse(
                    "regime-sens", properties.getStressFrom(), properties.getStressTo());
            runRegimeSensitivity(candidateSymbols, properties.getStressFrom(), properties.getStressTo());
            return;
        }

        // VB(방식1) 기준 실행 — 방식2·3이 같이 켜져 신호가 섞이지 않도록 명시적으로 끈다
        strategyParameters.setEnabled(true);
        maBreakoutProperties.setEnabled(false);
        scalpingProperties.setEnabled(false);

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
        variants.add(runFilterVariant("공시 쿨다운 5일 (이벤트성 공시 후 진입 금지)", baseline, () -> {
            filters.getDisclosureCooldown().setEnabled(true);
            filters.getDisclosureCooldown().setCooldownDays(5);
        }, symbols, from, to, chosenKs));
        variants.add(runFilterVariant("공시 쿨다운 3일", baseline, () -> {
            filters.getDisclosureCooldown().setEnabled(true);
            filters.getDisclosureCooldown().setCooldownDays(3);
        }, symbols, from, to, chosenKs));
        // §3.3: 조합 탐색은 상위 2개까지만 (조합 폭발 = 과최적화 지름길)
        variants.add(runFilterVariant("조합: 트레일링 1% + 공시 쿨다운 5일", baseline, () -> {
            filters.getTrailingStop().setEnabled(true);
            filters.getTrailingStop().setTrailPct(0.01);
            filters.getDisclosureCooldown().setEnabled(true);
            filters.getDisclosureCooldown().setCooldownDays(5);
        }, symbols, from, to, chosenKs));
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
        filters.getDisclosureCooldown().setEnabled(false);
        filters.getDisclosureCooldown().setCooldownDays(5);
    }

    // ── BACKTEST-DESIGN §13: 방식2·3 소급 검증 ────────────────────────────────
    //
    // WalkForwardEngine.run()은 StrategyParameters.k만 스윕한다 — MA돌파·스캘핑은 이
    // 파라미터를 쓰지 않으므로(VB가 위에서 이미 꺼져 있음) 단일값 List.of(0.5)로 호출해
    // 의미 없는 3배 반복을 피한다.

    /** K/필터 개념이 없는 단일 변형 실행(MA돌파 등) — variants는 있으면 "변형 비교" 섹션에 채워진다 */
    private void runSingleStrategyMode(String label, String slug, List<String> symbols,
                                       LocalDate from, LocalDate to, Runnable enableOnly,
                                       List<BacktestReportWriter.FilterVariant> variants) {
        enableOnly.run();
        WalkForwardEngine.WalkForwardResult result =
                walkForwardEngine.run(symbols, from, to, List.of(0.5), null);
        writeSingleStrategyReport(label, slug, symbols, from, to, result, variants);
    }

    /** 스캘핑(방식3) — 자체 파라미터 4종(windowSize/pullbackPct/reboundPct/takeProfitPct) ±20% 민감도 포함 */
    private void runScalpingMode(List<String> symbols, LocalDate from, LocalDate to) {
        resetScalpingDefaults();
        WalkForwardEngine.WalkForwardResult baseline =
                walkForwardEngine.run(symbols, from, to, List.of(0.5), null);

        List<BacktestReportWriter.FilterVariant> variants = new ArrayList<>();
        variants.add(scalpingVariant("windowSize -20% (8틱)", baseline, symbols, from, to,
                () -> scalpingProperties.setWindowSize(8)));
        variants.add(scalpingVariant("windowSize +20% (12틱)", baseline, symbols, from, to,
                () -> scalpingProperties.setWindowSize(12)));
        variants.add(scalpingVariant("pullbackPct -20% (0.4%)", baseline, symbols, from, to,
                () -> scalpingProperties.setPullbackPct(0.004)));
        variants.add(scalpingVariant("pullbackPct +20% (0.6%)", baseline, symbols, from, to,
                () -> scalpingProperties.setPullbackPct(0.006)));
        variants.add(scalpingVariant("reboundPct -20% (0.24%)", baseline, symbols, from, to,
                () -> scalpingProperties.setReboundPct(0.0024)));
        variants.add(scalpingVariant("reboundPct +20% (0.36%)", baseline, symbols, from, to,
                () -> scalpingProperties.setReboundPct(0.0036)));
        variants.add(scalpingVariant("takeProfitPct -20% (0.64%)", baseline, symbols, from, to,
                () -> scalpingProperties.setTakeProfitPct(0.0064)));
        variants.add(scalpingVariant("takeProfitPct +20% (0.96%)", baseline, symbols, from, to,
                () -> scalpingProperties.setTakeProfitPct(0.0096)));
        resetScalpingDefaults();

        writeSingleStrategyReport("눌림목 반등 스캘핑(SCALPING_MOMENTUM)", "SCALPING",
                symbols, from, to, baseline, variants);
    }

    private BacktestReportWriter.FilterVariant scalpingVariant(String name,
            WalkForwardEngine.WalkForwardResult baseline, List<String> symbols,
            LocalDate from, LocalDate to, Runnable perturb) {
        resetScalpingDefaults();
        perturb.run();
        WalkForwardEngine.WalkForwardResult result =
                walkForwardEngine.run(symbols, from, to, List.of(0.5), null);
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
        scalpingProperties.setWindowSize(10);
        scalpingProperties.setPullbackPct(0.005);
        scalpingProperties.setReboundPct(0.003);
        scalpingProperties.setTakeProfitPct(0.008);
    }

    private void writeSingleStrategyReport(String label, String slug, List<String> symbols,
                                           LocalDate from, LocalDate to,
                                           WalkForwardEngine.WalkForwardResult result,
                                           List<BacktestReportWriter.FilterVariant> variants) {
        BacktestReportWriter.ReportData data = new BacktestReportWriter.ReportData(
                symbols, from, to, Map.of(), result, variants, label, slug);
        BacktestReportWriter.Judgment judgment = reportWriter.judge(data);
        reportWriter.writeReport(data, judgment);
        if (judgment.pass()) {
            reportWriter.writeBaseline(data, variants.stream()
                    .filter(BacktestReportWriter.FilterVariant::adopted).toList());
        } else {
            log.warn("[Orchestrator] {} 불합격 — 기준선 파일을 쓰지 않는다: {}", label, judgment.reasons());
        }
    }

    // ── BACKTEST-DESIGN §14: Exit Lab — 손익비 재설계(다일 보유) ────────────────
    //
    // 진입 로직은 고정(enableEntry)하고 출구 프로필만 스윕한다. K는 VB 기본 0.5로 고정
    // (출구 효과를 격리하려고 진입 파라미터는 건드리지 않는다). 프로필은 5개로 제한(§3.3).

    /** 출구 프로필 — atrMult/타임컷/최대보유/트레일링 조합 */
    private record ExitProfile(String name, double atrMult, boolean timecut, int maxHoldDays,
                               boolean trailEnabled, double trailArmPct, double trailPct) {}

    private static final List<ExitProfile> EXIT_PROFILES = List.of(
            new ExitProfile("P0 기준선(ATR1.5·타임컷ON)",           1.5, true,  0,  false, 0,    0),
            new ExitProfile("P1 타이트손절·당일(ATR1.0·타임컷ON)",  1.0, true,  0,  false, 0,    0),
            new ExitProfile("P2 다일·느슨트레일(ATR1.0·20일·arm2%/trail5%)", 1.0, false, 20, true,  0.02, 0.05),
            new ExitProfile("P3 다일·조인트레일(ATR1.0·20일·arm1%/trail3%)", 1.0, false, 20, true,  0.01, 0.03),
            new ExitProfile("P4 다일·순수손절(ATR1.0·20일·트레일OFF)",       1.0, false, 20, false, 0,    0));

    private void runExitLab(String label, String slug, List<String> symbols,
                            LocalDate from, LocalDate to, Runnable enableEntry) {
        enableEntry.run();
        List<BacktestReportWriter.ExitLabRow> rows = new ArrayList<>();
        for (ExitProfile p : EXIT_PROFILES) {
            applyExitProfile(p);
            WalkForwardEngine.WalkForwardResult result =
                    walkForwardEngine.run(symbols, from, to, List.of(0.5), null);
            BacktestReportWriter.ReportData judgeData = new BacktestReportWriter.ReportData(
                    symbols, from, to, Map.of(), result, List.of(), label, slug);
            BacktestReportWriter.Judgment judgment = reportWriter.judge(judgeData);
            log.info("[ExitLab] {} · {}: {} → {}", label, p.name(),
                    result.aggregateValidation().summaryLine(), judgment.pass() ? "✅ 합격" : "❌ 불합격");
            rows.add(new BacktestReportWriter.ExitLabRow(p.name(), result, judgment));
        }
        resetExitProfile();
        reportWriter.writeExitLabReport(label, slug, symbols, from, to, rows);
    }

    // ── Risk Lab (§14) — 검증된 엣지(MA+P3)를 MDD 기준 안으로 넣는 리스크 축소 스윕 ──
    //
    // 진입=MA 정배열, 출구=P3(ATR1.0·타임컷OFF·20일·트레일 arm1%/trail3%) 고정. 사이징(1R
    // 비율)과 동시보유 종목수만 바꾼다 — 손익비·기대값은 사이징에 불변이고 MDD만 스케일다운.

    private record RiskProfile(String name, double riskFraction, int maxPositions) {}

    private static final List<RiskProfile> RISK_PROFILES = List.of(
            new RiskProfile("RR0 기준(1.0R·동시5)",   0.01,  5),
            new RiskProfile("RR1 하프(0.5R·동시5)",    0.005, 5),
            new RiskProfile("RR2 하프·집중(0.5R·동시3)", 0.005, 3),
            new RiskProfile("RR3 쿼터(0.25R·동시5)",   0.0025, 5),
            new RiskProfile("RR4 하프·최집중(0.5R·동시2)", 0.005, 2));

    /**
     * cost-lab·risk-lab 전용 — §14.1 후보 유니버스(54종목)를 idempotent 백필하고, 이번 실행이
     * 부동 날짜가 아닌 고정 후보 설정으로 도는 재현성 실행임을 로그로 남긴다. targetSymbols는
     * 건드리지 않으므로(backfillExtra는 추가 표본만 적재) VB 유니버스는 오염되지 않는다.
     */
    private List<String> prepareCandidateUniverse(String mode, LocalDate from, LocalDate to) {
        List<String> candidateSymbols = properties.getCandidateSymbols();
        int extra = backfillService.backfillExtra(candidateSymbols);
        log.info("[Orchestrator] {} 후보 유니버스 백필: 신규 {}건 (idempotent — 기존은 스킵)", mode, extra);
        log.info("[Orchestrator] {} 후보 고정 설정 사용(candidate-symbols {}개, 기간 {}~{}) — 재현성 실행",
                mode, candidateSymbols.size(), from, to);
        return candidateSymbols;
    }

    /** §14.1 MA 후보 재현 — 진입=MA 고정, 기준선 기록은 write-baseline 플래그 따름 */
    private void runRiskLab(List<String> symbols, LocalDate from, LocalDate to) {
        runRiskLab("MA 정배열 + P3 다일 트레일링", "MA-P3", () -> {
            strategyParameters.setEnabled(false);
            maBreakoutProperties.setEnabled(true);
            scalpingProperties.setEnabled(false);
        }, properties.isWriteBaseline(), symbols, from, to);
    }

    /**
     * 진입을 파라미터화한 Risk Lab — 진입=enableEntry, 출구=P3 고정, 사이징(1R 비율)·동시보유만 스윕.
     * 손익비·기대값은 사이징에 불변이고 MDD만 스케일다운 → exit-lab에서 MDD만 아슬하게 넘긴
     * 후보(예: Donchian P3 15.6%)를 §4 안으로 넣는지 본다. writeBaseline=false면 기준선을 안 건드림.
     */
    private void runRiskLab(String label, String slug, Runnable enableEntry, boolean writeBaseline,
                            List<String> symbols, LocalDate from, LocalDate to) {
        enableEntry.run();
        applyExitProfile(new ExitProfile("P3", 1.0, false, 20, true, 0.01, 0.03));

        List<BacktestReportWriter.ExitLabRow> rows = new ArrayList<>();
        for (RiskProfile rp : RISK_PROFILES) {
            riskLimits.setRiskFractionPerTrade(rp.riskFraction());
            riskLimits.setMaxPositionCount(rp.maxPositions());
            WalkForwardEngine.WalkForwardResult result =
                    walkForwardEngine.run(symbols, from, to, List.of(0.5), null);
            BacktestReportWriter.ReportData judgeData = new BacktestReportWriter.ReportData(
                    symbols, from, to, Map.of(), result, List.of(), label, slug);
            BacktestReportWriter.Judgment judgment = reportWriter.judge(judgeData);
            log.info("[RiskLab] {} · {}: {} → {}", slug, rp.name(),
                    result.aggregateValidation().summaryLine(), judgment.pass() ? "✅ 합격" : "❌ 불합격");
            rows.add(new BacktestReportWriter.ExitLabRow(rp.name(), result, judgment));
        }
        // 기본값 복원
        riskLimits.setRiskFractionPerTrade(com.trading.risk.RiskLimits.RISK_FRACTION_PER_TRADE);
        riskLimits.setMaxPositionCount(com.trading.risk.RiskLimits.MAX_POSITION_COUNT);
        resetExitProfile();

        reportWriter.writeRiskLabReport(label, slug, symbols, from, to, rows, writeBaseline);
    }

    private void applyExitProfile(ExitProfile p) {
        riskLimits.setAtrStopMultiplier(p.atrMult());
        exitLab.setTimecutEnabled(p.timecut());
        exitLab.setMaxHoldDays(p.maxHoldDays());
        allFiltersOff();
        if (p.trailEnabled()) {
            filters.getTrailingStop().setEnabled(true);
            filters.getTrailingStop().setArmProfitPct(p.trailArmPct());
            filters.getTrailingStop().setTrailPct(p.trailPct());
        }
    }

    private void resetExitProfile() {
        riskLimits.setAtrStopMultiplier(com.trading.risk.RiskLimits.ATR_STOP_MULTIPLIER);
        exitLab.resetDefaults();
        allFiltersOff();
    }

    // ── Cost Lab (§14.1) — 왕복 거래비용 상향 민감도 ────────────────────────────
    //
    // 검증된 후보(진입=MA 정배열, 출구=P3 다일 트레일링, 사이징=RR1 0.5R·동시5)를 전부
    // 고정하고 왕복 비용만 0.41%→0.80%로 올린다. 비용은 슬리피지에만 얹는다
    // (수수료·제세는 법정 확정값 — BacktestCostProperties 클래스 주석 참고).
    // C0(0.41%)는 §14.1 RR1 재현을 확인하는 회귀 앵커다.

    private record CostProfile(String name, double roundTrip) {}

    private static final List<CostProfile> COST_PROFILES = List.of(
            new CostProfile("C0 기준 0.41%(회귀 앵커)", 0.0041),
            new CostProfile("C1 0.50%", 0.0050),
            new CostProfile("C2 0.60%", 0.0060),
            new CostProfile("C3 0.70%", 0.0070),
            new CostProfile("C4 0.80%", 0.0080));

    private static final String COST_LAB_LABEL = "MA+P3+RR1";
    private static final String COST_LAB_SLUG  = "MA-P3-RR1";

    private void runCostLab(List<String> symbols, LocalDate from, LocalDate to) {
        // 고정 조건: 진입=MA 정배열 / 출구=P3 다일 트레일링 / 사이징=0.5R·동시5
        strategyParameters.setEnabled(false);
        maBreakoutProperties.setEnabled(true);
        scalpingProperties.setEnabled(false);
        applyExitProfile(new ExitProfile("P3", 1.0, false, 20, true, 0.01, 0.03));
        riskLimits.setRiskFractionPerTrade(0.005);
        riskLimits.setMaxPositionCount(5);

        logCostLabBanner(symbols, from, to);
        long labStart = System.nanoTime();
        List<BacktestReportWriter.ExitLabRow> rows = new ArrayList<>();
        for (int i = 0; i < COST_PROFILES.size(); i++) {
            rows.add(runCostProfile(COST_PROFILES.get(i), i, symbols, from, to));
        }

        // 기본값 복원 — 다른 모드/런으로의 누출 방지
        costProperties.resetDefaults();
        resetExitProfile();
        riskLimits.setRiskFractionPerTrade(com.trading.risk.RiskLimits.RISK_FRACTION_PER_TRADE);
        riskLimits.setMaxPositionCount(com.trading.risk.RiskLimits.MAX_POSITION_COUNT);

        Path report = reportWriter.writeCostLabReport(
                "MA 정배열 + P3 다일 트레일링 + RR1(0.5R·동시5)", COST_LAB_SLUG,
                symbols, from, to, rows);
        logCostLabSummary(rows, labStart, report);
    }

    /** 비용 프로필 1개 실행 — 세팅 → Walk-Forward → §4 판정 (진행 로그 포함) */
    private BacktestReportWriter.ExitLabRow runCostProfile(CostProfile cp, int index,
            List<String> symbols, LocalDate from, LocalDate to) {
        costProperties.setRoundTripCost(cp.roundTrip());
        // 목표값(cp.roundTrip())이 아니라 홀더에서 "다시 읽은" 실제 적용값을 기록한다 —
        // 역산이 틀리면 라벨은 그대로인 채 표가 어긋난 값을 드러내야 한다 (F-C1 재발 방지).
        BacktestReportWriter.AppliedCost applied = new BacktestReportWriter.AppliedCost(
                costProperties.getSlippageRate(), costProperties.roundTripCost());
        log.info("[CostLab] ({}/{}) {} 시작 — 실제 적용: 편도 슬리피지 {}, 왕복 {}",
                index + 1, COST_PROFILES.size(), cp.name(),
                pct(applied.slippageRate()), pct(applied.roundTripCost()));

        long started = System.nanoTime();
        WalkForwardEngine.WalkForwardResult result =
                walkForwardEngine.run(symbols, from, to, List.of(0.5), null);
        BacktestReportWriter.ReportData judgeData = new BacktestReportWriter.ReportData(
                symbols, from, to, Map.of(), result, List.of(), COST_LAB_LABEL, COST_LAB_SLUG);
        BacktestReportWriter.Judgment judgment = reportWriter.judge(judgeData);

        log.info("[CostLab] ({}/{}) {} 완료 ({}초): {} → {}",
                index + 1, COST_PROFILES.size(), cp.name(), elapsedSec(started),
                result.aggregateValidation().summaryLine(),
                judgment.pass() ? "✅ 합격" : "❌ 불합격");
        if (!judgment.pass()) {
            judgment.reasons().forEach(r -> log.info("[CostLab]     불합격 사유: {}", r));
        }
        return new BacktestReportWriter.ExitLabRow(cp.name(), result, judgment, applied);
    }

    /** 시작 배너 — 수십 분 걸리는 작업이므로 무엇을 몇 번 도는지 먼저 보여준다 */
    private void logCostLabBanner(List<String> symbols, LocalDate from, LocalDate to) {
        int windowCount = WalkForwardEngine.windows(from, to).size();
        log.info("[CostLab] ══ 왕복 비용 상향 민감도 (BACKTEST-DESIGN §14.1) ══");
        log.info("[CostLab] 고정 조건: 진입=MA 정배열 / 출구=P3 다일 트레일링(ATR1.0·최대20일·arm1%/trail3%)"
                + " / 사이징=0.5R·동시5");
        log.info("[CostLab] 기간: {} ~ {} (Walk-Forward 6/3/3 윈도우 {}개)", from, to, windowCount);
        log.info("[CostLab] 종목 {}개: {}", symbols.size(), String.join(", ", symbols));
        log.info("[CostLab] 스윕 비용: {}", COST_PROFILES.stream()
                .map(c -> String.format("%s=%s", c.name(), pct(c.roundTrip()))).toList());
        log.info("[CostLab] 총 실행 예정: 프로필 {} × 윈도우 {} = {}런",
                COST_PROFILES.size(), windowCount, COST_PROFILES.size() * windowCount);
    }

    /** 종료 요약 — 프로필별 한 줄 표를 로그로도 남긴다(리포트 파일을 열지 않아도 판독 가능하게) */
    private void logCostLabSummary(List<BacktestReportWriter.ExitLabRow> rows,
                                   long labStart, Path report) {
        log.info("[CostLab] ══ 전체 완료 ({}초) ══", elapsedSec(labStart));
        log.info("[CostLab] 프로필 | 트레이드 | PF | 기대값 | MDD | 판정");
        for (BacktestReportWriter.ExitLabRow row : rows) {
            BacktestMetrics m = row.result().aggregateValidation();
            log.info("[CostLab]   {} | {}건 | PF {} | 기대값 {}% | MDD {}% | {}",
                    row.profileName(), m.tradeCount(),
                    Double.isInfinite(m.profitFactor()) ? "inf" : String.format("%.2f", m.profitFactor()),
                    String.format("%+.3f", m.expectancyPct() * 100),
                    String.format("%.1f", m.maxDrawdown() * 100),
                    row.judgment().pass() ? "✅ 합격" : "❌ 불합격");
        }
        log.info("[CostLab] 리포트: {}", report.toAbsolutePath());
    }

    // ── Regime Lab (§14.4) — 지수 하락 추세에서 신규 진입을 막으면 휩쏘가 사라지는가 ──
    //
    // 진입=MA 정배열, 출구=P3, 사이징=RR1(0.5R·동시5)까지 §14.3 스트레스 실행과 똑같이 고정하고
    // 지수 추세 필터만 켠다. G0(필터 OFF)는 §14.3 RR1을 재현해야 하는 회귀 앵커다 —
    // 재현되지 않으면 G1·G2 비교는 의미가 없다. 프로필은 3개로 제한(§3.3 조합 폭발 방지).

    record RegimeProfile(String name, boolean trendEnabled, int maPeriod) {}

    static final List<RegimeProfile> REGIME_PROFILES = List.of(
            new RegimeProfile("G0 필터OFF(회귀 앵커)",            false, 200),
            new RegimeProfile("G1 지수 MA120 이탈 시 진입금지",    true,  120),
            new RegimeProfile("G2 지수 MA200 이탈 시 진입금지",    true,  200));

    // §14.4 민감도: regime-lab 승자 MA120의 ±20%. S0(회귀 앵커)는 §14.4 G0와 동일(OFF·200),
    // S2(MA120 기준)는 §14.4 G1을 재현하는 회귀 앵커다.
    static final List<RegimeProfile> REGIME_SENS_PROFILES = List.of(
            new RegimeProfile("S0 필터OFF(회귀 앵커)",  false, 200),
            new RegimeProfile("S1 MA96 (-20%)",         true,  96),
            new RegimeProfile("S2 MA120 (기준)",        true,  120),
            new RegimeProfile("S3 MA144 (+20%)",        true,  144));

    private static final String REGIME_LAB_LABEL =
            "MA 정배열 + P3 다일 트레일링 + RR1(0.5R·동시5)";
    private static final String REGIME_LAB_SLUG = "MA-P3-RR1";

    /** regime-lab/sens 공통 고정 조건: 진입=MA 정배열 / 출구=P3 다일 트레일링 / 사이징=0.5R·동시5 */
    private void applyRegimeFixedConditions() {
        strategyParameters.setEnabled(false);
        maBreakoutProperties.setEnabled(true);
        scalpingProperties.setEnabled(false);
        applyExitProfile(new ExitProfile("P3", 1.0, false, 20, true, 0.01, 0.03));
        riskLimits.setRiskFractionPerTrade(0.005);
        riskLimits.setMaxPositionCount(5);
    }

    private void runRegimeLab(List<String> symbols, LocalDate from, LocalDate to) {
        applyRegimeFixedConditions();

        logRegimeLabBanner(symbols, from, to);
        long labStart = System.nanoTime();
        List<BacktestReportWriter.ExitLabRow> rows = new ArrayList<>();
        for (int i = 0; i < REGIME_PROFILES.size(); i++) {
            rows.add(runRegimeProfile(REGIME_PROFILES.get(i), i, REGIME_PROFILES.size(),
                    "RegimeLab", symbols, from, to));
        }

        restoreRegimeDefaults();

        Path report = reportWriter.writeRegimeLabReport(
                REGIME_LAB_LABEL, REGIME_LAB_SLUG, symbols, from, to, rows);
        logRegimeLabSummary(rows, labStart, report);
    }

    /**
     * 레짐 프로필 1개 실행 — 필터 세팅 → Walk-Forward → §4 판정 (2022 창 진행 로그 포함).
     * {@code tag}/{@code total}은 로그 태그·분모만 바꾼다 — regime-lab은 "RegimeLab"·프로필 3개를
     * 그대로 넘겨 출력이 이전과 한 톨도 다르지 않다(§14.4 재현). regime-sens는 "RegimeSens"·4개.
     */
    private BacktestReportWriter.ExitLabRow runRegimeProfile(RegimeProfile rp, int index, int total,
            String tag, List<String> symbols, LocalDate from, LocalDate to) {
        filters.getIndexTrend().setEnabled(rp.trendEnabled());
        filters.getIndexTrend().setMaPeriod(rp.maPeriod());
        log.info("[{}] ({}/{}) {} 시작 — 실제 적용: 지수 추세 필터 {}",
                tag, index + 1, total, rp.name(),
                filters.getIndexTrend().isEnabled()
                        ? "ON (MA" + filters.getIndexTrend().getMaPeriod() + " 이탈 시 신규 매수 차단)"
                        : "OFF");

        long started = System.nanoTime();
        WalkForwardEngine.WalkForwardResult result =
                walkForwardEngine.run(symbols, from, to, List.of(0.5), null);
        BacktestReportWriter.ReportData judgeData = new BacktestReportWriter.ReportData(
                symbols, from, to, Map.of(), result, List.of(), REGIME_LAB_LABEL, REGIME_LAB_SLUG);
        BacktestReportWriter.Judgment judgment = reportWriter.judge(judgeData);

        log.info("[{}] ({}/{}) {} 완료 ({}초): {} → {}",
                tag, index + 1, total, rp.name(), elapsedSec(started),
                result.aggregateValidation().summaryLine(),
                judgment.pass() ? "✅ 합격" : "❌ 불합격");
        if (!judgment.pass()) {
            judgment.reasons().forEach(r -> log.info("[{}]     불합격 사유: {}", tag, r));
        }
        logCrisisWindows(rp, tag, result);
        return new BacktestReportWriter.ExitLabRow(rp.name(), result, judgment);
    }

    /** 고정 조건 복원 — 지수 추세 필터 OFF·200, 출구·사이징 상수 복원(regime-lab/sens 공용) */
    private void restoreRegimeDefaults() {
        filters.getIndexTrend().setEnabled(false);
        filters.getIndexTrend().setMaPeriod(200);
        resetExitProfile();
        riskLimits.setRiskFractionPerTrade(com.trading.risk.RiskLimits.RISK_FRACTION_PER_TRADE);
        riskLimits.setMaxPositionCount(com.trading.risk.RiskLimits.MAX_POSITION_COUNT);
    }

    /**
     * 2022(금리 쇼크) 검증 창만 따로 로그로 남긴다 — 이 실험의 핵심 판정 칸인데,
     * 비교 리포트는 <b>합격</b> 프로필의 창별 표만 싣기 때문이다(§14.3 판정에서 지적된 공백).
     */
    private void logCrisisWindows(RegimeProfile rp, String tag,
            WalkForwardEngine.WalkForwardResult result) {
        for (WalkForwardEngine.WindowResult w : result.windows()) {
            if (w.window().validateFrom().getYear() != 2022) continue;
            BacktestMetrics vm = w.validateMetrics();
            log.info("[{}]     {} · 2022 창 {}~{}: PF {} | 수익률 {}% | 트레이드 {}건",
                    tag, rp.name(), w.window().validateFrom(), w.window().validateTo(),
                    Double.isInfinite(vm.profitFactor()) ? "inf" : String.format("%.2f", vm.profitFactor()),
                    String.format("%+.2f", vm.totalReturnPct() * 100), vm.tradeCount());
        }
    }

    /** 시작 배너 — 프로필 3개 × 창 24개면 수십 분이므로 무엇을 몇 번 도는지 먼저 보여준다 */
    private void logRegimeLabBanner(List<String> symbols, LocalDate from, LocalDate to) {
        int windowCount = WalkForwardEngine.windows(from, to).size();
        log.info("[RegimeLab] ══ 지수 추세 진입금지 필터 A/B (BACKTEST-DESIGN §14.4) ══");
        log.info("[RegimeLab] 고정 조건: 진입=MA 정배열 / 출구=P3 다일 트레일링(ATR1.0·최대20일·arm1%/trail3%)"
                + " / 사이징=0.5R·동시5");
        log.info("[RegimeLab] 기간: {} ~ {} (stress-from/to — candidate-from/to {} ~ {} 와 별개, "
                        + "2020 코로나·2022 금리쇼크를 포함하는 약세장 창)",
                from, to, properties.getCandidateFrom(), properties.getCandidateTo());
        log.info("[RegimeLab] Walk-Forward 6/3/3 윈도우 {}개 · 종목 {}개", windowCount, symbols.size());
        log.info("[RegimeLab] 프로필: {}", REGIME_PROFILES.stream().map(RegimeProfile::name).toList());
        log.info("[RegimeLab] 총 실행 예정: 프로필 {} × 윈도우 {} = {}런",
                REGIME_PROFILES.size(), windowCount, REGIME_PROFILES.size() * windowCount);
        log.info("[RegimeLab] G0(필터OFF)는 회귀 앵커 — §14.3 RR1(트레이드 1591·PF 1.26·MDD 33.5%)을 "
                + "재현하지 못하면 이 실행은 무효다");
    }

    /** 종료 요약 — 프로필별 한 줄 표 (리포트 파일을 열지 않아도 판독 가능하게) */
    private void logRegimeLabSummary(List<BacktestReportWriter.ExitLabRow> rows,
                                     long labStart, Path report) {
        log.info("[RegimeLab] ══ 전체 완료 ({}초) ══", elapsedSec(labStart));
        log.info("[RegimeLab] 프로필 | 트레이드 | PF | 기대값 | MDD | 판정");
        for (BacktestReportWriter.ExitLabRow row : rows) {
            BacktestMetrics m = row.result().aggregateValidation();
            log.info("[RegimeLab]   {} | {}건 | PF {} | 기대값 {}% | MDD {}% | {}",
                    row.profileName(), m.tradeCount(),
                    Double.isInfinite(m.profitFactor()) ? "inf" : String.format("%.2f", m.profitFactor()),
                    String.format("%+.3f", m.expectancyPct() * 100),
                    String.format("%.1f", m.maxDrawdown() * 100),
                    row.judgment().pass() ? "✅ 합격" : "❌ 불합격");
        }
        log.info("[RegimeLab] 트레이드 수가 급감했으면 상승장 수익까지 잘라낸 것 — "
                + "MDD만 보지 말고 PF·기대값·건수를 함께 볼 것(과필터)");
        log.info("[RegimeLab] 리포트: {}", report.toAbsolutePath());
    }

    // ── Regime Sens (§14.4 민감도) — 승자 MA120 ±20%(MA96·MA144)에서도 성과가 완만한가 ──
    //
    // 진입=MA 정배열, 출구=P3, 사이징=RR1까지 regime-lab과 똑같이 고정하고 지수 추세 필터의
    // MA 기간만 96/120/144로 스윕한다(단일 파라미터). S0(필터 OFF)는 §14.4 G0, S2(MA120)는
    // §14.4 G1을 재현해야 하는 회귀 앵커 2개다 — 재현되지 않으면 이 실행은 무효다.

    private void runRegimeSensitivity(List<String> symbols, LocalDate from, LocalDate to) {
        applyRegimeFixedConditions();

        logRegimeSensBanner(symbols, from, to);
        long labStart = System.nanoTime();
        List<BacktestReportWriter.ExitLabRow> rows = new ArrayList<>();
        for (int i = 0; i < REGIME_SENS_PROFILES.size(); i++) {
            rows.add(runRegimeProfile(REGIME_SENS_PROFILES.get(i), i, REGIME_SENS_PROFILES.size(),
                    "RegimeSens", symbols, from, to));
        }

        restoreRegimeDefaults();

        Path report = reportWriter.writeRegimeSensReport(
                REGIME_LAB_LABEL, REGIME_LAB_SLUG, symbols, from, to, rows);
        logRegimeSensSummary(rows, labStart, report);
    }

    /** 시작 배너 — 회귀 앵커 2개(S0=G0, S2=G1)와 §4 민감도 기준(PF≥1.15)을 먼저 밝힌다 */
    private void logRegimeSensBanner(List<String> symbols, LocalDate from, LocalDate to) {
        int windowCount = WalkForwardEngine.windows(from, to).size();
        log.info("[RegimeSens] ══ 지수 추세 MA 기간 민감도 (BACKTEST-DESIGN §14.4, MA120 ±20%) ══");
        log.info("[RegimeSens] 고정 조건: 진입=MA 정배열 / 출구=P3 다일 트레일링(ATR1.0·최대20일·arm1%/trail3%)"
                + " / 사이징=0.5R·동시5 (regime-lab과 동일 — MA 기간만 스윕)");
        log.info("[RegimeSens] 기간: {} ~ {} (stress-from/to — regime-lab과 같은 약세장 창)", from, to);
        log.info("[RegimeSens] Walk-Forward 6/3/3 윈도우 {}개 · 종목 {}개", windowCount, symbols.size());
        log.info("[RegimeSens] 프로필: {}", REGIME_SENS_PROFILES.stream().map(RegimeProfile::name).toList());
        log.info("[RegimeSens] 총 실행 예정: 프로필 {} × 윈도우 {} = {}런",
                REGIME_SENS_PROFILES.size(), windowCount, REGIME_SENS_PROFILES.size() * windowCount);
        log.info("[RegimeSens] 회귀 앵커: S0(OFF)=§14.4 G0(트레이드 1591·PF 1.26·MDD 33.5%), "
                + "S2(MA120)=§14.4 G1(트레이드 1192·PF 1.51·MDD 13.3%) — 재현 못 하면 이 실행은 무효");
    }

    /** 종료 요약 — 프로필별 한 줄 표 + §4 민감도 판독 지침(뾰족한 최적점 = 과최적화) */
    private void logRegimeSensSummary(List<BacktestReportWriter.ExitLabRow> rows,
                                      long labStart, Path report) {
        log.info("[RegimeSens] ══ 전체 완료 ({}초) ══", elapsedSec(labStart));
        log.info("[RegimeSens] 프로필 | 트레이드 | PF | 기대값 | MDD | 판정");
        for (BacktestReportWriter.ExitLabRow row : rows) {
            BacktestMetrics m = row.result().aggregateValidation();
            log.info("[RegimeSens]   {} | {}건 | PF {} | 기대값 {}% | MDD {}% | {}",
                    row.profileName(), m.tradeCount(),
                    Double.isInfinite(m.profitFactor()) ? "inf" : String.format("%.2f", m.profitFactor()),
                    String.format("%+.3f", m.expectancyPct() * 100),
                    String.format("%.1f", m.maxDrawdown() * 100),
                    row.judgment().pass() ? "✅ 합격" : "❌ 불합격");
        }
        log.info("[RegimeSens] §4 민감도: S1·S2·S3가 전부 PF≥1.15면 완만(강건). 한 칸만 뾰족하고 "
                + "인접이 무너지면 과최적화 지문 — MDD가 15%를 크게 넘는 인접도 강건성 약화 신호");
        log.info("[RegimeSens] 리포트: {}", report.toAbsolutePath());
    }

    // ── Crash-Vol (BACKLOG 2026-07-24) — 약세장 방어 측정 (리포트 전용) ──────────

    private void runCrashVol(List<String> symbols, LocalDate from, LocalDate to) {
        long start = System.nanoTime();
        log.info("[CrashVol] 사용 창: crash-vol-from/to = {} ~ {} (candidate-from/to {} ~ {} 와 별개 — "
                        + "2020 코로나·2022 금리쇼크를 포함하려고 소급 확장한 전용 창)",
                from, to, properties.getCandidateFrom(), properties.getCandidateTo());
        log.info("[CrashVol] 소급 저장 하한(backfill-from): {} — 이보다 이른 캔들은 DB에 없다",
                properties.getBackfillFrom() != null ? properties.getBackfillFrom()
                        : "미설정(기본 now-years-워밍업)");
        LowVolCrashBacktester.CrashVolReport report =
                lowVolCrashBacktester.compute(symbols, from, to);
        Path file = reportWriter.writeLowVolCrashReport(report);

        log.info("[CrashVol] ══ 완료 ({}초) ══", elapsedSec(start));
        for (LowVolCrashBacktester.WindowStats w : report.windows()) {
            log.info("[CrashVol] ── {} 기준 버킷팅 ({}) ──", w.window().code(), w.window().label());
            log.info("[CrashVol] 호라이즌 | 저변동성 중앙값 | 고변동성 중앙값 | 저−고 차 | 이벤트 n");
            for (LowVolCrashBacktester.HorizonStat h : w.horizons()) {
                log.info("[CrashVol]   D+{} | {} | {} | {} | {}건", h.horizon(),
                        BacktestReportWriter.signedPct(h.lowVol()),
                        BacktestReportWriter.signedPct(h.highVol()),
                        BacktestReportWriter.lowMinusHighText(h), h.events());
            }
        }
        log.info("[CrashVol] 두 표가 다르면 물었던 질문의 답은 PRE 쪽 (DURING은 '이번 급락에서 덜 맞은 정도')");
        log.info("[CrashVol] 리포트: {}", file.toAbsolutePath());
    }

    private static String pct(double rate) {
        return String.format("%.3f%%", rate * 100);
    }

    private static String elapsedSec(long startNanos) {
        return String.format("%.1f", (System.nanoTime() - startNanos) / 1_000_000_000.0);
    }
}
