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
 * scale-in-lab 리포트 — 랩 공통 표에 현금 비중·2022 구간·창내 최악 낙폭·분할 매수 사건 수를 더한다.
 *
 * <p>§4 판정은 엔진 판정({@link BacktestReportWriter#judge})을 그대로 옮긴다(집계 MDD = 창 연결 곡선 기준,
 * 기존 앵커와 같은 잣대). "창내 최악 MDD"는 일별 곡선 기준 참고값이며 판정을 바꾸지 않는다.
 */
@Component
@Profile("backtest")
public class ScaleInReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ScaleInReportWriter.class);

    record Row(String code, String name, ExitLabRow lab, double cashRatio, Map<String, Integer> counters) {}

    private final CandleCoverageChecker coverageChecker;

    public ScaleInReportWriter(CandleCoverageChecker coverageChecker) {
        this.coverageChecker = coverageChecker;
    }

    public Path write(LabScope scope, List<Row> rows) {
        return write("SCALEIN", "Scale-In Lab", scope, rows);
    }

    /** 같은 표를 다른 랩(손절 폭 스윕 등)이 쓴다 — 파일 접두어·제목만 다르다 */
    public Path write(String filePrefix, String title, LabScope scope, List<Row> rows) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append(" — ").append(scope.label()).append("\n\n");
        md.append(String.format("- 실행: %s%n- 기간: %s ~ %s (Walk-Forward 6/3/3, 검증 구간만 채점)%n- 종목: %d개%n",
                LocalDateTime.now(), scope.from(), scope.to(), scope.symbols().size()));
        md.append("- 초기 자본 10,000,000원 · 왕복 비용 기본 0.41% · 출구 P3 · 동시 최대 5 · 진입 돈치안(고가20·추세120)\n\n");
        appendSummary(md, rows);
        appendReasons(md, rows);
        appendWindows(md, rows);
        appendCounters(md, rows);
        appendExitReasons(md, rows);
        coverageChecker.lastReport().ifPresent(c -> md.append(c.markdownLine()));
        return save(md.toString(), filePrefix, scope);
    }

    private void appendSummary(StringBuilder md, List<Row> rows) {
        md.append("## 판정표\n\n");
        md.append("| 코드 | 프로필 | 트레이드 | PF | 손익비 | 기대값 | MDD(§4) | 창내 최악 MDD | 총수익 "
                + "| 양(+)창/거래창/전체 | 2022 합성 | 평균 현금비중 | §4 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            BacktestMetrics m = r.lab().result().aggregateValidation();
            List<WalkForwardEngine.WindowResult> ws = r.lab().result().windows();
            md.append(String.format("| %s | %s | %d | %s | %s | %+.3f%% | %.2f%% | %.2f%% | %+.2f%% | %s | %s | %.1f%% | %s |%n",
                    r.code(), r.name(), m.tradeCount(), ReportFormat.pf(m.profitFactor()),
                    ReportFormat.pf(m.payoffRatio()), m.expectancyPct() * 100, m.maxDrawdown() * 100,
                    ScaleInWindowStats.worstWindowMdd(ws) * 100, m.totalReturnPct() * 100,
                    ScaleInWindowStats.positiveWindowsText(ws), ScaleInWindowStats.yearText(ws, 2022),
                    r.cashRatio() * 100, ReportFormat.verdict(r.lab().judgment())));
        }
        md.append('\n');
    }

    private void appendReasons(StringBuilder md, List<Row> rows) {
        md.append("## 불합격 사유\n\n");
        for (Row r : rows) {
            if (r.lab().judgment().pass()) continue;
            md.append("- **").append(r.code()).append("**: ")
                    .append(String.join(", ", r.lab().judgment().reasons())).append('\n');
        }
        md.append('\n');
    }

    private void appendWindows(StringBuilder md, List<Row> rows) {
        md.append("## 창별 검증 수익률 / 트레이드\n\n| 검증 창 |");
        rows.forEach(r -> md.append(' ').append(r.code()).append(" |"));
        md.append("\n|---|");
        rows.forEach(r -> md.append("---|"));
        md.append('\n');
        int windowCount = rows.isEmpty() ? 0 : rows.get(0).lab().result().windows().size();
        for (int i = 0; i < windowCount; i++) {
            var w = rows.get(0).lab().result().windows().get(i).window();
            md.append("| ").append(w.validateFrom()).append('~').append(w.validateTo()).append(" |");
            for (Row r : rows) {
                BacktestMetrics vm = r.lab().result().windows().get(i).validateMetrics();
                md.append(String.format(" %+.2f%% / %d |", vm.totalReturnPct() * 100, vm.tradeCount()));
            }
            md.append('\n');
        }
        md.append('\n');
    }

    private void appendCounters(StringBuilder md, List<Row> rows) {
        md.append("## 분할 매수 사건 수 (검증 구간만)\n\n| 코드 | 사건 |\n|---|---|\n");
        for (Row r : rows) {
            md.append("| ").append(r.code()).append(" | ")
                    .append(r.counters().isEmpty() ? "-" : new java.util.TreeMap<>(r.counters()).toString())
                    .append(" |\n");
        }
        md.append('\n');
    }

    /** 검증 구간 청산 사유별 건수 — 어떤 출구가 실제로 일했는지(손절·트레일·최대보유) */
    private void appendExitReasons(StringBuilder md, List<Row> rows) {
        md.append("## 청산 사유별 건수 (검증 구간)\n\n| 코드 | 사유 |\n|---|---|\n");
        for (Row r : rows) {
            java.util.Map<String, Long> reasons = new java.util.TreeMap<>();
            r.lab().result().windows().forEach(w -> w.validateTrades().forEach(
                    t -> reasons.merge(t.exitReason(), 1L, Long::sum)));
            md.append("| ").append(r.code()).append(" | ").append(reasons).append(" |\n");
        }
        md.append('\n');
    }

    private Path save(String markdown, String filePrefix, LabScope scope) {
        try {
            Path dir = Path.of("logs", "backtest");
            Files.createDirectories(dir);
            Path file = dir.resolve("REPORT-" + filePrefix + "-" + scope.slug() + "-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
            Files.writeString(file, markdown);
            log.info("[Report] Scale-In Lab 저장: {}", file.toAbsolutePath());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Scale-In Lab 리포트 저장 실패", e);
        }
    }
}
