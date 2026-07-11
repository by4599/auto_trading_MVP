package com.trading.backtest;

/**
 * 가상 체결 비용 모델 (설계 문서 §2.3) — 애매하면 항상 불리하게.
 *
 * 왕복 총비용 ≈ 슬리피지 0.1%×2 + 수수료 0.015%×2 + 매도 제세 0.18% ≈ 0.41%
 * (설계 문서의 하한 0.3~0.5% 충족. 슬리피지는 실전 실측값으로 교체 예정)
 */
public final class BacktestCosts {

    public static final double SLIPPAGE_RATE   = 0.001;    // 편도 0.1%
    public static final double COMMISSION_RATE = 0.00015;  // 편도 위탁수수료 0.015%
    public static final double SELL_TAX_RATE   = 0.0018;   // 매도 제세금 0.18% (보수)

    private BacktestCosts() {}

    /** 매수 체결가 = 시장가 × (1 + 슬리피지) */
    public static double buyFillPrice(double rawPrice) {
        return rawPrice * (1 + SLIPPAGE_RATE);
    }

    /** 매도 체결가 = 시장가 × (1 − 슬리피지) */
    public static double sellFillPrice(double rawPrice) {
        return rawPrice * (1 - SLIPPAGE_RATE);
    }

    /** 매수 현금 유출 = 체결대금 + 수수료 */
    public static double buyCashOut(double fillPrice, int quantity) {
        return fillPrice * quantity * (1 + COMMISSION_RATE);
    }

    /** 매도 현금 유입 = 체결대금 − 수수료 − 제세금 */
    public static double sellCashIn(double fillPrice, int quantity) {
        return fillPrice * quantity * (1 - COMMISSION_RATE - SELL_TAX_RATE);
    }
}
