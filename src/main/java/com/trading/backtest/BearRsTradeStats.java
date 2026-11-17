package com.trading.backtest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.function.Predicate;

/**
 * 하락장 거래만 따로 본 집계 (55_quant §0.5) — 진입일이 하락장 날인 <b>검증 구간</b> 거래의 건수 · PF · 기대값과
 * 2022년 · 2024-08 하위 집계. 순수 계산 + 기존 리포트 파일 끝에 절 하나를 덧붙인다.
 */
final class BearRsTradeStats {

    static final int MIN_SAMPLE = 100;
    static final YearMonth AUG_2024 = YearMonth.of(2024, 8);

    record Subset(int count, double profitFactor, double expectancyPct) {}

    record Summary(String code, Subset all, Subset y2022, Subset aug2024) {}

    private BearRsTradeStats() {
    }

    static Summary summarize(String code, List<WalkForwardEngine.WindowResult> windows, Predicate<LocalDate> isBearDay) {
        List<TradeRecorder.ClosedTrade> bear = windows.stream()
                .flatMap(w -> w.validateTrades().stream())
                .filter(t -> isBearDay.test(t.entryDate()))
                .toList();
        return new Summary(code, subset(bear),
                subset(bear.stream().filter(t -> t.entryDate().getYear() == 2022).toList()),
                subset(bear.stream().filter(t -> YearMonth.from(t.entryDate()).equals(AUG_2024)).toList()));
    }

    static Subset subset(List<TradeRecorder.ClosedTrade> trades) {
        BacktestMetrics m = BacktestMetrics.of(trades, List.of(), 1.0);
        return new Subset(m.tradeCount(), m.profitFactor(), m.expectancyPct());
    }

    static String markdown(List<Summary> rows) {
        StringBuilder md = new StringBuilder();
        md.append("## 하락장 거래만 (진입일 = 전일 KOSPI 종가 < MA120 인 날, 검증 구간만)\n\n");
        md.append("| 코드 | 건수 | PF | 기대값 | 판독 | 2022 건수·PF·기대값 | 2024-08 건수·PF·기대값 |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (Summary s : rows) {
            md.append(String.format("| %s | %d | %s | %s | %s | %s | %s |%n", s.code(), s.all().count(),
                    ReportFormat.pf(s.all().profitFactor()), pct(s.all()),
                    s.all().count() < MIN_SAMPLE ? "표본 부족(<100)" : "판독 가능",
                    text(s.y2022()), text(s.aug2024())));
        }
        md.append("\n- 2020-03(코로나)은 채점 불가 — 6/3/3에서 첫 검증 창이 2020-07부터다.\n");
        md.append("- D0의 하락장 거래는 0건이어야 한다(정의 검산). 100건 미만이면 PF·기대값으로 판정하지 않는다.\n\n");
        return md.toString();
    }

    static void append(Path report, List<Summary> rows) {
        try {
            Files.writeString(report, markdown(rows), StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("하락장 거래 절 덧붙이기 실패: " + report, e);
        }
    }

    private static String pct(Subset s) {
        return s.count() == 0 ? "-" : String.format("%+.3f%%", s.expectancyPct() * 100);
    }

    private static String text(Subset s) {
        return s.count() == 0 ? "0건" : String.format("%d · %s · %s", s.count(), ReportFormat.pf(s.profitFactor()), pct(s));
    }
}
