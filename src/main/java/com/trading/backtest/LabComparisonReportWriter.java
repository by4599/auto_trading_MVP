package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 랩 프로필 비교 리포트 (Exit/Risk/Cost/Regime Lab) — 손익비·평균보유일·윈도우별 레짐 포함.
 *
 * <p>랩마다 다른 것은 고정 문구({@link ComparisonTemplate})와 기준선 기록 여부뿐이고,
 * 표를 그리는 코드는 하나다. 거버넌스 기준선 yml은 risk-lab이 명시적으로 요청할 때만 쓴다
 * (대조/점검 실행이 기준선을 조용히 덮어쓰던 사고 2026-07-23 차단).
 */
@Component
@Profile("backtest")
public class LabComparisonReportWriter {

    private static final Logger log = LoggerFactory.getLogger(LabComparisonReportWriter.class);

    private final BacktestReportWriter baselineWriter;

    public LabComparisonReportWriter(BacktestReportWriter baselineWriter) {
        this.baselineWriter = baselineWriter;
    }

    public Path writeExitLabReport(LabScope scope, List<ExitLabRow> rows) {
        return writeComparison(LabReportTemplates.EXIT_LAB, scope, rows, false, null);
    }

    /** 합격 시 그 프로필로 거버넌스 기준선 yml을 쓴다(writeBaseline=true일 때만) */
    public Path writeRiskLabReport(LabScope scope, List<ExitLabRow> rows, boolean writeBaseline) {
        return writeComparison(LabReportTemplates.RISK_LAB, scope, rows,
                writeBaseline, "EXITLAB-" + scope.slug());
    }

    public Path writeCostLabReport(LabScope scope, List<ExitLabRow> rows) {
        return writeComparison(LabReportTemplates.COST_LAB, scope, rows, false, null);
    }

    public Path writeRegimeLabReport(LabScope scope, List<ExitLabRow> rows) {
        return writeComparison(LabReportTemplates.REGIME_LAB, scope, rows, false, null);
    }

    public Path writeRegimeSensReport(LabScope scope, List<ExitLabRow> rows) {
        return writeComparison(LabReportTemplates.REGIME_SENS, scope, rows, false, null);
    }

    private Path writeComparison(ComparisonTemplate template, LabScope scope, List<ExitLabRow> rows,
                                 boolean baselineOnPass, String baselineSlug) {
        StringBuilder md = new StringBuilder();
        appendHeader(md, template, scope);
        appendProfileTable(md, rows);
        appendFailureReasons(md, rows);

        List<ExitLabRow> passers = rows.stream().filter(r -> r.judgment().pass()).toList();
        appendRegimeConsistency(md, passers);

        md.append("## 판독 지침\n\n");
        for (String line : template.readingGuide()) md.append(line).append('\n');

        Path file = save(md.toString(), template, scope);
        writeBaselineIfRequested(passers, scope, baselineOnPass, baselineSlug);
        return file;
    }

    private void appendHeader(StringBuilder md, ComparisonTemplate template, LabScope scope) {
        md.append("# ").append(template.titlePhrase()).append(" (§14) — ")
                .append(scope.label()).append("\n\n");
        md.append(String.format("- 실행: %s%n- 기간: %s ~ %s%n- 종목: %s%n- 초기 자본: 10,000,000원%n",
                LocalDateTime.now(), scope.from(), scope.to(), String.join(", ", scope.symbols())));
        md.append(template.sweepDescLine()).append('\n');
        md.append(template.costModelLine()).append("\n\n");
    }

    private void appendProfileTable(StringBuilder md, List<ExitLabRow> rows) {
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
                    m.tradeCount(), ReportFormat.pf(m.profitFactor()),
                    ReportFormat.pf(m.payoffRatio()), m.expectancyPct() * 100, m.maxDrawdown() * 100,
                    m.avgHoldDays(), ReportFormat.verdict(row.judgment())));
        }
        md.append('\n');
    }

    private void appendFailureReasons(StringBuilder md, List<ExitLabRow> rows) {
        md.append("## 프로필별 불합격 사유\n\n");
        for (ExitLabRow row : rows) {
            if (row.judgment().pass()) continue;
            md.append("- **").append(row.profileName()).append("**: ")
                    .append(String.join(", ", row.judgment().reasons())).append('\n');
        }
        md.append('\n');
    }

    /** 레짐 일관성 — 합격 프로필의 Walk-Forward 윈도우별 검증 성적 (상승장 편중 판독용) */
    private void appendRegimeConsistency(StringBuilder md, List<ExitLabRow> passers) {
        if (passers.isEmpty()) return;
        md.append("## 윈도우별(레짐) 일관성 — 합격 프로필\n\n");
        md.append("전 구간 고르게 이기면 엣지, 특정 상승 구간에만 몰리면 레짐(베타) 의심.\n\n");
        for (ExitLabRow row : passers) {
            md.append("**").append(row.profileName()).append("**\n\n");
            md.append("| 윈도우(검증) | PF | 수익률 | 트레이드 |\n|---|---|---|---|\n");
            for (WalkForwardEngine.WindowResult w : row.result().windows()) {
                BacktestMetrics vm = w.validateMetrics();
                md.append(String.format("| %s~%s | %s | %+.1f%% | %d |%n",
                        w.window().validateFrom(), w.window().validateTo(),
                        ReportFormat.pf(vm.profitFactor()), vm.totalReturnPct() * 100, vm.tradeCount()));
            }
            md.append('\n');
        }
    }

    private Path save(String markdown, ComparisonTemplate template, LabScope scope) {
        try {
            Path dir = Path.of("logs", "backtest");
            Files.createDirectories(dir);
            Path file = dir.resolve("REPORT-" + template.filePrefix() + "-" + scope.slug() + "-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
            Files.writeString(file, markdown);
            log.info("[Report] {} 저장: {}", template.titlePhrase(), file.toAbsolutePath());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(template.titlePhrase() + " 저장 실패", e);
        }
    }

    /**
     * 합격 프로필이 있으면(risk-lab만) 첫 합격 프로필로 거버넌스 기준선 yml 기록.
     * baselineOnPass는 opt-in(--backtest.write-baseline=true)일 때만 true.
     */
    private void writeBaselineIfRequested(List<ExitLabRow> passers, LabScope scope,
                                          boolean baselineOnPass, String baselineSlug) {
        if (!baselineOnPass) {
            log.info("[Report] write-baseline=false — 기준선 미기록(대조/점검 실행)");
            return;
        }
        passers.stream().findFirst().ifPresent(win -> {
            log.info("[Report] 기준선 대상 프로필: {}", win.profileName());
            BacktestReportWriter.ReportData data = new BacktestReportWriter.ReportData(
                    scope.symbols(), scope.from(), scope.to(), Map.of(), win.result(),
                    List.of(), scope.label() + " / " + win.profileName(), baselineSlug);
            baselineWriter.writeBaseline(data, List.of());
        });
    }

    /** 적용 비용 셀 2칸 — 컬럼이 없으면 빈 문자열(기존 표 출력 불변), 값이 없으면 "-" */
    private static String appliedCostCells(ExitLabRow row, boolean showApplied) {
        if (!showApplied) return "";
        AppliedCost c = row.appliedCost();
        if (c == null) return " - | - |";
        return String.format(" %.3f%% | %.3f%% |", c.slippageRate() * 100, c.roundTripCost() * 100);
    }
}
