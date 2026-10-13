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
 * Cost Lab (§14.1) — 왕복 거래비용 상향 민감도.
 *
 * <p>검증된 후보(진입=MA 정배열, 출구=P3 다일 트레일링, 사이징=RR1 0.5R·동시5)를 전부
 * 고정하고 왕복 비용만 0.41%→0.80%로 올린다. 비용은 슬리피지에만 얹는다
 * (수수료·제세는 법정 확정값). C0(0.41%)는 §14.1 RR1 재현을 확인하는 회귀 앵커다.
 */
@Component
@Profile("backtest")
public class CostLab {

    private static final Logger log = LoggerFactory.getLogger(CostLab.class);

    private static final String COST_LAB_LABEL = "MA+P3+RR1";
    private static final String COST_LAB_SLUG  = "MA-P3-RR1";
    private static final String REPORT_LABEL   = "MA 정배열 + P3 다일 트레일링 + RR1(0.5R·동시5)";

    private final LabExecutor executor;
    private final LabComparisonReportWriter reportWriter;
    private final ExecutionKnobs knobs;
    private final StrategyToggles toggles;

    public CostLab(LabExecutor executor, LabComparisonReportWriter reportWriter,
                   ExecutionKnobs knobs, StrategyToggles toggles) {
        this.executor = executor;
        this.reportWriter = reportWriter;
        this.knobs = knobs;
        this.toggles = toggles;
    }

    public void run(List<String> symbols, LocalDate from, LocalDate to) {
        // 고정 조건: 진입=MA 정배열 / 출구=P3 다일 트레일링 / 사이징=0.5R·동시5
        toggles.enableMaBreakoutOnly();
        knobs.applyP3ExitWithHalfRisk();

        LabScope scope = new LabScope(COST_LAB_LABEL, COST_LAB_SLUG, symbols, from, to);
        logBanner(scope);
        long labStart = System.nanoTime();
        List<ExitLabRow> rows = new ArrayList<>();
        for (int i = 0; i < CostProfile.SWEEP.size(); i++) {
            rows.add(runCostProfile(CostProfile.SWEEP.get(i), i, scope));
        }

        // 기본값 복원 — 다른 모드/런으로의 누출 방지
        knobs.resetCostDefaults();
        knobs.resetExitProfile();
        knobs.resetSizing();

        Path report = reportWriter.writeCostLabReport(
                scope.withNames(REPORT_LABEL, COST_LAB_SLUG), rows);
        logSummary(rows, labStart, report);
    }

    /** 비용 프로필 1개 실행 — 세팅 → Walk-Forward → §4 판정 (진행 로그 포함) */
    private ExitLabRow runCostProfile(CostProfile cp, int index, LabScope scope) {
        knobs.setRoundTripCost(cp.roundTrip());
        // 목표값(cp.roundTrip())이 아니라 홀더에서 "다시 읽은" 실제 적용값을 기록한다 —
        // 역산이 틀리면 라벨은 그대로인 채 표가 어긋난 값을 드러내야 한다 (F-C1 재발 방지).
        AppliedCost applied = knobs.appliedCost();
        log.info("[CostLab] ({}/{}) {} 시작 — 실제 적용: 편도 슬리피지 {}, 왕복 {}",
                index + 1, CostProfile.SWEEP.size(), cp.name(),
                ReportFormat.pct(applied.slippageRate()), ReportFormat.pct(applied.roundTripCost()));

        long started = System.nanoTime();
        ExitLabRow row = executor.evaluate(cp.name(), scope, applied);

        log.info("[CostLab] ({}/{}) {} 완료 ({}초): {} → {}",
                index + 1, CostProfile.SWEEP.size(), cp.name(), ReportFormat.elapsedSec(started),
                row.result().aggregateValidation().summaryLine(),
                ReportFormat.verdict(row.judgment()));
        if (!row.judgment().pass()) {
            row.judgment().reasons().forEach(r -> log.info("[CostLab]     불합격 사유: {}", r));
        }
        return row;
    }

    /** 시작 배너 — 수십 분 걸리는 작업이므로 무엇을 몇 번 도는지 먼저 보여준다 */
    private void logBanner(LabScope scope) {
        int windowCount = LabExecutor.windowCount(scope);
        log.info("[CostLab] ══ 왕복 비용 상향 민감도 (BACKTEST-DESIGN §14.1) ══");
        log.info("[CostLab] 고정 조건: 진입=MA 정배열 / 출구=P3 다일 트레일링(ATR1.0·최대20일·arm1%/trail3%)"
                + " / 사이징=0.5R·동시5");
        log.info("[CostLab] 기간: {} ~ {} (Walk-Forward 6/3/3 윈도우 {}개)",
                scope.from(), scope.to(), windowCount);
        log.info("[CostLab] 종목 {}개: {}", scope.symbols().size(), String.join(", ", scope.symbols()));
        log.info("[CostLab] 스윕 비용: {}", CostProfile.SWEEP.stream()
                .map(c -> String.format("%s=%s", c.name(), ReportFormat.pct(c.roundTrip()))).toList());
        log.info("[CostLab] 총 실행 예정: 프로필 {} × 윈도우 {} = {}런",
                CostProfile.SWEEP.size(), windowCount, CostProfile.SWEEP.size() * windowCount);
    }

    /** 종료 요약 — 프로필별 한 줄 표를 로그로도 남긴다(리포트 파일을 열지 않아도 판독 가능하게) */
    private void logSummary(List<ExitLabRow> rows, long labStart, Path report) {
        log.info("[CostLab] ══ 전체 완료 ({}초) ══", ReportFormat.elapsedSec(labStart));
        LabLog.logProfileTable(log, "CostLab", rows);
        log.info("[CostLab] 리포트: {}", report.toAbsolutePath());
    }
}
