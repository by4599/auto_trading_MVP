package com.trading.backtest;

/**
 * 출구 프로필 — atrMult/타임컷/최대보유/트레일링 조합 (BACKTEST-DESIGN §14).
 *
 * <p>여러 랩이 같은 조합을 반복해서 쓰므로 상수로 둔다.
 */
record ExitProfile(String name, double atrMult, boolean timecut, int maxHoldDays,
                   boolean trailEnabled, double trailArmPct, double trailPct) {

    /** §14 검증 후보의 고정 출구 — ATR1.0 · 타임컷 OFF · 최대 20일 · 트레일 arm1%/trail3% */
    static final ExitProfile P3 =
            new ExitProfile("P3", 1.0, false, 20, true, 0.01, 0.03);

    /** 현행 paper B동(당일 단타) 출구 — ATR1.5 · 15:15 타임컷 ON · 트레일 arm3%/trail1% */
    static final ExitProfile B_DONG_CURRENT =
            new ExitProfile("B동현행", 1.5, true, 0, true, 0.03, 0.01);
}
