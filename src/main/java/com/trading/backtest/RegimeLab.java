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
 * Regime Lab (§14.4) — 지수 하락 추세에서 신규 진입을 막으면 휩쏘가 사라지는가.
 *
 * <p>진입=enableEntry, 출구=P3, 사이징=RR1(0.5R·동시5)까지 §14.3 스트레스 실행과 똑같이 고정하고
 * 지수 추세 필터만 켠다. G0(필터 OFF)는 §14.3 RR1을 재현해야 하는 회귀 앵커다 —
 * 재현되지 않으면 G1·G2 비교는 의미가 없다. 프로필은 3개로 제한(§3.3 조합 폭발 방지).
 *
 * <p>민감도({@link #runSensitivity})는 같은 고정 조건에서 승자 MA120의 ±20%(MA96·MA144)만
 * 스윕하는 단일 파라미터 실행이다. S0(필터 OFF)=§14.4 G0, S2(MA120)=§14.4 G1이 회귀 앵커 2개.
 */
@Component
@Profile("backtest")
public class RegimeLab {

    private static final Logger log = LoggerFactory.getLogger(RegimeLab.class);

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

    static final String REGIME_LAB_LABEL = "MA 정배열 + P3 다일 트레일링 + RR1(0.5R·동시5)";
    static final String REGIME_LAB_SLUG = "MA-P3-RR1";

    private final LabExecutor executor;
    private final LabComparisonReportWriter reportWriter;
    private final ExecutionKnobs knobs;
    private final StrategyToggles toggles;
    private final BacktestDataProperties properties;

    public RegimeLab(LabExecutor executor, LabComparisonReportWriter reportWriter,
                     ExecutionKnobs knobs, StrategyToggles toggles,
                     BacktestDataProperties properties) {
        this.executor = executor;
        this.reportWriter = reportWriter;
        this.knobs = knobs;
        this.toggles = toggles;
        this.properties = properties;
    }

    /**
     * 진입을 파라미터화한 Regime Lab — 진입=enableEntry, 출구=P3, 사이징=RR1 고정,
     * 지수 추세 필터 A/B(G0 OFF / G1 MA120 / G2 MA200). maAnchor=true면 MA 재현 배너
     * (§14.3 앵커 문구 포함), false면 신규 후보용 일반 배너.
     */
    public void run(LabScope scope, Runnable enableEntry, boolean maAnchor) {
        enableEntry.run();
        knobs.applyP3ExitWithHalfRisk();

        if (maAnchor) logMaAnchorBanner(scope);
        else          logGenericBanner(scope);
        long labStart = System.nanoTime();
        List<ExitLabRow> rows = new ArrayList<>();
        for (int i = 0; i < REGIME_PROFILES.size(); i++) {
            rows.add(runRegimeProfile(REGIME_PROFILES.get(i), i, REGIME_PROFILES.size(),
                    "RegimeLab", scope));
        }

        knobs.restoreRegimeDefaults();

        Path report = reportWriter.writeRegimeLabReport(scope, rows);
        logLabSummary(rows, labStart, report);
    }

    /**
     * §14.4 민감도 — 진입=MA 정배열, 출구=P3, 사이징=RR1까지 regime-lab과 똑같이 고정하고
     * 지수 추세 필터의 MA 기간만 96/120/144로 스윕한다(단일 파라미터).
     */
    public void runSensitivity(List<String> symbols, LocalDate from, LocalDate to) {
        toggles.enableMaBreakoutOnly();
        knobs.applyP3ExitWithHalfRisk();

        LabScope scope = new LabScope(REGIME_LAB_LABEL, REGIME_LAB_SLUG, symbols, from, to);
        logSensBanner(scope);
        long labStart = System.nanoTime();
        List<ExitLabRow> rows = new ArrayList<>();
        for (int i = 0; i < REGIME_SENS_PROFILES.size(); i++) {
            rows.add(runRegimeProfile(REGIME_SENS_PROFILES.get(i), i, REGIME_SENS_PROFILES.size(),
                    "RegimeSens", scope));
        }

        knobs.restoreRegimeDefaults();

        Path report = reportWriter.writeRegimeSensReport(scope, rows);
        logSensSummary(rows, labStart, report);
    }

    /**
     * 레짐 프로필 1개 실행 — 필터 세팅 → Walk-Forward → §4 판정 (2022 창 진행 로그 포함).
     * {@code tag}/{@code total}은 로그 태그·분모만 바꾼다 — regime-lab은 "RegimeLab"·프로필 3개를,
     * regime-sens는 "RegimeSens"·4개를 넘긴다.
     */
    private ExitLabRow runRegimeProfile(RegimeProfile rp, int index, int total,
                                        String tag, LabScope scope) {
        knobs.applyIndexTrend(rp.trendEnabled(), rp.maPeriod());
        log.info("[{}] ({}/{}) {} 시작 — 실제 적용: 지수 추세 필터 {}",
                tag, index + 1, total, rp.name(),
                knobs.isIndexTrendEnabled()
                        ? "ON (MA" + knobs.indexTrendMaPeriod() + " 이탈 시 신규 매수 차단)"
                        : "OFF");

        long started = System.nanoTime();
        ExitLabRow row = executor.evaluate(rp.name(), scope);

        log.info("[{}] ({}/{}) {} 완료 ({}초): {} → {}",
                tag, index + 1, total, rp.name(), ReportFormat.elapsedSec(started),
                row.result().aggregateValidation().summaryLine(),
                ReportFormat.verdict(row.judgment()));
        if (!row.judgment().pass()) {
            row.judgment().reasons().forEach(r -> log.info("[{}]     불합격 사유: {}", tag, r));
        }
        logCrisisWindows(rp, tag, row.result());
        return row;
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
                    ReportFormat.pf(vm.profitFactor()),
                    String.format("%+.2f", vm.totalReturnPct() * 100), vm.tradeCount());
        }
    }

    /** 시작 배너 — 프로필 3개 × 창 24개면 수십 분이므로 무엇을 몇 번 도는지 먼저 보여준다 */
    private void logMaAnchorBanner(LabScope scope) {
        int windowCount = LabExecutor.windowCount(scope);
        log.info("[RegimeLab] ══ 지수 추세 진입금지 필터 A/B (BACKTEST-DESIGN §14.4) ══");
        log.info("[RegimeLab] 고정 조건: 진입=MA 정배열 / 출구=P3 다일 트레일링(ATR1.0·최대20일·arm1%/trail3%)"
                + " / 사이징=0.5R·동시5");
        log.info("[RegimeLab] 기간: {} ~ {} (stress-from/to — candidate-from/to {} ~ {} 와 별개, "
                        + "2020 코로나·2022 금리쇼크를 포함하는 약세장 창)",
                scope.from(), scope.to(), properties.getCandidateFrom(), properties.getCandidateTo());
        log.info("[RegimeLab] Walk-Forward 6/3/3 윈도우 {}개 · 종목 {}개",
                windowCount, scope.symbols().size());
        log.info("[RegimeLab] 프로필: {}", REGIME_PROFILES.stream().map(RegimeProfile::name).toList());
        log.info("[RegimeLab] 총 실행 예정: 프로필 {} × 윈도우 {} = {}런",
                REGIME_PROFILES.size(), windowCount, REGIME_PROFILES.size() * windowCount);
        log.info("[RegimeLab] G0(필터OFF)는 회귀 앵커 — §14.3 RR1(트레이드 1591·PF 1.26·MDD 33.5%)을 "
                + "재현하지 못하면 이 실행은 무효다");
    }

    /** 신규 후보(Donchian·RSI) 약세장 검증용 일반 배너 — §14.3 MA 앵커 문구 없이 */
    private void logGenericBanner(LabScope scope) {
        int windowCount = LabExecutor.windowCount(scope);
        log.info("[RegimeLab] ══ 지수 추세 진입금지 필터 A/B (BACKTEST-DESIGN §14.4 확장 — 신규 후보) ══");
        log.info("[RegimeLab] 고정 조건: {}", scope.label());
        log.info("[RegimeLab] 기간: {} ~ {} (약세장 창 — 2020 코로나·2022 금리쇼크 포함)",
                scope.from(), scope.to());
        log.info("[RegimeLab] Walk-Forward 6/3/3 윈도우 {}개 · 종목 {}개",
                windowCount, scope.symbols().size());
        log.info("[RegimeLab] 프로필: {}", REGIME_PROFILES.stream().map(RegimeProfile::name).toList());
        log.info("[RegimeLab] 총 실행 예정: 프로필 {} × 윈도우 {} = {}런",
                REGIME_PROFILES.size(), windowCount, REGIME_PROFILES.size() * windowCount);
        log.info("[RegimeLab] G0(필터OFF) 대비 G1(MA120)/G2(MA200)에서 약세장 낙폭이 §4(≤15%) 안으로 "
                + "들어오는지, 상승장 수익까지 잘라내지는 않는지(과필터) 본다");
    }

    /** 종료 요약 — 프로필별 한 줄 표 (리포트 파일을 열지 않아도 판독 가능하게) */
    private void logLabSummary(List<ExitLabRow> rows, long labStart, Path report) {
        log.info("[RegimeLab] ══ 전체 완료 ({}초) ══", ReportFormat.elapsedSec(labStart));
        LabLog.logProfileTable(log, "RegimeLab", rows);
        log.info("[RegimeLab] 트레이드 수가 급감했으면 상승장 수익까지 잘라낸 것 — "
                + "MDD만 보지 말고 PF·기대값·건수를 함께 볼 것(과필터)");
        log.info("[RegimeLab] 리포트: {}", report.toAbsolutePath());
    }

    /** 시작 배너 — 회귀 앵커 2개(S0=G0, S2=G1)와 §4 민감도 기준(PF≥1.15)을 먼저 밝힌다 */
    private void logSensBanner(LabScope scope) {
        int windowCount = LabExecutor.windowCount(scope);
        log.info("[RegimeSens] ══ 지수 추세 MA 기간 민감도 (BACKTEST-DESIGN §14.4, MA120 ±20%) ══");
        log.info("[RegimeSens] 고정 조건: 진입=MA 정배열 / 출구=P3 다일 트레일링(ATR1.0·최대20일·arm1%/trail3%)"
                + " / 사이징=0.5R·동시5 (regime-lab과 동일 — MA 기간만 스윕)");
        log.info("[RegimeSens] 기간: {} ~ {} (stress-from/to — regime-lab과 같은 약세장 창)",
                scope.from(), scope.to());
        log.info("[RegimeSens] Walk-Forward 6/3/3 윈도우 {}개 · 종목 {}개",
                windowCount, scope.symbols().size());
        log.info("[RegimeSens] 프로필: {}", REGIME_SENS_PROFILES.stream().map(RegimeProfile::name).toList());
        log.info("[RegimeSens] 총 실행 예정: 프로필 {} × 윈도우 {} = {}런",
                REGIME_SENS_PROFILES.size(), windowCount, REGIME_SENS_PROFILES.size() * windowCount);
        log.info("[RegimeSens] 회귀 앵커: S0(OFF)=§14.4 G0(트레이드 1591·PF 1.26·MDD 33.5%), "
                + "S2(MA120)=§14.4 G1(트레이드 1192·PF 1.51·MDD 13.3%) — 재현 못 하면 이 실행은 무효");
    }

    /** 종료 요약 — 프로필별 한 줄 표 + §4 민감도 판독 지침(뾰족한 최적점 = 과최적화) */
    private void logSensSummary(List<ExitLabRow> rows, long labStart, Path report) {
        log.info("[RegimeSens] ══ 전체 완료 ({}초) ══", ReportFormat.elapsedSec(labStart));
        LabLog.logProfileTable(log, "RegimeSens", rows);
        log.info("[RegimeSens] §4 민감도: S1·S2·S3가 전부 PF≥1.15면 완만(강건). 한 칸만 뾰족하고 "
                + "인접이 무너지면 과최적화 지문 — MDD가 15%를 크게 넘는 인접도 강건성 약화 신호");
        log.info("[RegimeSens] 리포트: {}", report.toAbsolutePath());
    }
}
