package com.trading.backtest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Cost Lab(BACKTEST-DESIGN §14.1) 거래비용 스위치 — 백테스트 전용 mutable holder.
 *
 * 왕복 비용 상향 민감도 실험에서 오케스트레이터가 프로필마다 값을 세팅한다
 * (ExitLabProperties·필터 A/B와 동일 패턴).
 *
 * <p><b>왜 슬리피지만 올리는가</b> — 왕복 비용은 세 조각으로 이뤄진다:
 * 위탁수수료(편도 0.015%)와 매도 제세금(0.18%)은 <b>법·약관으로 정해진 확정값</b>이라
 * 시장이 나빠져도 변하지 않는다. 실제로 불확실한 건 슬리피지(주문가와 체결가가
 * 미끄러지는 정도)뿐이다. 따라서 "비용이 더 나빠지면?"이라는 스트레스는 슬리피지에만
 * 얹는 것이 정직하다. 수수료·세금을 같이 부풀리면 현실에 없는 시나리오를 만드는 셈이다.
 *
 * <p>기본값은 기존 상수({@link BacktestCosts#SLIPPAGE_RATE})와 동일하다. 따라서
 * cost-lab 모드가 아닌 다른 백테스트 실행(full/smoke/ma-breakout/scalping/events/
 * exit-lab/risk-lab)의 결과는 이 클래스 도입으로 달라지지 않는다.
 *
 * @Profile("backtest") — paper/real에는 존재하지 않는다(라이브 비용은 실제 체결이 정한다).
 */
@Component
@Profile("backtest")
public class BacktestCostProperties {

    /** 편도 슬리피지. 왕복 비용 중 유일한 가변 조각. */
    private volatile double slippageRate = BacktestCosts.SLIPPAGE_RATE;

    public double getSlippageRate() { return slippageRate; }
    public void setSlippageRate(double slippageRate) { this.slippageRate = slippageRate; }

    /** 현재 슬리피지 기준 왕복 총비용 (슬리피지×2 + 수수료×2 + 매도 제세) */
    public double roundTripCost() {
        return BacktestCosts.roundTripCost(slippageRate);
    }

    /**
     * 목표 왕복 비용에서 편도 슬리피지를 역산해 세팅한다.
     * slippage = (목표 − 수수료×2 − 제세) ÷ 2
     *
     * @throws IllegalArgumentException 목표가 법정 확정비용(수수료×2 + 제세) 하한보다 낮을 때
     *         — 슬리피지를 음수로 만들어야 도달 가능한 값이라 물리적으로 불가능하다.
     */
    public void setRoundTripCost(double target) {
        double fixed = BacktestCosts.COMMISSION_RATE * 2 + BacktestCosts.SELL_TAX_RATE;
        double slippage = (target - fixed) / 2;
        if (slippage < 0) {
            throw new IllegalArgumentException(String.format(
                    "목표 왕복비용 %.5f 가 법정 확정비용 하한 %.5f 보다 낮다 (슬리피지 음수 불가)",
                    target, fixed));
        }
        this.slippageRate = slippage;
    }

    /** 프로필 스윕 사이 기본값 복원 — 다른 모드/런으로의 누출 방지 */
    public void resetDefaults() {
        this.slippageRate = BacktestCosts.SLIPPAGE_RATE;
    }
}
