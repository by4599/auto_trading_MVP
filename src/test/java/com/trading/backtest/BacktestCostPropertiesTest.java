package com.trading.backtest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("BacktestCostProperties — 왕복 비용 역산(cost-lab §14.1)")
class BacktestCostPropertiesTest {

    private static final double EPS = 1e-9;

    private BacktestCostProperties costs;

    @BeforeEach
    void setUp() {
        costs = new BacktestCostProperties();
    }

    @Test
    @DisplayName("기본 왕복비용은 기존 상수 0.41%와 같다 (다른 모드 회귀 방지)")
    void defaultMatchesLegacyConstant() {
        assertThat(costs.getSlippageRate()).isCloseTo(BacktestCosts.SLIPPAGE_RATE, within(EPS));
        assertThat(costs.roundTripCost()).isCloseTo(0.0041, within(EPS));
        assertThat(costs.roundTripCost()).isCloseTo(BacktestCosts.ROUND_TRIP_COST, within(EPS));
    }

    @Test
    @DisplayName("목표 왕복 0.60% → 편도 슬리피지 0.195%로 역산")
    void setRoundTripCost60bp() {
        costs.setRoundTripCost(0.006);

        assertThat(costs.getSlippageRate()).isCloseTo(0.00195, within(EPS));
        assertThat(costs.roundTripCost()).isCloseTo(0.006, within(EPS));
    }

    @Test
    @DisplayName("목표 왕복 0.80% → 편도 슬리피지 0.295%로 역산")
    void setRoundTripCost80bp() {
        costs.setRoundTripCost(0.008);

        assertThat(costs.getSlippageRate()).isCloseTo(0.00295, within(EPS));
        assertThat(costs.roundTripCost()).isCloseTo(0.008, within(EPS));
    }

    @Test
    @DisplayName("법정 확정비용(수수료×2+제세=0.21%) 하한 미만 목표는 거부한다")
    void rejectsTargetBelowStatutoryFloor() {
        assertThatThrownBy(() -> costs.setRoundTripCost(0.001))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(costs.getSlippageRate()).isCloseTo(BacktestCosts.SLIPPAGE_RATE, within(EPS));
    }

    @Test
    @DisplayName("resetDefaults()는 왕복 0.41%로 되돌린다 (모드 간 누출 방지)")
    void resetDefaultsRestoresBaseline() {
        costs.setRoundTripCost(0.008);
        costs.resetDefaults();

        assertThat(costs.getSlippageRate()).isCloseTo(BacktestCosts.SLIPPAGE_RATE, within(EPS));
        assertThat(costs.roundTripCost()).isCloseTo(0.0041, within(EPS));
    }

    @Test
    @DisplayName("buyFillPrice 오버로드는 지정 슬리피지를 쓰고, 기본 시그니처는 0.1%를 유지한다")
    void fillPriceOverloadUsesGivenSlippage() {
        assertThat(BacktestCosts.buyFillPrice(100, 0.00295)).isCloseTo(100 * 1.00295, within(EPS));
        assertThat(BacktestCosts.sellFillPrice(100, 0.00295)).isCloseTo(100 * 0.99705, within(EPS));

        assertThat(BacktestCosts.buyFillPrice(100)).isCloseTo(100 * 1.001, within(EPS));
        assertThat(BacktestCosts.sellFillPrice(100)).isCloseTo(100 * 0.999, within(EPS));
    }
}
