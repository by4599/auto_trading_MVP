package com.trading.backtest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 63_quant 리포트 — M · 진입일 가정마다 A동 단독 1행 + 합성 12칸 + 인버스 단독 12칸.
 * 칸 표시 (a)(b)(c)(d)는 §0.6-1을 그대로 옮긴 것이다(판정은 63번 문서에서 사람이 읽고 적는다).
 */
final class BearInverseReport {

    static final double MAX_MDD = 0.15;
    static final double MDD_SLACK = 0.03;

    private BearInverseReport() {
    }

    record Flags(boolean a, boolean b, boolean c, boolean d) {
        boolean all() {
            return a && b && c && d;
        }
    }

    static Flags flags(InverseOverlayStats.Cell alone, InverseOverlayStats.Cell comp, InverseOverlayStats.Cell solo) {
        BacktestMetrics m = comp.metrics();
        boolean a = m.tradeCount() >= 100 && m.profitFactor() >= 1.3 && m.expectancyPct() > 0 && m.maxDrawdown() <= MAX_MDD;
        boolean b = m.totalReturnPct() > alone.metrics().totalReturnPct();
        boolean c = m.maxDrawdown() <= alone.metrics().maxDrawdown() + MDD_SLACK && comp.worstWindowMdd() <= MAX_MDD;
        boolean d = solo.metrics().profitFactor() >= 1.3;
        return new Flags(a, b, c, d);
    }

    static Path write(LabScope scope, String tag, List<BearInverseLab.Section> sections, boolean synthetic) {
        StringBuilder md = new StringBuilder();
        md.append("# Bear Inverse Lab (63_quant) — ").append(scope.label()).append(" · ").append(tag).append("\n\n");
        md.append(String.format("- 실행: %s%n- 기간: %s ~ %s (Walk-Forward 6/3/3, 검증 구간만 채점, 창마다 1,000만원 재시작)%n- 종목: %d개%n",
                LocalDateTime.now(), scope.from(), scope.to(), scope.symbols().size()));
        md.append("- 인버스 데이터: ").append(synthetic ? "KOSPI 합성 −1배(연 보수 0.64% 일할, 괴리 무시)" : "114800 실제 일봉(candle_history)")
                .append(" · 인버스 비용 = 주식과 같음(매도 제세 포함)\n\n");
        for (BearInverseLab.Section s : sections) appendSection(md, s);
        return save(md.toString(), scope, tag);
    }

    private static void appendSection(StringBuilder md, BearInverseLab.Section s) {
        BacktestMetrics e = s.engine().result().aggregateValidation();
        md.append(String.format("## M=%d · %s (왕복 %.2f%%)%n%n", s.ma(), s.assumption(),
                s.engine().appliedCost() == null ? Double.NaN : s.engine().appliedCost().roundTripCost() * 100));
        md.append(String.format("- A동 단독(엔진): %s · §4 %s%n%n", e.summaryLine(), ReportFormat.verdict(s.engine().judgment())));
        md.append("| 코드 | 트레이드(인버스) | PF | 기대값 | §4 MDD | 창내 최악 MDD | 총수익 | A동 대비 | ≤−3%일 · ≤−5%일 | (a)(b)(c)(d) |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        md.append(row(s.alone(), s.alone(), "-"));
        for (int i = 0; i < s.composite().size(); i++) {
            Flags f = flags(s.alone(), s.composite().get(i), s.inverseOnly().get(i));
            md.append(row(s.composite().get(i), s.alone(), mark(f)));
        }
        if (s.inverseOnly().isEmpty()) {
            md.append('\n');
            return;
        }
        md.append("\n인버스 단독(현금 1,000만원에 같은 규칙):\n\n");
        md.append("| 코드 | 건수 | PF | 기대값 | 총수익 | §4 MDD | 창내 최악 MDD | 손익 합 | 2022 건수·PF·손익 | 2024-08 건수·PF·손익 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < s.inverseOnly().size(); i++) {
            InverseOverlayStats.Cell c = s.inverseOnly().get(i);
            InverseOverlayStats.Subset[] sub = s.inverseSubsets().get(i);
            BacktestMetrics m = c.metrics();
            md.append(String.format("| %s | %d | %s | %+.3f%% | %+.2f%% | %.2f%% | %.2f%% | %,.0f | %s | %s |%n", c.code(),
                    m.tradeCount(), ReportFormat.pf(m.profitFactor()), m.expectancyPct() * 100, m.totalReturnPct() * 100,
                    m.maxDrawdown() * 100, c.worstWindowMdd() * 100, sub[0].pnl(), text(sub[1]), text(sub[2])));
        }
        md.append('\n');
    }

    private static String row(InverseOverlayStats.Cell c, InverseOverlayStats.Cell alone, String flags) {
        BacktestMetrics m = c.metrics();
        return String.format("| %s | %d(%d) | %s | %+.3f%% | %.2f%% | %.2f%% | %+.2f%% | %+.2f%%p | %d · %d | %s |%n",
                c.code(), m.tradeCount(), c.inverseTrades(), ReportFormat.pf(m.profitFactor()), m.expectancyPct() * 100,
                m.maxDrawdown() * 100, c.worstWindowMdd() * 100, m.totalReturnPct() * 100,
                (m.totalReturnPct() - alone.metrics().totalReturnPct()) * 100, c.daysBelow3(), c.daysBelow5(), flags);
    }

    private static String mark(Flags f) {
        return (f.a() ? "✅" : "❌") + (f.b() ? "✅" : "❌") + (f.c() ? "✅" : "❌") + (f.d() ? "✅" : "❌");
    }

    private static String text(InverseOverlayStats.Subset s) {
        return s.count() == 0 ? "0건" : String.format("%d · %s · %,.0f", s.count(), ReportFormat.pf(s.profitFactor()), s.pnl());
    }

    private static Path save(String markdown, LabScope scope, String tag) {
        try {
            Path dir = Path.of("logs", "backtest");
            Files.createDirectories(dir);
            Path file = dir.resolve("REPORT-BEARINV-" + scope.slug() + "-" + tag + "-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
            Files.writeString(file, markdown);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Bear Inverse 리포트 저장 실패", e);
        }
    }
}
