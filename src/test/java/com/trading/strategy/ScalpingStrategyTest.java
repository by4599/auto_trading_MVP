package com.trading.strategy;

import com.trading.bucket.StrategyBucket;
import com.trading.market.Candle;
import com.trading.signal.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 눌림목 반등 스캘핑 전략(방식3) — 검증 없이 모의투자 직결(2026-07-20, 사용자 판단).
 */
@DisplayName("ScalpingStrategy — 눌림목 반등 모멘텀")
class ScalpingStrategyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 7, 20);

    private ScalpingProperties properties;
    private ScalpingStrategy sut;

    @BeforeEach
    void setUp() {
        properties = new ScalpingProperties();
        properties.setEnabled(true);
        properties.setWindowSize(5);
        properties.setPullbackPct(0.05);
        properties.setReboundPct(0.02);
        sut = new ScalpingStrategy(properties);
    }

    private List<Candle> tick(double price) {
        return List.of(new Candle(DAY, price, price + 1, price - 1, price, 1000));
    }

    private List<Signal> feed(String stockCode, double... prices) {
        List<Signal> last = List.of();
        for (double p : prices) {
            last = sut.evaluate(stockCode, tick(p));
        }
        return last;
    }

    @Test
    @DisplayName("고점 대비 5% 눌린 뒤 저점 대비 2% 반등 → BUY, bucket=MIX")
    void buys_on_pullback_then_rebound() {
        // 창(5) 채우기: 100,100,100,100,94 → 이후 96으로 반등 (창이 [100,100,100,94,96]으로 슬라이드)
        List<Signal> signals = feed("005930", 100, 100, 100, 100, 94, 96);

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).isBuy()).isTrue();
        assertThat(signals.get(0).getBucket()).isEqualTo(StrategyBucket.MIX);
    }

    @Test
    @DisplayName("눌림 없이 계속 같은 가격이면 신호 없음")
    void no_signal_without_pullback() {
        List<Signal> signals = feed("005930", 100, 100, 100, 100, 100, 100);

        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("눌렸지만 아직 반등하지 않으면(저점 유지) 신호 없음")
    void no_signal_without_rebound() {
        List<Signal> signals = feed("005930", 100, 100, 100, 100, 94);

        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("창이 아직 다 안 찼으면(windowSize 미만) 신호 없음")
    void no_signal_when_window_not_full() {
        List<Signal> signals = feed("005930", 100, 94, 96);

        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("스캘핑 비활성이면 조건이 맞아도 신호 없음")
    void no_signal_when_disabled() {
        properties.setEnabled(false);

        List<Signal> signals = feed("005930", 100, 100, 100, 100, 94, 96);

        assertThat(signals).isEmpty();
    }
}
