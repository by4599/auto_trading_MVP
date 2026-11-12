package com.trading.backtest;

/**
 * 분할 매수 계산 — 순수 함수만 둔다(단위 테스트 대상). 상태·체결은 {@link ScaleInExecutor}가 맡는다.
 *
 * <p>규칙 (2026-11-12 사용자 설계, 애매하면 불리하게):
 * <ul>
 *   <li>n차 트리거 = 1차 체결가 + (n−1) × k × ATR(1차 진입 시점 값으로 고정)</li>
 *   <li>그날 고가 ≥ 트리거면 체결. 체결 원가 = max(시가, 트리거) — 갭 상승이면 더 비싼 시가</li>
 *   <li>수량 = floor(트랜치 금액 ÷ 원가), 비용 포함 현금 한도로 다시 깎는다</li>
 * </ul>
 */
final class ScaleInMath {

    private ScaleInMath() {
    }

    /** 사다리 한 칸의 상태 — 1차 진입 때 고정되고 채운 단계 수만 늘어난다 */
    record Ladder(double firstFill, double atr, double stopDistance, double trancheAmount, int filled) {
        Ladder withFilled(int newFilled) {
            return new Ladder(firstFill, atr, stopDistance, trancheAmount, newFilled);
        }
    }

    /** n차(2 이상) 트리거 가격 */
    static double triggerPrice(double firstFill, double atr, double stepAtr, int trancheNo) {
        if (trancheNo < 2) throw new IllegalArgumentException("트리거는 2차부터: " + trancheNo);
        return firstFill + (trancheNo - 1) * stepAtr * atr;
    }

    /** 고가가 트리거에 닿았는가 */
    static boolean triggered(double dayHigh, double trigger) {
        return dayHigh >= trigger;
    }

    /** 추가 매수 원가(슬리피지 전) — 시가가 트리거보다 높으면(갭 상승) 시가 */
    static double addRawPrice(double dayOpen, double trigger) {
        return Math.max(dayOpen, trigger);
    }

    /**
     * 1차 매수 금액. 분할(tranches≥2)이면 목표의 1/tranches. 전량(tranches=1)이면 목표 전부이고,
     * 하락 추세 제한(downtrendLimited)이면 1차분(1/3)만.
     */
    static double firstTrancheAmount(double equity, double targetWeight, int tranches,
                                     boolean downtrendLimited) {
        double target = equity * targetWeight;
        if (tranches >= 2) return target / tranches;
        return downtrendLimited ? target / ScaleInProperties.FIRST_TRANCHE_DIVISOR : target;
    }

    /** 이후 단계(2차·3차)의 매수 금액 — 1차 진입 시점 목표의 1/tranches 고정 */
    static double laterTrancheAmount(double equity, double targetWeight, int tranches) {
        return tranches >= 2 ? equity * targetWeight / tranches : 0.0;
    }

    /**
     * 수량 = floor(금액 ÷ 원가)을 비용 포함 현금 한도로 깎은 값. 0이면 매수하지 않는다.
     * 현금 검사는 {@link BacktestOrderClient}와 같은 식(체결가 × 수량 × (1+수수료))으로 한다.
     */
    static int quantity(double amount, double rawPrice, double slippageRate, double cash) {
        if (amount <= 0 || rawPrice <= 0 || cash <= 0) return 0;
        int byAmount = (int) Math.floor(amount / rawPrice);
        double fill = BacktestCosts.buyFillPrice(rawPrice, slippageRate);
        int byCash = (int) Math.floor(cash / BacktestCosts.buyCashOut(fill, 1));
        int qty = Math.max(0, Math.min(byAmount, byCash));
        while (qty > 0 && BacktestCosts.buyCashOut(fill, qty) > cash) qty--; // 부동소수 경계 방어
        return qty;
    }

    /** 추가 매수 뒤 손절선 — 1차 기준이면 불변, 평균단가 기준이면 평균 − 손절폭 */
    static double rebasedStop(ScaleInProperties.StopBasis basis, double firstFill,
                              double averagePrice, double stopDistance) {
        return basis == ScaleInProperties.StopBasis.AVERAGE
                ? averagePrice - stopDistance
                : firstFill - stopDistance;
    }
}
