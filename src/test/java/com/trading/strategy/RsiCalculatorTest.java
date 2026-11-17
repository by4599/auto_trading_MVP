package com.trading.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Wilder RSI 계산기 — RSI(2) 평균회귀 전략용.
 */
@DisplayName("RsiCalculator — Wilder RSI")
class RsiCalculatorTest {

    private final RsiCalculator sut = new RsiCalculator();

    @Test
    @DisplayName("데이터가 period+1개 미만이면 empty")
    void empty_when_insufficient() {
        assertThat(sut.rsi(List.of(10.0, 11.0), 2)).isEmpty(); // period 2엔 3개 필요
        assertThat(sut.rsi(List.of(), 2)).isEmpty();
    }

    @Test
    @DisplayName("전부 상승이면 하락이 없어 RSI=100")
    void rsi_100_when_all_gains() {
        assertThat(sut.rsi(List.of(10.0, 20.0, 30.0, 40.0), 2).getAsDouble())
                .isCloseTo(100.0, within(1e-9));
    }

    @Test
    @DisplayName("전부 하락이면 상승이 없어 RSI=0")
    void rsi_0_when_all_losses() {
        assertThat(sut.rsi(List.of(40.0, 30.0, 20.0, 10.0), 2).getAsDouble())
                .isCloseTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("손으로 계산한 Wilder RSI(2) 값과 일치한다")
    void matches_hand_computed_wilder() {
        // closes=[10,11,10,11] · 변화량 +1,-1,+1
        // 초기(변화량 2개): avgGain=(1+0)/2=0.5, avgLoss=(0+1)/2=0.5
        // 마지막 +1 스무딩: avgGain=(0.5+1)/2=0.75, avgLoss=(0.5+0)/2=0.25
        // RS=3 → RSI=100-100/4=75
        assertThat(sut.rsi(List.of(10.0, 11.0, 10.0, 11.0), 2).getAsDouble())
                .isCloseTo(75.0, within(1e-9));
    }

    @Test
    @DisplayName("긴 상승 뒤 큰 하락 한 방이면 RSI(2)가 과매도(<10)로 급락한다")
    void rsi2_drops_below_10_after_sharp_pullback() {
        // 100부터 +1씩 상승(변화량 전부 +1 → avgGain=1, avgLoss=0), 마지막에 12 급락.
        // 최종 스무딩: avgGain=0.5, avgLoss=6 → RS=1/12 → RSI≈7.7 (<10)
        java.util.ArrayList<Double> closes = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) closes.add(100.0 + i); // 100..129
        closes.add(closes.get(closes.size() - 1) - 12);      // 117
        assertThat(sut.rsi(closes, 2).getAsDouble()).isLessThan(10.0);
    }
}
