package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bracket Lab (§17) — 고정% 브래킷 출구("-5% 손절 / +15% 익절")가 §14의 트레일링 출구를
 * 이기는지 판정한다.
 *
 * <p>고정: 진입(랩 호출자가 지정) · 지수 추세 MA120 진입금지 필터 ON · 사이징 SZ2(0.25R·동시5) ·
 * 최대보유 20일 · 왕복 비용 0.41% · 약세장 6.5년 창. 바뀌는 것은 <b>출구 규칙 한 축</b>뿐이다.
 *
 * <p>판정 질문은 "9칸 중 가장 좋은 칸"이 아니라 <b>"브래킷 계열 전체가 같은 진입의 P3
 * 트레일링 기준선을 이기는가"</b>이다 — 9칸을 돌리면 그중 하나는 우연히 좋다.
 */
@Component
@Profile("backtest")
public class BracketLab {

    private static final Logger log = LoggerFactory.getLogger(BracketLab.class);

    /** 사전 동결된 스윕 격자 — 손절 3×익절 3 = 9조합. 사용자 원안(5%/15%)이 그 중 하나다. */
    static final List<Double> STOP_PCTS   = List.of(0.03, 0.05, 0.07);
    static final List<Double> TARGET_PCTS = List.of(0.10, 0.15, 0.20);

    /** 사용자 원안 칸 — 표에서 따로 읽기 위해 이름에 표시한다 */
    static final double USER_STOP_PCT   = 0.05;
    static final double USER_TARGET_PCT = 0.15;

    /** §15.7에서 두 강건성 관문이 열린 사이징 (0.25R·동시5) — 낙폭 잣대를 한도에서 떼어 둔다 */
    static final double SZ2_RISK_FRACTION = 0.0025;
    static final double RR1_RISK_FRACTION = 0.005;
    static final int    MAX_POSITIONS     = 5;

    static final int INDEX_TREND_MA = 120;

    /** 비교 기준선 — §14 검증 후보의 출구(P3 다일 트레일링). 이 행이 없으면 비교가 성립하지 않는다. */
    static final ExitProfile BASELINE =
            new ExitProfile("BL 기준선 P3 트레일링(ATR1.0·20일·arm1%/trail3%)",
                    1.0, false, 20, true, 0.01, 0.03);

    /** 회귀 앵커 — 같은 P3 트레일링을 0.5R로. §14.4 G1(1192·PF1.51·MDD13.3%) 재현 확인용. */
    static final ExitProfile ANCHOR_RR1 =
            new ExitProfile("AX 회귀앵커 P3 트레일링 0.5R(§14.4 G1)",
                    1.0, false, 20, true, 0.01, 0.03);

    private final LabExecutor executor;
    private final LabComparisonReportWriter reportWriter;
    private final ExecutionKnobs knobs;

    public BracketLab(LabExecutor executor, LabComparisonReportWriter reportWriter,
                      ExecutionKnobs knobs) {
        this.executor = executor;
        this.reportWriter = reportWriter;
        this.knobs = knobs;
    }

    /** 기준선 1개 + 브래킷 9개 (순서 고정 — 기준선이 항상 첫 행) */
    static List<ExitProfile> profiles() {
        List<ExitProfile> all = new ArrayList<>();
        all.add(BASELINE);
        for (double stop : STOP_PCTS) {
            for (double target : TARGET_PCTS) {
                all.add(ExitProfile.bracket(bracketName(stop, target), stop, target));
            }
        }
        return List.copyOf(all);
    }

    private static String bracketName(double stop, double target) {
        boolean userProposal = stop == USER_STOP_PCT && target == USER_TARGET_PCT;
        return String.format("BK 손절%.0f%%/익절%.0f%%%s",
                stop * 100, target * 100, userProposal ? " ★사용자 원안" : "");
    }

    /**
     * @param withRr1Anchor true면 0.5R 회귀 앵커 행을 덧붙인다(진입 경로가 기존과 같음을 증명).
     */
    public void run(LabScope scope, Runnable enableEntry, boolean withRr1Anchor) {
        enableEntry.run();
        List<ExitProfile> sweep = profiles();
        log.info("[BracketLab] ══ 고정% 브래킷 출구 vs P3 트레일링 (§17, {}) ══", scope.slug());
        log.info("[BracketLab] 기간 {}~{} · 종목 {}개 · 윈도우 {}개 · 프로필 {}개 "
                        + "(기준선 1 + 브래킷 9{}) · 사이징 0.25R·동시5 · 지수MA{} ON",
                scope.from(), scope.to(), scope.symbols().size(), LabExecutor.windowCount(scope),
                sweep.size(), withRr1Anchor ? " + 앵커 1" : "", INDEX_TREND_MA);

        List<ExitLabRow> rows = new ArrayList<>();
        for (ExitProfile p : sweep) {
            rows.add(evaluate(p, scope, SZ2_RISK_FRACTION));
        }
        if (withRr1Anchor) {
            rows.add(evaluate(ANCHOR_RR1, scope, RR1_RISK_FRACTION));
        }

        knobs.resetSizing();
        knobs.restoreRegimeDefaults();

        LabLog.logProfileTable(log, "BracketLab", rows);
        Path report = reportWriter.writeRiskLabReport(
                scope.withNames(scope.label() + " · 고정% 브래킷 출구 A/B", scope.slug() + "-BRACKET"),
                rows, false);
        log.info("[BracketLab] 판독: 9칸의 중앙값·분포가 기준선을 넘는지를 먼저 본다 — "
                + "한 칸만 튀고 이웃이 무너지면 노이즈");
        log.info("[BracketLab] 리포트: {}", report.toAbsolutePath());
    }

    private ExitLabRow evaluate(ExitProfile p, LabScope scope, double riskFraction) {
        knobs.applyExitProfile(p);
        knobs.applySizing(riskFraction, MAX_POSITIONS);
        knobs.applyIndexTrend(true, INDEX_TREND_MA);

        ExitLabRow row = executor.evaluate(p.name(), scope);
        BacktestMetrics m = row.result().aggregateValidation();
        log.info("[BracketLab] {}: {} → {}", p.name(), m.summaryLine(),
                ReportFormat.verdict(row.judgment()));
        log.info("[BracketLab]   출구 내역 {}: {}", p.name(), exitBreakdown(row));
        return row;
    }

    /**
     * 출구 사유별 내역 — "+15%를 먼저 찍을 확률"과 "갭으로 -5%에 못 판다"를 직접 재는 칸이다.
     * 검증 구간 트레이드만 센다(§4와 같은 표본).
     */
    static String exitBreakdown(ExitLabRow row) {
        Map<String, int[]> counts = new LinkedHashMap<>();   // 사유 → [건수]
        Map<String, Double> sumReturn = new LinkedHashMap<>();
        int total = 0;
        for (WalkForwardEngine.WindowResult w : row.result().windows()) {
            for (TradeRecorder.ClosedTrade t : w.validateTrades()) {
                counts.computeIfAbsent(t.exitReason(), k -> new int[1])[0]++;
                sumReturn.merge(t.exitReason(), t.returnPct(), Double::sum);
                total++;
            }
        }
        if (total == 0) return "표본 없음";

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            int n = e.getValue()[0];
            double avg = sumReturn.get(e.getKey()) / n;
            sb.append(String.format("%s %d건(%.1f%%, 평균 %+.2f%%) | ",
                    e.getKey(), n, 100.0 * n / total, avg * 100));
        }
        return sb.append(String.format("합계 %d건", total)).toString();
    }
}
