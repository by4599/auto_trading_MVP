package com.trading.backtest;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 63_quant §0.5 집계 — 창별 오버레이 결과를 엔진과 같은 잣대로 묶는다. 순수 계산.
 *
 * <p>§4 MDD = 창마다 1,000만원에서 다시 시작한 수익률을 곱연결한 창 끝 곡선 기준({@code WalkForwardEngine.aggregate}와 같다).
 * 창내 최악 MDD = 창별 일별 곡선의 최대 낙폭 중 최댓값(참고).
 */
final class InverseOverlayStats {

    static final YearMonth AUG_2024 = YearMonth.of(2024, 8);

    /** 합성(또는 인버스 단독) 한 칸의 집계 */
    record Cell(String code, BacktestMetrics metrics, double worstWindowMdd, int inverseTrades,
                int daysBelow3, int daysBelow5) {}

    /** 인버스 거래만의 하위 집계 */
    record Subset(int count, double profitFactor, double pnl) {}

    private InverseOverlayStats() {
    }

    /**
     * @param baseTrades 창별 A동 검증 거래(인버스 단독이면 빈 목록들)
     * @param outcomes   창별 오버레이 결과(같은 순서)
     */
    static Cell aggregate(String code, List<List<TradeRecorder.ClosedTrade>> baseTrades,
                          List<InverseOverlay.WindowOutcome> outcomes, double initialCash) {
        if (baseTrades.size() != outcomes.size()) {
            throw new IllegalArgumentException("창 수 불일치: A동 " + baseTrades.size() + " · 오버레이 " + outcomes.size());
        }
        List<TradeRecorder.ClosedTrade> all = new ArrayList<>();
        List<Double> chained = new ArrayList<>();
        double base = initialCash;
        double worst = 0;
        int inverse = 0;
        int below3 = 0;
        int below5 = 0;
        for (int w = 0; w < outcomes.size(); w++) {
            InverseOverlay.WindowOutcome o = outcomes.get(w);
            all.addAll(baseTrades.get(w));
            all.addAll(o.trades());
            inverse += o.trades().size();
            double end = o.composite().isEmpty() ? initialCash : o.composite().get(o.composite().size() - 1);
            base = base * (end / initialCash);
            chained.add(base);
            worst = Math.max(worst, BacktestMetrics.maxDrawdown(o.composite(), initialCash));
            below3 += countDaysBelow(o.composite(), initialCash, -0.03);
            below5 += countDaysBelow(o.composite(), initialCash, -0.05);
        }
        return new Cell(code, BacktestMetrics.of(all, chained, initialCash), worst, inverse, below3, below5);
    }

    /** 하루 수익률(전일 마감 대비, 첫날은 창 시작 자본 대비)이 문턱 이하인 날 수 */
    static int countDaysBelow(List<Double> equity, double initialCash, double threshold) {
        int n = 0;
        double prev = initialCash;
        for (double e : equity) {
            if (prev > 0 && e / prev - 1 <= threshold) n++;
            prev = e;
        }
        return n;
    }

    static Subset subset(List<InverseOverlay.WindowOutcome> outcomes, Predicate<LocalDate> entryFilter) {
        List<TradeRecorder.ClosedTrade> picked = outcomes.stream()
                .flatMap(o -> o.trades().stream())
                .filter(t -> entryFilter.test(t.entryDate()))
                .toList();
        BacktestMetrics m = BacktestMetrics.of(picked, List.of(), 1.0);
        double pnl = picked.stream().mapToDouble(TradeRecorder.ClosedTrade::pnl).sum();
        return new Subset(m.tradeCount(), m.profitFactor(), pnl);
    }

    static Predicate<LocalDate> year(int y) {
        return d -> d.getYear() == y;
    }

    static Predicate<LocalDate> month(YearMonth ym) {
        return d -> YearMonth.from(d).equals(ym);
    }

    /** A동 기록을 현금만 있는 계좌로 바꾼다 — 인버스 단독 성적용(날짜는 그대로) */
    static List<ScaleInRunStats.DayEnd> flatTape(List<ScaleInRunStats.DayEnd> tape, double initialCash) {
        return tape.stream().map(d -> new ScaleInRunStats.DayEnd(d.date(), initialCash, initialCash)).toList();
    }
}
