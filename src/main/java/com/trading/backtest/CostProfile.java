package com.trading.backtest;

import java.util.List;

/**
 * 왕복 거래비용 상향 스윕 프로필 (§14.1).
 *
 * <p>비용은 슬리피지에만 얹는다(수수료·제세는 법정 확정값 — {@link BacktestCostProperties}
 * 클래스 주석 참고). C0(0.41%)는 기존 결과 재현을 확인하는 회귀 앵커다.
 */
record CostProfile(String name, double roundTrip) {

    static final List<CostProfile> SWEEP = List.of(
            new CostProfile("C0 기준 0.41%(회귀 앵커)", 0.0041),
            new CostProfile("C1 0.50%", 0.0050),
            new CostProfile("C2 0.60%", 0.0060),
            new CostProfile("C3 0.70%", 0.0070),
            new CostProfile("C4 0.80%", 0.0080));
}
