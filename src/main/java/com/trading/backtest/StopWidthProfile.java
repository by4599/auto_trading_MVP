package com.trading.backtest;

import java.util.ArrayList;
import java.util.List;

/**
 * 손절 폭 스윕(56_quant §0) 한 칸 — 수량을 정하는 ATR 배수와 손절선 ATR 배수를 따로 둔다.
 *
 * @param sizingMult 사이징 배수(수량 = 1R ÷ (ATR × 이 값)). 손절선도 일단 이 배수로 장착된다
 * @param stopMult   손절선 배수 — NaN이면 사이징 배수 그대로, 0이면 손절 없음(손절가 0원)
 * @param fixedStopPct 0이면 ATR 손절. >0이면 손절선 = 진입가 × (1 − 이 값) 고정% (58_quant)
 */
record StopWidthProfile(String code, String name, double sizingMult, double stopMult,
                        BacktestStopRules.EntryDayStop entryDay, int lookback, int trendMa, double roundTripCost,
                        double fixedStopPct) {

    StopWidthProfile(String code, String name, double sizingMult, double stopMult,
                     BacktestStopRules.EntryDayStop entryDay, int lookback, int trendMa, double roundTripCost) {
        this(code, name, sizingMult, stopMult, entryDay, lookback, trendMa, roundTripCost, 0);
    }

    static final BacktestStopRules.EntryDayStop SAME = BacktestStopRules.EntryDayStop.SAME_DAY;
    static final BacktestStopRules.EntryDayStop NEXT = BacktestStopRules.EntryDayStop.FROM_NEXT_DAY;

    static StopWidthProfile none() {
        return new StopWidthProfile("N", "손절 없음 · 수량 ATR1.0 (옛 엔진 SZ2와 같아야 함)", 1.0, 0.0, SAME, 20, 120, 0);
    }

    /** 연동(A): 사이징 배수 = 손절 배수 */
    static StopWidthProfile coupled(double m, BacktestStopRules.EntryDayStop day) {
        return new StopWidthProfile(String.format("A%02d-%s", Math.round(m * 10), tag(day)),
                String.format("연동 수량·손절 ATR×%.1f · %s", m, dayName(day)), m, Double.NaN, day, 20, 120, 0);
    }

    /** 고정(B): 수량은 ATR×1.0, 손절선만 ATR×m */
    static StopWidthProfile fixedSize(double m, BacktestStopRules.EntryDayStop day) {
        return new StopWidthProfile(String.format("B%02d-%s", Math.round(m * 10), tag(day)),
                String.format("수량 ATR×1.0 고정 · 손절 ATR×%.1f · %s", m, dayName(day)), 1.0, m, day, 20, 120, 0);
    }

    /** 고정%(F): 수량은 ATR×1.0, ATR 손절은 끄고 손절선 = 진입가 × (1 − pct) */
    static StopWidthProfile fixedPct(double pct, BacktestStopRules.EntryDayStop day) {
        return new StopWidthProfile(String.format("F%02d-%s", Math.round(pct * 100), tag(day)),
                String.format("수량 ATR×1.0 고정 · 손절 −%.0f%% 고정 · %s", pct * 100, dayName(day)),
                1.0, 0.0, day, 20, 120, 0, pct);
    }

    /** 고정% 손절 격자 (58_quant §0, 순서 고정) */
    static List<StopWidthProfile> pctGrid() {
        List<StopWidthProfile> grid = new ArrayList<>();
        for (double pct : List.of(0.05, 0.07, 0.10, 0.15)) {
            grid.add(fixedPct(pct, SAME));
            grid.add(fixedPct(pct, NEXT));
        }
        return grid;
    }

    /** 사전 등록 15칸 (순서 고정) */
    static List<StopWidthProfile> grid() {
        List<StopWidthProfile> grid = new ArrayList<>();
        grid.add(none());
        for (double m : List.of(1.0, 1.5, 2.0, 3.0)) {
            grid.add(coupled(m, SAME));
            grid.add(coupled(m, NEXT));
        }
        for (double m : List.of(1.5, 2.0, 3.0)) {
            grid.add(fixedSize(m, SAME));
            grid.add(fixedSize(m, NEXT));
        }
        return grid;
    }

    /** 합격 출구의 민감도·비용 칸 — 같은 진입일 규칙에서 m ×0.8/×1.2 · 돈치안 ±20% · 비용 0.50/0.80% */
    static List<StopWidthProfile> sensitivity(StopWidthProfile base) {
        boolean coupled = Double.isNaN(base.stopMult());
        double m = coupled ? base.sizingMult() : base.stopMult();
        List<StopWidthProfile> grid = new ArrayList<>();
        grid.add(base.renamed(base.code() + "-base", base.name() + " (본 실행 재현)"));
        if (base.fixedStopPct() > 0) {
            grid.add(base.withFixedStopPct(base.fixedStopPct() * 0.8).renamed(base.code() + "-Mm",
                    String.format("고정 손절 −%.1f%% (−20%%)", base.fixedStopPct() * 80)));
            grid.add(base.withFixedStopPct(base.fixedStopPct() * 1.2).renamed(base.code() + "-Mp",
                    String.format("고정 손절 −%.1f%% (+20%%)", base.fixedStopPct() * 120)));
        } else {
            grid.add(scaled(base, m * 0.8, coupled).renamed(base.code() + "-Mm", String.format("손절 배수 %.2f (−20%%)", m * 0.8)));
            grid.add(scaled(base, m * 1.2, coupled).renamed(base.code() + "-Mp", String.format("손절 배수 %.2f (+20%%)", m * 1.2)));
        }
        grid.add(base.withDonchian(16, 120).renamed(base.code() + "-L16", "돈치안 고가16 (−20%)"));
        grid.add(base.withDonchian(24, 120).renamed(base.code() + "-L24", "돈치안 고가24 (+20%)"));
        grid.add(base.withDonchian(20, 96).renamed(base.code() + "-T96", "종목 추세96 (−20%)"));
        grid.add(base.withDonchian(20, 144).renamed(base.code() + "-T144", "종목 추세144 (+20%)"));
        grid.add(base.withCost(0.0050).renamed(base.code() + "-C050", "왕복 비용 0.50% (+0.09%p)"));
        grid.add(base.withCost(0.0080).renamed(base.code() + "-C080", "왕복 비용 0.80%"));
        return grid;
    }

    private static StopWidthProfile scaled(StopWidthProfile b, double m, boolean coupled) {
        return coupled
                ? new StopWidthProfile(b.code, b.name, m, Double.NaN, b.entryDay, b.lookback, b.trendMa, b.roundTripCost)
                : new StopWidthProfile(b.code, b.name, b.sizingMult, m, b.entryDay, b.lookback, b.trendMa, b.roundTripCost);
    }

    StopWidthProfile renamed(String newCode, String newName) {
        return new StopWidthProfile(newCode, newName, sizingMult, stopMult, entryDay, lookback, trendMa, roundTripCost, fixedStopPct);
    }

    StopWidthProfile withDonchian(int newLookback, int newTrendMa) {
        return new StopWidthProfile(code, name, sizingMult, stopMult, entryDay, newLookback, newTrendMa, roundTripCost, fixedStopPct);
    }

    StopWidthProfile withFixedStopPct(double pct) {
        return new StopWidthProfile(code, name, sizingMult, stopMult, entryDay, lookback, trendMa, roundTripCost, pct);
    }

    StopWidthProfile withCost(double cost) {
        return new StopWidthProfile(code, name, sizingMult, stopMult, entryDay, lookback, trendMa, cost, fixedStopPct);
    }

    private static String tag(BacktestStopRules.EntryDayStop day) {
        return day == SAME ? "S" : "X";
    }

    private static String dayName(BacktestStopRules.EntryDayStop day) {
        return day == SAME ? "진입당일부터(비관)" : "다음날부터(낙관)";
    }
}
