package com.trading.backtest;

import java.util.ArrayList;
import java.util.List;

/**
 * accumulation-lab 보조 지표 (§18) — 공통 비교표에 없는 세 칸을 덧붙인다:
 * 총수익(검증 창 연결) · 창별 양(+) 비율 · 돈치안 D0와의 트레이드 겹침.
 *
 * <p>첫 행이 기준선(D0)이라는 규약은 {@link AccumulationLab}이 지킨다.
 */
final class AccumulationLabSummary {

    /** 근접 겹침의 창 — 진입일 ±5 달력일 */
    static final int OVERLAP_WINDOW_DAYS = 5;

    private AccumulationLabSummary() {
    }

    /** 검증 창 성적 분포 — 양(+) 창 / 거래가 있었던 창 / 전체 창 */
    record WindowStats(int positive, int traded, int total) {}

    static WindowStats windowStats(ExitLabRow row) {
        int positive = 0;
        int traded = 0;
        int total = 0;
        for (WalkForwardEngine.WindowResult w : row.result().windows()) {
            total++;
            BacktestMetrics m = w.validateMetrics();
            if (m.tradeCount() > 0) traded++;
            if (m.totalReturnPct() > 0) positive++;
        }
        return new WindowStats(positive, traded, total);
    }

    static List<TradeRecorder.ClosedTrade> validationTrades(ExitLabRow row) {
        List<TradeRecorder.ClosedTrade> all = new ArrayList<>();
        row.result().windows().forEach(w -> all.addAll(w.validateTrades()));
        return all;
    }

    /** 리포트에 덧붙일 절 (로그에도 같은 줄을 찍는다) */
    static String markdown(List<ExitLabRow> rows) {
        List<TradeRecorder.ClosedTrade> baselineTrades = validationTrades(rows.get(0));
        StringBuilder md = new StringBuilder();
        md.append("## 흔적레이더 보조 지표 — 총수익 · 창별 양(+) · 돈치안 D0와 겹침\n\n");
        md.append("| 칸 | 트레이드 | PF | 기대값 | MDD | 총수익(검증 창 연결) | 양(+) 창 / 거래 있는 창 / 전체 "
                + "| D0와 같은 날·같은 종목 | D0와 ±5일 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        for (ExitLabRow row : rows) {
            md.append(rowLine(row, row == rows.get(0) ? null
                    : TradeOverlap.measure(validationTrades(row), baselineTrades, OVERLAP_WINDOW_DAYS)));
        }
        md.append("\n- 첫 행이 기준선(D0)이다. 겹침은 실제 산 트레이드 기준(슬롯 경합에 따라 달라지는 경로 의존 값).\n");
        md.append("- 판정은 최고 칸이 아니라 **가족 전체**로 본다(사전 등록 `_workspace/51_quant` §0.5).\n");
        return md.toString();
    }

    private static String rowLine(ExitLabRow row, TradeOverlap overlap) {
        BacktestMetrics m = row.result().aggregateValidation();
        WindowStats ws = windowStats(row);
        String overlapCells = overlap == null ? " — | — |" : String.format(" %d건 (%.1f%%) | %d건 (%.1f%%) |",
                overlap.sameDay(), overlap.sameDayRatio() * 100, overlap.nearby(), overlap.nearbyRatio() * 100);
        return String.format("| %s | %d | %s | %+.3f%% | %.2f%% | %+.2f%% | %d / %d / %d |%s%n",
                row.profileName(), m.tradeCount(), ReportFormat.pf(m.profitFactor()), m.expectancyPct() * 100,
                m.maxDrawdown() * 100, m.totalReturnPct() * 100, ws.positive(), ws.traded(), ws.total(),
                overlapCells);
    }
}
