package com.trading.backtest;

import java.util.List;

/** 창(Walk-Forward 검증 구간) 단위 부가 지표 — 순수 계산 */
final class ScaleInWindowStats {

    private ScaleInWindowStats() {
    }

    /** 창 안 일별 곡선 MDD 중 최댓값 — 집계 MDD(창 끝 연결)가 놓치는 창 내부 낙폭 */
    static double worstWindowMdd(List<WalkForwardEngine.WindowResult> windows) {
        return windows.stream().mapToDouble(w -> w.validateMetrics().maxDrawdown()).max().orElse(0.0);
    }

    /** "양(+) 창 / 거래 있는 창 / 전체 창" */
    static String positiveWindowsText(List<WalkForwardEngine.WindowResult> windows) {
        long traded = windows.stream().filter(w -> w.validateMetrics().tradeCount() > 0).count();
        long positive = windows.stream().filter(w -> w.validateMetrics().totalReturnPct() > 0).count();
        return positive + "/" + traded + "/" + windows.size();
    }

    /** 검증 시작일이 그 해인 창들의 합성 수익률 */
    static double yearCompound(List<WalkForwardEngine.WindowResult> windows, int year) {
        double growth = 1.0;
        for (WalkForwardEngine.WindowResult w : windows) {
            if (w.window().validateFrom().getYear() != year) continue;
            growth *= 1 + w.validateMetrics().totalReturnPct();
        }
        return growth - 1;
    }

    static int yearTrades(List<WalkForwardEngine.WindowResult> windows, int year) {
        return windows.stream().filter(w -> w.window().validateFrom().getYear() == year)
                .mapToInt(w -> w.validateMetrics().tradeCount()).sum();
    }

    static String yearText(List<WalkForwardEngine.WindowResult> windows, int year) {
        return String.format("%+.2f%% (%d건)", yearCompound(windows, year) * 100, yearTrades(windows, year));
    }
}
