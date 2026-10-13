package com.trading.backtest;

/**
 * 출구 프로필 — atrMult/타임컷/최대보유/트레일링/고정% 브래킷 조합 (BACKTEST-DESIGN §14·§17).
 *
 * <p>여러 랩이 같은 조합을 반복해서 쓰므로 상수로 둔다.
 *
 * <p>{@code stopPct}/{@code targetPct}는 고정% 브래킷 출구(§17 — "-5% 손절 / +15% 익절")
 * 전용이며 <b>둘 다 0이면 기존 동작</b>이다(손절=ATR 손절선, 목표 익절 없음).
 * 7-인자 생성자가 0·0을 채우므로 기존 프로필(P0~P4·P3·B동현행)은 글자 그대로 불변이다.
 */
record ExitProfile(String name, double atrMult, boolean timecut, int maxHoldDays,
                   boolean trailEnabled, double trailArmPct, double trailPct,
                   double stopPct, double targetPct) {

    /** 기존 형태 — 고정% 브래킷 없음(ATR 손절 / 목표 없음) */
    ExitProfile(String name, double atrMult, boolean timecut, int maxHoldDays,
                boolean trailEnabled, double trailArmPct, double trailPct) {
        this(name, atrMult, timecut, maxHoldDays, trailEnabled, trailArmPct, trailPct, 0, 0);
    }

    /** §14 검증 후보의 고정 출구 — ATR1.0 · 타임컷 OFF · 최대 20일 · 트레일 arm1%/trail3% */
    static final ExitProfile P3 =
            new ExitProfile("P3", 1.0, false, 20, true, 0.01, 0.03);

    /** 현행 paper B동(당일 단타) 출구 — ATR1.5 · 15:15 타임컷 ON · 트레일 arm3%/trail1% */
    static final ExitProfile B_DONG_CURRENT =
            new ExitProfile("B동현행", 1.5, true, 0, true, 0.03, 0.01);

    /**
     * 고정% 브래킷 출구 (§17) — 진입가 대비 -stopPct 손절 / +targetPct 목표 익절.
     * 트레일링은 끄고 P3와 같은 최대보유 20일·타임컷 OFF를 쓴다(출구 규칙 한 축만 바꿔 비교).
     * atrMult는 사이징(R 수량 역산)에만 남고 손절 레벨에는 쓰이지 않는다.
     */
    static ExitProfile bracket(String name, double stopPct, double targetPct) {
        return new ExitProfile(name, 1.0, false, 20, false, 0, 0, stopPct, targetPct);
    }
}
