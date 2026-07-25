package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * B-3 결과 리포트 + 거버넌스 기준선 파일 (설계 문서 §4).
 *
 * 리포트: logs/backtest/REPORT-{일시}.md (gitignore 대상 — 실행 산출물)
 * 기준선: 합격 시 docs/BACKTEST-BASELINE.yml (버전 관리 — 거버넌스 강등 판정의 분모)
 */
@Component
@Profile("backtest")
public class BacktestReportWriter {

    private static final Logger log = LoggerFactory.getLogger(BacktestReportWriter.class);

    /** 합격 기준 (설계 문서 §4) */
    static final int    MIN_TRADES        = 100;
    static final double MIN_PF            = 1.3;
    static final double MAX_MDD           = 0.15;
    static final double MIN_ADJACENT_PF   = 1.15;

    /** 비교 리포트 헤더의 비용 문구 기본값 — 비용 스윕(cost-lab)이 아닌 모드는 왕복 0.41% 고정 */
    private static final String DEFAULT_COST_LINE =
            "- 비용 모델: 왕복 ≈0.41%. 합격 기준(§4): 트레이드≥100·PF≥1.3·기대값>0·MDD≤15%";

    public record FilterVariant(String name, WalkForwardEngine.WalkForwardResult result,
                                boolean adopted, String verdictNote) {}

    /**
     * strategyLabel/fileSlug — BACKTEST-DESIGN §13(방식2·3 소급 검증)에서 VB 외 전략도
     * 같은 리포트 포맷을 재사용하기 위한 확장. 6-인자 생성자는 VB 기존 호출부 호환용으로
     * strategyLabel="변동성 돌파(VB)", fileSlug=""(파일명 접두사 없음, 기존 REPORT-*.md 유지)를 채운다.
     */
    public record ReportData(List<String> symbols, LocalDate from, LocalDate to,
                             Map<Double, BacktestMetrics> kSensitivity,
                             WalkForwardEngine.WalkForwardResult baseline,
                             List<FilterVariant> filterVariants,
                             String strategyLabel, String fileSlug) {

        public ReportData(List<String> symbols, LocalDate from, LocalDate to,
                          Map<Double, BacktestMetrics> kSensitivity,
                          WalkForwardEngine.WalkForwardResult baseline,
                          List<FilterVariant> filterVariants) {
            this(symbols, from, to, kSensitivity, baseline, filterVariants, "변동성 돌파(VB)", "");
        }
    }

    public record Judgment(boolean pass, List<String> reasons) {}

    /**
     * 그 런에 <b>실제로 엔진에 걸린</b> 거래비용 (cost-lab 전용, 다른 랩은 null).
     *
     * <p>프로필 라벨("C2 0.60%")은 사람이 적은 <b>의도값</b>일 뿐이다. 역산 수식이 틀리면
     * 라벨은 그대로인 채 엔진에는 다른 비용이 걸리고, 리포트는 아무 말도 하지 않는다(F-C1).
     * 그래서 이 값은 반드시 세팅 <b>직후 홀더에서 다시 읽어</b> 채운다 — 목표값을 그대로
     * 옮겨 적으면 검증 능력이 0이 된다.
     */
    public record AppliedCost(double slippageRate, double roundTripCost) {}

    /** Exit Lab(§14) 출구 프로필 1개의 결과 — 프로필명 + Walk-Forward 검증 결과 + §4 판정 */
    public record ExitLabRow(String profileName,
                             WalkForwardEngine.WalkForwardResult result,
                             Judgment judgment,
                             AppliedCost appliedCost) {

        /** 비용 스윕이 아닌 랩(exit-lab/risk-lab)용 — 적용 비용은 기본값 고정이라 표기하지 않는다 */
        public ExitLabRow(String profileName, WalkForwardEngine.WalkForwardResult result,
                          Judgment judgment) {
            this(profileName, result, judgment, null);
        }
    }

    /** §4 합격 판정 — 검증 구간 집계 + 전기간 K 민감도 */
    public Judgment judge(ReportData data) {
        BacktestMetrics v = data.baseline().aggregateValidation();
        List<String> reasons = new java.util.ArrayList<>();

        if (v.tradeCount() < MIN_TRADES) {
            reasons.add(String.format("검증 트레이드 %d건 < %d (소표본)", v.tradeCount(), MIN_TRADES));
        }
        if (v.profitFactor() < MIN_PF) {
            reasons.add(String.format("검증 PF %.2f < %.1f", v.profitFactor(), MIN_PF));
        }
        if (v.expectancyPct() <= 0) {
            reasons.add(String.format("검증 기대값 %.3f%% ≤ 0", v.expectancyPct() * 100));
        }
        if (v.maxDrawdown() > MAX_MDD) {
            reasons.add(String.format("검증 MDD %.1f%% > %.0f%%", v.maxDrawdown() * 100, MAX_MDD * 100));
        }
        data.kSensitivity().forEach((k, m) -> {
            if (m.profitFactor() < MIN_ADJACENT_PF) {
                reasons.add(String.format("K=%.1f 전기간 PF %.2f < %.2f (민감도)", k,
                        m.profitFactor(), MIN_ADJACENT_PF));
            }
        });
        return new Judgment(reasons.isEmpty(), reasons);
    }

    public Path writeReport(ReportData data, Judgment judgment) {
        StringBuilder md = new StringBuilder();
        md.append("# 백테스트 리포트 (B-3) — ").append(data.strategyLabel()).append(" 소급 검증\n\n");
        md.append(String.format("- 실행: %s%n- 기간: %s ~ %s%n- 종목: %s%n- 초기 자본: 10,000,000원%n",
                LocalDateTime.now(), data.from(), data.to(), String.join(", ", data.symbols())));
        md.append("- 비용 모델: 슬리피지 0.1%×2 + 수수료 0.015%×2 + 매도 제세 0.18% (왕복 ≈0.41%)\n\n");

        md.append("## 판정: ").append(judgment.pass() ? "✅ 합격" : "❌ 불합격").append("\n\n");
        judgment.reasons().forEach(r -> md.append("- ").append(r).append('\n'));
        md.append('\n');

        if (data.kSensitivity().isEmpty()) {
            md.append("## 1. 파라미터 민감도 — 해당 없음 (이 전략에는 K 같은 단일 튜닝 파라미터가 없다)\n\n");
        } else {
            md.append("## 1. K 민감도 (전기간, 참고용 — 채점은 Walk-Forward 검증 구간)\n\n");
            md.append("| K | 트레이드 | 승률 | PF | 기대값 | MDD | 수익률 |\n|---|---|---|---|---|---|---|\n");
            data.kSensitivity().forEach((k, m) -> md.append(metricsRow(String.format("%.1f", k), m)));
            md.append('\n');
        }

        md.append("## 2. Walk-Forward (학습 6개월 / 검증 3개월 / 3개월 롤링)\n\n");
        md.append("검증 구간 집계: **").append(data.baseline().aggregateValidation().summaryLine()).append("**\n\n");
        md.append("| 윈도우 | 학습 | 검증 | 선택 K | 검증 PF | 검증 MDD | 검증 트레이드 |\n|---|---|---|---|---|---|---|\n");
        List<WalkForwardEngine.WindowResult> ws = data.baseline().windows();
        for (int i = 0; i < ws.size(); i++) {
            WalkForwardEngine.WindowResult w = ws.get(i);
            md.append(String.format("| %d | %s~%s | %s~%s | %.1f | %s | %.1f%% | %d |%n",
                    i, w.window().trainFrom(), w.window().trainTo(),
                    w.window().validateFrom(), w.window().validateTo(), w.chosenK(),
                    pfText(w.validateMetrics().profitFactor()),
                    w.validateMetrics().maxDrawdown() * 100,
                    w.validateMetrics().tradeCount()));
        }
        md.append('\n');

        md.append("## 3. 변형 비교 (검증 구간 — PF·MDD 동반 우위일 때만 채택)\n\n");
        md.append("| 변형 | 트레이드 | PF | MDD | 판정 |\n|---|---|---|---|---|\n");
        BacktestMetrics base = data.baseline().aggregateValidation();
        md.append(String.format("| (순정) | %d | %s | %.1f%% | 기준선 |%n",
                base.tradeCount(), pfText(base.profitFactor()), base.maxDrawdown() * 100));
        for (FilterVariant fv : data.filterVariants()) {
            BacktestMetrics m = fv.result().aggregateValidation();
            md.append(String.format("| %s | %d | %s | %.1f%% | %s |%n",
                    fv.name(), m.tradeCount(), pfText(m.profitFactor()),
                    m.maxDrawdown() * 100, fv.verdictNote()));
        }
        if (data.fileSlug().isEmpty()) {
            md.append("\n※ 진입 시간창·거래량 필터는 일봉으로 돌파 시각을 알 수 없어 검증 유보 —\n");
            md.append("   분봉 축적(MinuteCandleCollector, 매일 15:40) 후 별도 세션에서 A/B한다.\n\n");
        } else {
            md.append('\n');
        }

        md.append("## 4. 방법 한계 (일봉 근사 v1 — 보수 방향)\n\n");
        md.append("- 진입 체크포인트 10:00 고정 (돌파 시각 불명 — MarketCloseRule 등 시간 룰 근사)\n");
        md.append("- 진입가 = 이분탐색 복원 돌파가 + 슬리피지 0.1% (분봉 다음 봉 시가 대신)\n");
        md.append("- 당일 진입 후 저가 ≤ 손절가면 손절 체결로 간주 (저가가 진입 전이었을 가능성 무시 — 비관)\n");
        md.append("- 타임컷 15:15 체결가 = 당일 종가 근사\n");
        md.append("- 생존 편향: 현 유니버스(대형주)는 영향 작음 — 유니버스 확대 시 재평가 (§2.2)\n");

        try {
            Path dir = Path.of("logs", "backtest");
            Files.createDirectories(dir);
            String prefix = data.fileSlug().isEmpty() ? "REPORT-" : "REPORT-" + data.fileSlug() + "-";
            Path file = dir.resolve(prefix + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
            Files.writeString(file, md.toString());
            log.info("[Report] 리포트 저장: {}", file.toAbsolutePath());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("리포트 저장 실패", e);
        }
    }

    /** 합격 시에만 호출 — 거버넌스가 소비하는 기준선 (버전 관리 대상) */
    public Path writeBaseline(ReportData data, List<FilterVariant> adoptedFilters) {
        BacktestMetrics v = data.baseline().aggregateValidation();
        StringBuilder yml = new StringBuilder();
        yml.append("# 백테스트 기준선 (B-3) — 거버넌스 강등 판정의 분모 (PERFORMANCE-GOVERNANCE §2)\n");
        yml.append("# 자동 생성: BacktestReportWriter — 수동 편집 금지\n");
        yml.append(String.format("generated-at: %s%n", LocalDate.now()));
        yml.append(String.format("period: %s ~ %s%n", data.from(), data.to()));
        yml.append("method: daily-bar-approximation-v1\n");
        yml.append("validation:   # Walk-Forward 검증 구간 집계 (비용 차감 후)\n");
        yml.append(String.format("  trades: %d%n", v.tradeCount()));
        yml.append(String.format("  profit-factor: %.3f%n", v.profitFactor()));
        yml.append(String.format("  expectancy-pct: %.4f%n", v.expectancyPct()));
        yml.append(String.format("  max-drawdown: %.4f%n", v.maxDrawdown()));
        yml.append(String.format("  win-rate: %.4f%n", v.winRate()));
        yml.append(String.format("chosen-k-per-window: %s%n", data.baseline().chosenKs()));
        yml.append(String.format("adopted-filters: %s%n",
                adoptedFilters.stream().map(FilterVariant::name).toList()));

        try {
            String filename = data.fileSlug().isEmpty()
                    ? "BACKTEST-BASELINE.yml" : "BACKTEST-BASELINE-" + data.fileSlug() + ".yml";
            Path file = Path.of("docs", filename);
            Files.writeString(file, yml.toString());
            log.info("[Report] 기준선 저장: {}", file.toAbsolutePath());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("기준선 저장 실패", e);
        }
    }

    /**
     * Exit Lab(§14) 출구 프로필 비교 — 진입 고정, 출구만 스윕. 거버넌스 기준선은 쓰지 않는다
     * (합격은 §4 임계선 통과일 뿐, 낙관 편향·레짐 리스크가 남아 검증된 기준선이 아니다).
     */
    public Path writeExitLabReport(String strategyLabel, String fileSlug, List<String> symbols,
                                   LocalDate from, LocalDate to, List<ExitLabRow> rows) {
        return writeComparison("Exit Lab — 출구 프로필 비교", "EXITLAB",
                "- 진입 로직 고정(VB는 K=0.5). 출구(ATR 손절배수·15:15 타임컷·트레일링·최대보유일)만 스윕",
                DEFAULT_COST_LINE,
                strategyLabel, fileSlug, symbols, from, to, rows,
                List.of(
                        "- **손익비(payoff)**: 평균이익÷평균손실. 1 미만이면 손익비가 거꾸로(작게 벌고 크게 잃음).",
                        "  당일 프로필(P0·P1)은 <1, 다일 프로필(P2~P4)에서 개선되면 '이익을 태우는' 재설계가 작동한 것.",
                        "- **평균보유일**: 다일 프로필(P2~P4)이 1보다 크면 실제로 다음 날로 이월됐다는 뜻.",
                        "- 합격해도 **실전 승격은 ADR-001(Sleeve A 보류) 재논의 선행** — 이 리포트는 측정까지."),
                false, null);
    }

    /**
     * Risk Lab(§14) 리스크 축소 프로필 비교 — 진입·출구 고정, 사이징(1R 비율)·동시보유 한도만
     * 스윕. 검증된 엣지(양의 기대값)를 MDD 기준(≤15%) 안으로 넣는 게 목표. 사이징 축소는
     * 손익비·기대값을 보존한 채 MDD만 낮춘다. 합격 시 그 프로필로 거버넌스 기준선 yml을 쓴다
     * (낙관 편향 수정 + 넓은 유니버스 재현을 통과한 후보이므로).
     */
    public Path writeRiskLabReport(String strategyLabel, String fileSlug, List<String> symbols,
                                   LocalDate from, LocalDate to, List<ExitLabRow> rows,
                                   boolean writeBaseline) {
        return writeComparison("Risk Lab — 리스크 축소 프로필 비교", "RISKLAB",
                "- 진입(MA 정배열)·출구(다일 트레일링 P3) 고정. 사이징(1R 비율)·동시보유 종목수만 스윕",
                DEFAULT_COST_LINE,
                strategyLabel, fileSlug, symbols, from, to, rows,
                List.of(
                        "- **막힌 곳은 기대값이 아니라 MDD였다** — 사이징 축소·동시보유 상한은 손익비(기대값)를",
                        "  보존한 채 MDD를 직접 낮춘다. '기준 완화'가 아니라 리스크로 기준을 충족시키는 것.",
                        "- 합격해도 **실전 승격은 ADR-001(다일 보유) 재논의 + 게이트 G2(사람) 선행** —",
                        "  남은 검증: 왕복 비용 상향 민감도, 분봉 정밀 재검증."),
                writeBaseline, "EXITLAB-" + fileSlug);
    }

    /**
     * Cost Lab(§14.1) 왕복 비용 상향 민감도 — 진입·출구·사이징 고정, 거래비용만 스윕.
     * 검증된 후보(MA 정배열 + P3 다일 트레일링 + RR1 0.5R·동시5)가 비용이 나빠져도
     * §4 기준을 지키는지 보는 <b>스트레스 테스트</b>다.
     *
     * <p>거버넌스 기준선 yml은 쓰지 않는다({@code baselineOnPass=false}) — 기준선은 이미
     * §14.1의 0.41% 실행(BACKTEST-BASELINE-EXITLAB-MA-P3.yml)이 갖고 있고, 비용을 부풀린
     * 스트레스 실행 결과로 그 분모를 덮어쓰면 거버넌스 판정이 왜곡된다.
     */
    public Path writeCostLabReport(String strategyLabel, String fileSlug, List<String> symbols,
                                   LocalDate from, LocalDate to, List<ExitLabRow> rows) {
        return writeComparison("Cost Lab — 왕복 비용 상향 민감도", "COSTLAB",
                "- 진입(MA 정배열)·출구(다일 트레일링 P3)·사이징(0.5R·동시5) 고정. 왕복 거래비용만 스윕",
                "- 비용 모델: 왕복 0.41%~0.80% 스윕. 합격 기준(§4): 트레이드≥100·PF≥1.3·기대값>0·MDD≤15%",
                strategyLabel, fileSlug, symbols, from, to, rows,
                List.of(
                        "- **라벨(C0~C4)은 '이렇게 걸고 싶다'는 의도값이고, 옆의 `편도 슬리피지`·`실제 왕복`",
                        "  두 칸이 엔진에 진짜로 걸린 값이다.** 둘이 어긋나면(예: C2인데 실제 왕복이 0.99%)",
                        "  비용 역산이 깨진 것이므로 **이 실행 전체를 신뢰하지 말 것** — 숫자만 보면 알 수 없다.",
                        "- **비용은 슬리피지(체결 미끄러짐)만 올려서 만들었다** — 위탁수수료·매도 제세금은",
                        "  법으로 정해진 확정값이라 시장이 나빠져도 변하지 않는다. 불확실한 조각만 부풀리는 게 정직하다.",
                        "- **C0(0.41%)는 회귀 앵커다** — §14.1 RR1 결과(트레이드 780·PF 1.96·MDD 9.9%)를",
                        "  재현하지 못하면 이 실행 전체가 **무효**다(엔진이 중간에 바뀌었다는 뜻). 먼저 C0부터 확인할 것.",
                        "- **버티는 한계**: 비용을 올려도 PF≥1.3·기대값>0을 유지하는 가장 높은 비용이,",
                        "  이 후보가 실제로 견디는 마진이다. 실전 슬리피지가 그보다 크면 엣지는 사라진다.",
                        "- 합격해도 **실전 승격은 아니다** — ADR-001(다일 보유) 재논의 + 게이트 G2(사람) 선행."),
                false, null);
    }

    /**
     * Regime Lab(§14.4) 지수 추세 필터 A/B — 진입·출구·사이징 고정, "지수가 장기 이동평균
     * 아래면 신규 진입 금지"만 켜고 끈다. §14.3에서 후보를 무너뜨린 2022 하락 추세 휩쏘가
     * 이 필터로 제거되는지 <b>측정</b>하는 실행이다(채택 아님).
     *
     * <p>거버넌스 기준선 yml은 쓰지 않는다({@code baselineOnPass=false}) — 이 랩은 A/B 측정이고,
     * 합격 프로필이 나와도 실전 승격은 ADR-001 재논의 + 게이트 G2(사람)가 선행한다.
     */
    public Path writeRegimeLabReport(String strategyLabel, String fileSlug, List<String> symbols,
                                     LocalDate from, LocalDate to, List<ExitLabRow> rows) {
        return writeComparison("Regime Lab — 지수 추세 진입금지 필터 A/B", "REGIMELAB",
                "- 진입(MA 정배열)·출구(다일 트레일링 P3)·사이징(0.5R·동시5) 고정. "
                        + "지수 장기추세 이탈 시 신규 진입금지 필터만 스윕",
                DEFAULT_COST_LINE,
                strategyLabel, fileSlug, symbols, from, to, rows,
                List.of(
                        "- **G0(필터 OFF)가 회귀 앵커다.** §14.3 스트레스 실행의 RR1",
                        "  (트레이드 **1591** · PF **1.26** · MDD **33.5%**)을 재현하지 못하면",
                        "  **이 실행 전체가 무효**다 — 엔진이 중간에 바뀌었다는 뜻이므로 G1·G2 숫자를 읽지 말 것.",
                        "- **핵심 판정 칸은 MDD와 2022 창들의 수익률**이다. 필터가 진짜로 들으면",
                        "  2022 Q1~Q4의 4연속 손실(§14.3 합성 -30.0%)이 끊겨야 한다. 창별 수치는",
                        "  실행 로그의 `[RegimeLab] 2022 창` / `[WalkForward]` 줄에 있다",
                        "  (이 표는 불합격 프로필의 창별 표를 싣지 않는다).",
                        "- **과필터 경고**: 트레이드 수가 급감하면 하락장 휩쏘만이 아니라 상승장 수익까지",
                        "  잘라낸 것이다. MDD만 보지 말고 **PF·기대값·트레이드 수를 함께** 볼 것 —",
                        "  MDD가 내려가도 기대값이 무너지면 그건 '안전해진 것'이 아니라 '안 하는 것'이다.",
                        "- 지수 추세 판정은 **전일까지의 종가만** 쓴다(당일 종가·당일 MA 미사용 — 선견편향 차단).",
                        "  이동평균 표본이 모자란 초기 구간은 '판단 불가'로 아무것도 막지 않는다(보수적).",
                        "- 합격해도 **실전 아님** — ADR-001(다일 보유) 재논의 + 게이트 G2(사람) 선행."),
                false, null);
    }

    /**
     * Regime Sens(§14.4 민감도) — 승자 MA120의 ±20%(MA96·MA144)에서도 성과가 완만한지 본다.
     * 진입·출구·사이징을 §14.4 regime-lab과 똑같이 고정하고 <b>지수 추세 MA 기간만</b> 스윕하는
     * 단일 파라미터 민감도 실행이다(채택 아님). §4 5번째 기준(인접 파라미터 PF≥1.15 유지) 검사용.
     *
     * <p>거버넌스 기준선 yml은 쓰지 않는다({@code baselineOnPass=false}) — regime-lab과 동일하게
     * 실전 승격은 ADR-001 재논의 + 게이트 G2(사람)가 선행한다.
     */
    public Path writeRegimeSensReport(String strategyLabel, String fileSlug, List<String> symbols,
                                      LocalDate from, LocalDate to, List<ExitLabRow> rows) {
        return writeComparison("Regime Sens — 지수 추세 MA 기간 민감도 (MA120 ±20%)", "REGIMESENS",
                "- 진입(MA 정배열)·출구(다일 트레일링 P3)·사이징(0.5R·동시5) 고정. "
                        + "지수 추세 필터의 MA 기간(96/120/144)만 스윕",
                DEFAULT_COST_LINE,
                strategyLabel, fileSlug, symbols, from, to, rows,
                List.of(
                        "- **회귀 앵커 2개를 먼저 확인하라.** S0(필터 OFF)가 §14.4 G0",
                        "  (트레이드 **1591** · PF **1.26** · MDD **33.5%**)을, S2(MA120)가 §14.4 G1",
                        "  (트레이드 **1192** · PF **1.51** · MDD **13.3%**)을 재현하지 못하면",
                        "  **이 실행 전체가 무효**다 — 엔진이 중간에 바뀌었다는 뜻이므로 S1·S3 숫자를 읽지 말 것.",
                        "- **§4 민감도 기준: S1·S2·S3가 전부 PF ≥ 1.15를 유지**해야 한다. 한 칸만",
                        "  뾰족하게 높고 인접이 무너지면(급변) 그건 강건성이 아니라 **과최적화 지문**이다.",
                        "- **MDD도 함께 보라** — PF는 완만한데 MA96/MA144에서 MDD가 15%를 크게 넘으면",
                        "  필터 강건성이 약한 것이다(승자 MA120만 우연히 안전했을 수 있다).",
                        "- 지수 추세 판정은 **전일까지의 종가만** 쓴다(선견편향 차단). 이동평균 표본이",
                        "  모자란 초기 구간은 '판단 불가'로 아무것도 막지 않는다(보수적).",
                        "- 합격해도 **실전 아님** — ADR-001(다일 보유) 재논의 + 게이트 G2(사람) 선행."),
                false, null);
    }

    /**
     * 약세장 방어 측정(BACKLOG 2026-07-24 탐색) — 급락 직후 저변동성 종목의 전방수익률.
     * 거버넌스 기준선 yml은 쓰지 않는다(측정 전용). 표본은 이벤트 단위로 접힌 상태
     * (n = 급락 이벤트 수, 종목 수 아님 — LowVolCrashBacktester 참고).
     */
    public Path writeLowVolCrashReport(LowVolCrashBacktester.CrashVolReport report) {
        StringBuilder md = new StringBuilder();
        md.append("# 약세장 방어 측정 — 급락 직후 저변동성 종목의 전방수익률\n\n");
        md.append("측정 실험(전략 채택 아님). 거버넌스 기준선·레지스트리에 아무것도 기록하지 않는다.\n\n");
        md.append(String.format("- 실행: %s%n- 기간: %s ~ %s%n- 유니버스: %d종목%n",
                LocalDateTime.now(), report.from(), report.to(), report.universeSize()));
        md.append(String.format("- 급락 정의: 트레일링 10거래일 KOSPI 수익률 ≤ %.0f%% · "
                        + "쿨다운 %d거래일(≥최대 호라이즌 D+%d — 앵커 전방창 중첩 제거) · "
                        + "버킷당 최소 %d종목%n",
                report.threshold() * 100, report.cooldownDays(),
                LowVolCrashBacktester.MAX_HORIZON, report.minCoverage()));
        for (LowVolCrashBacktester.WindowStats w : report.windows()) {
            md.append(String.format("- 변동성 창 **%s**: %s%n", w.window().code(), w.window().label()));
        }

        md.append("\n## 급락 임계치별 이벤트 개수 (검정력 정직성)\n\n");
        md.append("| 임계치 | 앵커 이벤트 수 |\n|---|---|\n");
        report.thresholdCounts().forEach((t, c) ->
                md.append(String.format("| %.0f%% | %d |%n", t * 100, c)));
        md.append(String.format("%n본 통계는 임계 %.0f%% (앵커 %d건)로 낸다.%n%n",
                report.threshold() * 100, report.anchorCount()));

        md.append("## 전방수익률 — 이벤트당 버킷 중앙값을 이벤트 축으로 집계\n\n");
        md.append("각 급락 이벤트에서 종목을 저/고 변동성으로 갈라 이벤트당 중앙값 하나로 접은 뒤, ")
          .append("그 이벤트 중앙값들의 중앙값(median)을 표시한다 (n = 이벤트 수).\n");
        md.append("**변동성 창 정의를 바꿔 두 번 잰다** — 같은 앵커·같은 전방수익률에 ")
          .append("버킷팅 기준만 다르다. `-`는 표본이 없어 측정 못 함(0%가 아님).\n\n");
        for (LowVolCrashBacktester.WindowStats w : report.windows()) {
            md.append(String.format("### %s 기준 버킷팅 — %s%n%n", w.window().code(), w.window().label()));
            md.append("| 호라이즌 | 저변동성 중앙값 | 고변동성 중앙값 | KOSPI | 저−고 차 | 이벤트 n | (참고)중 |\n");
            md.append("|---|---|---|---|---|---|---|\n");
            for (LowVolCrashBacktester.HorizonStat h : w.horizons()) {
                md.append(String.format("| D+%d | %s | %s | %s | %s | %d | %s |%n",
                        h.horizon(), signedPct(h.lowVol()), signedPct(h.highVol()),
                        signedPct(h.kospi()), lowMinusHighText(h),
                        h.events(), signedPct(h.midVol())));
            }
            md.append('\n');
        }

        md.append("## 판독 지침 (정직하게)\n\n");
        md.append("- ⓪ **두 표가 서로 다르면, 물었던 질문의 답은 PRE 표다.** PRE는 ")
          .append("'급락 **전까지** 조용했던 종목'을, DURING은 '이번 급락에서 **덜 맞은** 종목'을 ")
          .append("갈라낸다. DURING만 보면 되돌림(덜 맞은 종목이 되돌아오는 것)을 ")
          .append("'저변동성 효과'로 오독하게 된다. 두 표가 비슷하면 두 정의가 실무상 같다는 뜻.\n");
        md.append("- ① **덜 다침**: 단기(D+5/10)에서 저변동성 초과수익≥0 & 지수(KOSPI)가 음수로 읽힌다.\n");
        md.append("- ② **급등**: 장기(D+20/60)에서 저변동성 중앙값이 크고 **고변동성보다 커야** 성립. ")
          .append("역사적으로 최대 반등은 고변동성에서 나오는 경향이라 이 칸(저−고 차)이 핵심 검증 포인트다.\n");
        md.append("- ③ **생존편향**: 유니버스는 오늘 살아있는 대형주라 '저변동성인데 망한 종목'이 빠져 ")
          .append("저변동성에 유리하게 편향될 수 있다.\n");
        md.append("- ④ **이벤트 수가 적으면 통계적 결론 금지** — 방향 참고만. ")
          .append("2023~2026은 대체로 우호장이고, 게다가 쿨다운을 최대 호라이즌(60거래일)까지 올려 ")
          .append("앵커를 더 솎아냈다(전방창이 겹치면 n이 유효표본을 부풀리기 때문). ")
          .append("이벤트가 적게 나오는 건 이 창과 이 정의의 한계지 결함이 아니다 — 낮은 검정력을 숨기지 않는다.\n");
        md.append("- ⑤ 일봉 근사 한계 — 장중 경로·정확한 진입시각은 무시한다.\n");
        md.append("- ⑥ PRE 표는 앵커 -30거래일 이력이 필요해, 이력이 짧은 종목이 그 이벤트의 ")
          .append("PRE 버킷에서만 빠진다(DURING 표는 기존대로). 두 표의 이벤트 n이 다를 수 있는 이유다.\n");

        try {
            Path dir = Path.of("logs", "backtest");
            Files.createDirectories(dir);
            Path file = dir.resolve("REPORT-CRASHVOL-" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
            Files.writeString(file, md.toString());
            log.info("[Report] 약세장 방어 측정 리포트 저장: {}", file.toAbsolutePath());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("약세장 방어 측정 리포트 저장 실패", e);
        }
    }

    /**
     * 부호 있는 백분율 — 빈 표본(n=0)이면 {@code "-"}. 표본이 없는 칸을 {@code +0.00%}로 찍으면
     * "측정했는데 차이가 없다"로 오독된다(측정 못 함과 차이 0은 다르다).
     */
    static String signedPct(EventStatsBacktester.Quantiles q) {
        return q.n() == 0 ? "-" : signedPct(q.median());
    }

    /** 저−고 차 — 두 버킷이 함께 있는 이벤트가 0건이면 {@code "-"} */
    static String lowMinusHighText(LowVolCrashBacktester.HorizonStat h) {
        return h.events() == 0 ? "-" : signedPct(h.lowMinusHigh());
    }

    /** 부호 있는 백분율 */
    private static String signedPct(double ratio) {
        return String.format("%+.2f%%", ratio * 100);
    }

    /** Exit/Risk/Cost Lab 공통 프로필 비교 리포트 본문 (손익비·평균보유일·윈도우별 레짐 포함) */
    private Path writeComparison(String titlePhrase, String filePrefix, String sweepDescLine,
                                 String costModelLine,
                                 String strategyLabel, String fileSlug, List<String> symbols,
                                 LocalDate from, LocalDate to, List<ExitLabRow> rows,
                                 List<String> readingGuide, boolean baselineOnPass, String baselineSlug) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(titlePhrase).append(" (§14) — ").append(strategyLabel).append("\n\n");
        md.append(String.format("- 실행: %s%n- 기간: %s ~ %s%n- 종목: %s%n- 초기 자본: 10,000,000원%n",
                LocalDateTime.now(), from, to, String.join(", ", symbols)));
        md.append(sweepDescLine).append('\n');
        md.append(costModelLine).append("\n\n");

        List<String> passed = rows.stream().filter(r -> r.judgment().pass())
                .map(ExitLabRow::profileName).toList();
        md.append("## 판정 요약: ").append(passed.isEmpty()
                ? "❌ 합격 프로필 없음" : "✅ 합격 프로필 " + passed).append("\n\n");

        // 적용 비용 컬럼은 비용 스윕(cost-lab)에서만 나온다 — 다른 랩은 컬럼 자체가 없어
        // 기존 리포트 출력이 글자 단위로 유지된다.
        boolean showApplied = rows.stream().anyMatch(r -> r.appliedCost() != null);
        md.append("| 프로필 |").append(showApplied ? " 편도 슬리피지 | 실제 왕복 |" : "")
                .append(" 트레이드 | PF | 손익비 | 기대값 | MDD | 평균보유일 | 판정 |\n");
        md.append("|---|---|---|---|---|---|---|---|")
                .append(showApplied ? "---|---|" : "").append('\n');
        for (ExitLabRow row : rows) {
            BacktestMetrics m = row.result().aggregateValidation();
            md.append(String.format("| %s |%s %d | %s | %s | %.3f%% | %.1f%% | %.1f일 | %s |%n",
                    row.profileName(), appliedCostCells(row, showApplied),
                    m.tradeCount(), pfText(m.profitFactor()),
                    pfText(m.payoffRatio()), m.expectancyPct() * 100, m.maxDrawdown() * 100,
                    m.avgHoldDays(), row.judgment().pass() ? "✅ 합격" : "❌ 불합격"));
        }
        md.append('\n');

        md.append("## 프로필별 불합격 사유\n\n");
        for (ExitLabRow row : rows) {
            if (row.judgment().pass()) continue;
            md.append("- **").append(row.profileName()).append("**: ")
                    .append(String.join(", ", row.judgment().reasons())).append('\n');
        }
        md.append('\n');

        // 레짐 일관성 — 합격 프로필의 Walk-Forward 윈도우별 검증 성적 (상승장 편중 판독용)
        List<ExitLabRow> passers = rows.stream().filter(r -> r.judgment().pass()).toList();
        if (!passers.isEmpty()) {
            md.append("## 윈도우별(레짐) 일관성 — 합격 프로필\n\n");
            md.append("전 구간 고르게 이기면 엣지, 특정 상승 구간에만 몰리면 레짐(베타) 의심.\n\n");
            for (ExitLabRow row : passers) {
                md.append("**").append(row.profileName()).append("**\n\n");
                md.append("| 윈도우(검증) | PF | 수익률 | 트레이드 |\n|---|---|---|---|\n");
                for (WalkForwardEngine.WindowResult w : row.result().windows()) {
                    BacktestMetrics vm = w.validateMetrics();
                    md.append(String.format("| %s~%s | %s | %+.1f%% | %d |%n",
                            w.window().validateFrom(), w.window().validateTo(),
                            pfText(vm.profitFactor()), vm.totalReturnPct() * 100, vm.tradeCount()));
                }
                md.append('\n');
            }
        }

        md.append("## 판독 지침\n\n");
        for (String line : readingGuide) md.append(line).append('\n');

        Path file;
        try {
            Path dir = Path.of("logs", "backtest");
            Files.createDirectories(dir);
            file = dir.resolve("REPORT-" + filePrefix + "-" + fileSlug + "-" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
            Files.writeString(file, md.toString());
            log.info("[Report] {} 저장: {}", titlePhrase, file.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException(titlePhrase + " 저장 실패", e);
        }

        // 합격 프로필이 있으면(risk-lab만) 첫 합격 프로필로 거버넌스 기준선 yml 기록.
        // baselineOnPass는 이제 opt-in(--backtest.write-baseline=true)일 때만 true — 대조/점검
        // 실행이 기준선을 조용히 덮어쓰던 사고(2026-07-23) 차단.
        if (baselineOnPass) {
            passers.stream().findFirst().ifPresent(win -> {
                log.info("[Report] 기준선 대상 프로필: {}", win.profileName());
                ReportData data = new ReportData(symbols, from, to, Map.of(), win.result(),
                        List.of(), strategyLabel + " / " + win.profileName(), baselineSlug);
                writeBaseline(data, List.of());
            });
        } else {
            log.info("[Report] write-baseline=false — 기준선 미기록(대조/점검 실행)");
        }
        return file;
    }

    /** 적용 비용 셀 2칸 — 컬럼이 없으면 빈 문자열(기존 표 출력 불변), 값이 없으면 "-" */
    private static String appliedCostCells(ExitLabRow row, boolean showApplied) {
        if (!showApplied) return "";
        AppliedCost c = row.appliedCost();
        if (c == null) return " - | - |";
        return String.format(" %.3f%% | %.3f%% |", c.slippageRate() * 100, c.roundTripCost() * 100);
    }

    private static String metricsRow(String label, BacktestMetrics m) {
        return String.format("| %s | %d | %.1f%% | %s | %.3f%% | %.1f%% | %.1f%% |%n",
                label, m.tradeCount(), m.winRate() * 100, pfText(m.profitFactor()),
                m.expectancyPct() * 100, m.maxDrawdown() * 100, m.totalReturnPct() * 100);
    }

    private static String pfText(double pf) {
        return Double.isInfinite(pf) ? "inf" : String.format("%.2f", pf);
    }
}
