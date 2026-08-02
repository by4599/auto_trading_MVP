package com.trading.strategy;

import com.trading.bucket.StrategyBucket;
import com.trading.market.Candle;
import com.trading.market.MarketDataService;
import com.trading.signal.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 돈치안 채널 돌파 전략(전략1=VB 대체 후보) — 직전 N일 고가 돌파 + 장기 추세 필터.
 */
@DisplayName("DonchianBreakoutStrategy — N일 고가 돌파 + 추세 필터")
class DonchianBreakoutStrategyTest {

    private MarketDataService marketDataService;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);
    private DonchianProperties properties;
    private DonchianBreakoutStrategy sut;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        properties = new DonchianProperties();
        properties.setEnabled(true); // 기본 false이므로 검증 케이스는 명시적으로 켠다
        sut = new DonchianBreakoutStrategy(marketDataService, new MovingAverageCalculator(), clock, properties);
    }

    /** 과거→최신 단조 증가 (high=close) — 직전 20일 고가는 뒤쪽(약 223), MA120은 그보다 낮다(≈164) */
    private List<Candle> risingHistory(int count, double startClose) {
        List<Candle> list = new ArrayList<>();
        LocalDate base = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < count; i++) {
            double px = startClose + i;
            list.add(new Candle(base.plusDays(i), px, px, px, px, 1000));
        }
        return list;
    }

    private List<Candle> tick(double currentPrice) {
        Candle today = new Candle(LocalDate.of(2026, 7, 20), 100, currentPrice + 1, 99, currentPrice, 1000);
        return List.of(today);
    }

    @Test
    @DisplayName("직전 20일 고가 상향 돌파 + 상승추세 → BUY, bucket=VB")
    void buys_on_breakout_in_uptrend() {
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(risingHistory(125, 100));

        List<Signal> signals = sut.evaluate("005930", tick(300)); // 300 > 직전고가(≈223) & > MA120(≈164)

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).isBuy()).isTrue();
        assertThat(signals.get(0).getBucket()).isEqualTo(StrategyBucket.VB);
    }

    @Test
    @DisplayName("직전 20일 고가 미만이면 돌파 아님 → 신호 없음")
    void no_signal_below_prior_high() {
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(risingHistory(125, 100));

        List<Signal> signals = sut.evaluate("005930", tick(210)); // 210 < 직전고가(≈223)

        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("enabled=false면 돌파여도 신호 없음")
    void no_signal_when_disabled() {
        properties.setEnabled(false);
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(risingHistory(125, 100));

        List<Signal> signals = sut.evaluate("005930", tick(300));

        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("일봉이 trendMaPeriod(120)+1 미만이면 신호 없음")
    void no_signal_when_insufficient_history() {
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(risingHistory(50, 100));

        List<Signal> signals = sut.evaluate("005930", tick(300));

        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("같은 날 재호출 시 일봉 재조회 안 함 (일별 캐시)")
    void caches_daily_history_within_same_day() {
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(risingHistory(125, 100));

        sut.evaluate("005930", tick(300));
        sut.evaluate("005930", tick(305));

        verify(marketDataService, times(1)).getDailyCandles(anyString(), anyInt());
    }
}
