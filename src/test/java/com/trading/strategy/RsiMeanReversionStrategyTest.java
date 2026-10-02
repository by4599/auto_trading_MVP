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
 * RSI(2) 평균회귀 전략(전략3=스캘핑 대체 후보) — 장기 추세 필터 + 단기 RSI 과매도.
 */
@DisplayName("RsiMeanReversionStrategy — 추세 필터 + RSI(2) 과매도 종가 진입")
class RsiMeanReversionStrategyTest {

    private MarketDataService marketDataService;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);
    private RsiProperties properties;
    private RsiMeanReversionStrategy sut;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        properties = new RsiProperties();
        properties.setEnabled(true); // 기본 false이므로 검증 케이스는 명시적으로 켠다
        sut = new RsiMeanReversionStrategy(marketDataService, new RsiCalculator(), clock, properties);
    }

    /** 과거→최신 단조 증가 종가 125개 (100..224) — MA120은 낮고 RSI 변화량은 전부 +1 */
    private List<Candle> risingHistory(int count, double startClose) {
        List<Candle> list = new ArrayList<>();
        LocalDate base = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < count; i++) {
            double px = startClose + i;
            list.add(new Candle(base.plusDays(i), px, px, px, px, 1000));
        }
        return list;
    }

    /** 당일 봉 — 전략은 마지막 종가만 읽는다 */
    private List<Candle> tick(double currentClose) {
        return List.of(new Candle(LocalDate.of(2026, 7, 20), 200, currentClose + 1, 99, currentClose, 1000));
    }

    @Test
    @DisplayName("상승추세(종가>MA120) + RSI(2) 과매도 → BUY, bucket=MIX")
    void buys_on_oversold_in_uptrend() {
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(risingHistory(125, 100));

        // 직전 종가 224에서 212로 급락 → RSI(2)≈7.7(<10), 212 > MA120(≈165) → 상승추세 유지
        List<Signal> signals = sut.evaluate("005930", tick(212));

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).isBuy()).isTrue();
        assertThat(signals.get(0).getBucket()).isEqualTo(StrategyBucket.MIX);
    }

    @Test
    @DisplayName("과매도가 아니면(오늘도 상승) 신호 없음")
    void no_signal_when_not_oversold() {
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(risingHistory(125, 100));

        List<Signal> signals = sut.evaluate("005930", tick(230)); // 224→230 상승 → RSI 높음

        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("과매도여도 종가가 MA120 아래면(하락추세) 신호 없음")
    void no_signal_when_below_trend() {
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(risingHistory(125, 100));

        List<Signal> signals = sut.evaluate("005930", tick(150)); // RSI 과매도지만 150 < MA120(≈165)

        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("enabled=false면 과매도여도 신호 없음")
    void no_signal_when_disabled() {
        properties.setEnabled(false);
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(risingHistory(125, 100));

        List<Signal> signals = sut.evaluate("005930", tick(212));

        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("일봉이 trendMaPeriod(120) 미만이면 신호 없음")
    void no_signal_when_insufficient_history() {
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(risingHistory(50, 100));

        List<Signal> signals = sut.evaluate("005930", tick(150));

        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("같은 날 재호출 시 일봉 재조회 안 함 (일별 캐시)")
    void caches_daily_history_within_same_day() {
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(risingHistory(125, 100));

        sut.evaluate("005930", tick(212));
        sut.evaluate("005930", tick(210));

        verify(marketDataService, times(1)).getDailyCandles(anyString(), anyInt());
    }
}
