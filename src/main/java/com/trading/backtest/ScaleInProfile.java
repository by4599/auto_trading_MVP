package com.trading.backtest;

import java.util.ArrayList;
import java.util.List;

/**
 * scale-in-lab 프로필 1칸 — 진입 방식(전량/분할) × 지수 필터 처리 × (민감도용) 파라미터.
 *
 * <p>{@code scaleIn=false}면 기존 R 사이징 경로(회귀 앵커)다. 격자는 결과를 보기 전에 여기서 고정한다.
 */
record ScaleInProfile(String code, String name, boolean scaleIn, double riskFraction,
                      int tranches, double stepAtr, ScaleInProperties.StopBasis stopBasis,
                      IndexMode indexMode, double targetWeight, int lookback, int trendMa,
                      double roundTripCost) {

    /** 지수 필터 3종 — (a) MA120 진입 금지 유지 / (b) 필터 제거 / (c) 하락 추세면 1차분만 */
    enum IndexMode { A_MA120, B_NONE, C_FIRST_ONLY }

    static final double NO_COST_OVERRIDE = 0.0;
    static final ScaleInProperties.StopBasis FIRST = ScaleInProperties.StopBasis.FIRST_ENTRY;
    static final ScaleInProperties.StopBasis AVG = ScaleInProperties.StopBasis.AVERAGE;

    /** 회귀 앵커 — 기존 R 사이징 · 지수 MA120 */
    static ScaleInProfile anchor(String code, String name, double riskFraction) {
        return new ScaleInProfile(code, name, false, riskFraction, 1, 0, FIRST,
                IndexMode.A_MA120, 0.20, 20, 120, NO_COST_OVERRIDE);
    }

    static ScaleInProfile c100(IndexMode mode) {
        return new ScaleInProfile("C100-" + tag(mode), "C100 전량 20%×5 · " + label(mode),
                true, 0.005, 1, 0, FIRST, mode, 0.20, 20, 120, NO_COST_OVERRIDE);
    }

    static ScaleInProfile split(double k, ScaleInProperties.StopBasis basis, IndexMode mode) {
        String basisTag = basis == AVG ? "A" : "F";
        String basisName = basis == AVG ? "손절=평균단가" : "손절=1차가";
        return new ScaleInProfile(String.format("S%s%s-%s", kTag(k), basisTag, tag(mode)),
                String.format("분할3 k%.1f %s · %s", k, basisName, label(mode)),
                true, 0.005, 3, k, basis, mode, 0.20, 20, 120, NO_COST_OVERRIDE);
    }

    /** 사전 고정 본 격자 — C100 × 지수3 + 분할(k 0.5/1.0 × 손절 기준 2) × 지수3 = 15칸 */
    static List<ScaleInProfile> mainGrid() {
        List<ScaleInProfile> grid = new ArrayList<>();
        for (IndexMode mode : IndexMode.values()) {
            grid.add(c100(mode));
            for (double k : List.of(0.5, 1.0)) {
                grid.add(split(k, FIRST, mode));
                grid.add(split(k, AVG, mode));
            }
        }
        return grid;
    }

    ScaleInProfile renamed(String newCode, String newName) {
        return new ScaleInProfile(newCode, newName, scaleIn, riskFraction, tranches, stepAtr, stopBasis,
                indexMode, targetWeight, lookback, trendMa, roundTripCost);
    }

    ScaleInProfile withTargetWeight(double w) {
        return new ScaleInProfile(code, name, scaleIn, riskFraction, tranches, stepAtr, stopBasis,
                indexMode, w, lookback, trendMa, roundTripCost);
    }

    ScaleInProfile withStepAtr(double k) {
        return new ScaleInProfile(code, name, scaleIn, riskFraction, tranches, k, stopBasis,
                indexMode, targetWeight, lookback, trendMa, roundTripCost);
    }

    ScaleInProfile withDonchian(int newLookback, int newTrendMa) {
        return new ScaleInProfile(code, name, scaleIn, riskFraction, tranches, stepAtr, stopBasis,
                indexMode, targetWeight, newLookback, newTrendMa, roundTripCost);
    }

    ScaleInProfile withRoundTripCost(double cost) {
        return new ScaleInProfile(code, name, scaleIn, riskFraction, tranches, stepAtr, stopBasis,
                indexMode, targetWeight, lookback, trendMa, cost);
    }

    private static String tag(IndexMode mode) {
        return switch (mode) {
            case A_MA120 -> "a";
            case B_NONE -> "b";
            case C_FIRST_ONLY -> "c";
        };
    }

    private static String label(IndexMode mode) {
        return switch (mode) {
            case A_MA120 -> "(a)지수MA120 진입금지";
            case B_NONE -> "(b)지수필터 없음";
            case C_FIRST_ONLY -> "(c)하락추세엔 1차분만";
        };
    }

    private static String kTag(double k) {
        return k == 0.5 ? "05" : k == 1.0 ? "10" : String.format("%02d", Math.round(k * 10));
    }
}
