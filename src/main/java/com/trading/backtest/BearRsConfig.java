package com.trading.backtest;

/**
 * 하락장 상대강도 예외 한 칸의 설정 (55_quant §0.3 — 결과 전에 고정).
 *
 * @param n           RS 기간(거래일) — 60 / 120
 * @param condition   허용 조건 (a) RS&gt;0 · (b) 상위 비율 · (c) (b) + 52주 고가 근접
 * @param topFraction (b)(c)의 상위 비율 — 0.20
 * @param highRatio   (c)의 고가 근접 비율 — 0.9 (직전 {@link #HIGH_LOOKBACK}거래일 고가 최댓값 대비)
 * @param halfSize    true면 하락장 날 진입분만 1R 비율을 절반으로 (0.25R → 0.125R)
 */
record BearRsConfig(int n, Condition condition, double topFraction, double highRatio, boolean halfSize) {

    enum Condition { A_POSITIVE, B_TOP, C_TOP_NEAR_HIGH }

    static final double TOP_FRACTION = 0.20;
    static final double HIGH_RATIO = 0.9;
    static final int HIGH_LOOKBACK = 250;

    static BearRsConfig of(int n, Condition condition, boolean halfSize) {
        return new BearRsConfig(n, condition, TOP_FRACTION, HIGH_RATIO, halfSize);
    }

    String conditionTag() {
        return switch (condition) {
            case A_POSITIVE -> "a";
            case B_TOP -> "b";
            case C_TOP_NEAR_HIGH -> "c";
        };
    }
}
