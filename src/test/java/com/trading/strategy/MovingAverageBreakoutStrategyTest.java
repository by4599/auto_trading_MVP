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
import static org.mockito.Mockito.when;

/**
 * 이동평균선 정배열 돌파 전략(방식2) — 검증 없이 모의투자 직결(2026-07-20, 사용자 판단).
 */
@DisplayName("MovingAverageBreakoutStrategy — 정배열 + 20일선 돌파")
class MovingAverageBreakoutStrategyTest {

    private MarketDataService marketDataService;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);
    private MovingAverageBreakoutStrategy sut;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        sut = new MovingAverageBreakoutStrategy(marketDataService, new MovingAverageCalculator(), clock);
    }

    /** 과거→최신 순 단조 증가 종가 125개 — 정배열(MA5>MA20>MA60>MA120)이 자연히 성립 */
    private List<Candle> risingHistory(int count, double startClose) {
        List<Candle> list = new ArrayList<>();
        LocalDate base = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < count; i++) {
            double close = startClose + i;
            list.add(new Candle(base.plusDays(i), close, close, close, close, 1000));
        }
        return list;
    }

    private List<Candle> flatHistory(int count, double close) {
        List<Candle> list = new ArrayList<>();
        LocalDate base = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < count; i++) {
            list.add(new Candle(base.plusDays(i), close, close, close, close, 1000));
        }
        return list;
    }

    private List<Candle> tick(double currentPrice) {
        Candle yesterday = new Candle(LocalDate.of(2026, 7, 19), 100, 101, 99, 100, 1000);
        Candle today = new Candle(LocalDate.of(2026, 7, 20), 100, currentPrice + 1, 99, currentPrice, 1000);
        return List.of(yesterday, today);
    }

    @Test
    @DisplayName("정배열 + 현재가가 20일선 상향 돌파 → BUY, bucket=EVENT")
    void buys_on_aligned_breakout() {
        when(marketDataService.getDailyCandles(anyString(), anyInt()))
                .thenReturn(risingHistory(125, 100));

        // 마지막 완성 종가는 100+124=224, MA20은 이보다 낮으므로 그보다 훨씬 위인 현재가는 확실히 돌파
        List<Signal> signals = sut.evaluate("005930", tick(300));

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).isBuy()).isTrue();
        assertThat(signals.get(0).getBucket()).isEqualTo(StrategyBucket.EVENT);
    }

    @Test
    @DisplayName("정배열이 아니면(횡보) 신호 없음")
    void no_signal_when_not_aligned() {
        when(marketDataService.getDailyCandles(anyString(), anyInt()))
                .thenReturn(flatHistory(125, 100));

        List<Signal> signals = sut.evaluate("005930", tick(100.5));

        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("정배열이어도 현재가가 20일선 아래면 신호 없음")
    void no_signal_when_below_ma20() {
        when(marketDataService.getDailyCandles(anyString(), anyInt()))
                .thenReturn(risingHistory(125, 100));

        List<Signal> signals = sut.evaluate("005930", tick(1.0)); // 현재가가 극단적으로 낮음

        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("일봉 데이터가 120개 미만이면 신호 없음")
    void no_signal_when_insufficient_history() {
        when(marketDataService.getDailyCandles(anyString(), anyInt()))
                .thenReturn(risingHistory(50, 100));

        List<Signal> signals = sut.evaluate("005930", tick(300));

        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("같은 날 재호출 시 일봉을 다시 조회하지 않는다 (일별 캐시)")
    void caches_daily_history_within_same_day() {
        when(marketDataService.getDailyCandles(anyString(), anyInt()))
                .thenReturn(risingHistory(125, 100));

        sut.evaluate("005930", tick(300));
        sut.evaluate("005930", tick(305));

        org.mockito.Mockito.verify(marketDataService, org.mockito.Mockito.times(1))
                .getDailyCandles(anyString(), anyInt());
    }
}
