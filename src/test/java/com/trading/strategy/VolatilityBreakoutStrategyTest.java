package com.trading.strategy;

import com.trading.market.Candle;
import com.trading.signal.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * enabled 스위치(BACKTEST-DESIGN §13 — 방식2·3 소급 검증에서 VB/MA돌파/스캘핑을
 * 한 번에 하나씩만 켜기 위한 격리 장치) 검증. 나머지 돌파 로직은 BreakoutCalculatorTest
 * 및 DailyBarSimulatorTest에서 간접 검증된다.
 */
@DisplayName("VolatilityBreakoutStrategy — enabled 스위치")
class VolatilityBreakoutStrategyTest {

    private StrategyParameters parameters;
    private VolatilityBreakoutStrategy sut;

    @BeforeEach
    void setUp() {
        parameters = new StrategyParameters();
        sut = new VolatilityBreakoutStrategy(parameters, new FilterProperties());
    }

    private List<Candle> breakoutCandles() {
        // 전일 range 20 (110-90), K=0.5 → 돌파가 = 시가100+10=110. 당일 종가 120으로 확실히 돌파.
        Candle yesterday = new Candle(LocalDate.of(2026, 7, 20), 100, 110, 90, 105, 1000);
        Candle today = new Candle(LocalDate.of(2026, 7, 21), 100, 125, 99, 120, 1000);
        return List.of(yesterday, today);
    }

    @Test
    @DisplayName("enabled 기본값 true — 돌파 시 기존과 동일하게 BUY")
    void buys_on_breakout_when_enabled_default() {
        List<Signal> signals = sut.evaluate("005930", breakoutCandles());

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).isBuy()).isTrue();
    }

    @Test
    @DisplayName("enabled=false면 돌파 조건을 만족해도 신호를 내지 않는다")
    void no_signal_when_disabled() {
        parameters.setEnabled(false);

        List<Signal> signals = sut.evaluate("005930", breakoutCandles());

        assertThat(signals).isEmpty();
    }
}
