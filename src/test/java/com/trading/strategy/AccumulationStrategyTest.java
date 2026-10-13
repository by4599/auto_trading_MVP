package com.trading.strategy;

import com.trading.bucket.StrategyBucket;
import com.trading.market.Candle;
import com.trading.market.MarketDataService;
import com.trading.signal.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 매집 흔적 전략 — ① 기본 OFF ② 완성 봉(getDailyCandles)만 본다 ③ <b>당일 봉 인자는 쓰지 않는다</b>
 * (백테스트 당일 봉의 거래량은 하루치 총량 = 미래 정보, §15.8).
 */
@DisplayName("AccumulationStrategy — 완성 봉만 보는 매집 흔적 진입 (기본 OFF)")
class AccumulationStrategyTest {

    private static final String CODE = "005930";
    private static final LocalDate START = LocalDate.of(2025, 1, 1);

    private MarketDataService market;
    private AccumulationProperties properties;
    private AccumulationStrategy sut;

    @BeforeEach
    void setUp() {
        market = mock(MarketDataService.class); // 인터페이스 목 (Java 25 Mockito 제약 준수)
        properties = new AccumulationProperties();
        sut = new AccumulationStrategy(market, properties);
    }

    /** 완만한 상승 121봉 — 마지막 봉 거래량 lastVolume */
    private static List<Candle> history(long lastVolume) {
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 121; i++) {
            double c = 100 + 0.05 * i;
            bars.add(new Candle(START.plusDays(i), c, c + 0.5, c - 0.5, c, i == 120 ? lastVolume : 1000));
        }
        return bars;
    }

    private static List<Candle> todayWithVolume(long volume) {
        Candle yesterday = new Candle(START.plusDays(120), 106, 106.5, 105.5, 106, 1000);
        Candle today = new Candle(START.plusDays(121), 106, 107, 105, 106.2, volume);
        return List.of(yesterday, today);
    }

    @Test
    @DisplayName("기본값은 OFF — 신호도 없고 일봉 조회도 하지 않는다")
    void disabledByDefault() {
        assertThat(properties.isEnabled()).isFalse();

        assertThat(sut.evaluate(CODE, todayWithVolume(1000))).isEmpty();
        verify(market, never()).getDailyCandles(anyString(), anyInt());
    }

    @Test
    @DisplayName("켜져 있고 완성 봉에 흔적이 있으면 매수 신호(A동 TREND 칸 — 돈치안과 같은 이름표)")
    void firesBuyOnCompletedBars() {
        properties.setEnabled(true);
        properties.setRule(AccumulationRule.quietSpike(3.0));
        when(market.getDailyCandles(eq(CODE), anyInt())).thenReturn(history(3000));

        List<Signal> signals = sut.evaluate(CODE, todayWithVolume(1000));

        assertThat(signals).singleElement().satisfies(s -> {
            assertThat(s.isBuy()).isTrue();
            assertThat(s.getStrategyName()).isEqualTo("ACCUMULATION_TRACE");
            assertThat(s.getBucket()).isEqualTo(StrategyBucket.TREND);
        });
    }

    @Test
    @DisplayName("당일 봉 인자는 쓰지 않는다 — 당일 거래량을 10억 주로 바꿔도 결과가 같다(선견편향 차단)")
    void ignoresTodayCandleArgument() {
        properties.setEnabled(true);
        properties.setRule(AccumulationRule.quietSpike(3.0));
        when(market.getDailyCandles(eq(CODE), anyInt())).thenReturn(history(1000)); // 완성 봉엔 흔적 없음

        assertThat(sut.evaluate(CODE, todayWithVolume(1_000_000_000L))).isEmpty();
        assertThat(sut.evaluate(CODE, todayWithVolume(1))).isEmpty();
    }

    @Test
    @DisplayName("규칙이 요구하는 만큼(추세 조건 121봉 이상) 완성 봉을 요청한다")
    void requestsEnoughCompletedBars() {
        properties.setEnabled(true);
        properties.setRule(AccumulationRule.quietSpike(3.0));
        when(market.getDailyCandles(eq(CODE), anyInt())).thenReturn(history(3000));

        sut.evaluate(CODE, todayWithVolume(1000));

        ArgumentCaptor<Integer> count = ArgumentCaptor.forClass(Integer.class);
        verify(market).getDailyCandles(eq(CODE), count.capture());
        assertThat(count.getValue()).isGreaterThanOrEqualTo(AccumulationRule.quietSpike(3.0).requiredBars());
    }

    @Test
    @DisplayName("일봉 조회가 실패하면 신호 없이 넘어간다 (예외를 던지지 않는다)")
    void fetchFailureMeansNoSignal() {
        properties.setEnabled(true);
        when(market.getDailyCandles(eq(CODE), anyInt())).thenThrow(new IllegalStateException("시리즈 미적재"));

        assertThat(sut.evaluate(CODE, todayWithVolume(1000))).isEmpty();
    }

    @Test
    @DisplayName("resetDefaults는 OFF·기본 규칙으로 되돌린다")
    void resetDefaults() {
        properties.setEnabled(true);
        properties.setRule(AccumulationRule.clusterBase(4, 0.11));

        properties.resetDefaults();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getRule()).isEqualTo(AccumulationRule.quietSpike(3.0));
    }
}
