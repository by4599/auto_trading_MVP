package com.trading.backtest;

import com.trading.market.Candle;
import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.Timeframe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 유동성 필터 (B-3 유니버스 확장 재검증 신설) — 일평균 거래대금 임계값 필터링 검증.
 */
@DisplayName("LiquidityScreener — 일평균 거래대금 필터")
class LiquidityScreenerTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 18);

    private CandleHistoryRepository candleHistoryRepository;
    private LiquidityScreener sut;

    @BeforeEach
    void setUp() {
        candleHistoryRepository = mock(CandleHistoryRepository.class);
        sut = new LiquidityScreener(candleHistoryRepository);
    }

    private void givenCandles(String symbol, double close, long volume, int days) {
        List<CandleHistory> candles = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            candles.add(CandleHistory.ofDaily(symbol,
                    new Candle(AS_OF.minusDays(days - i), close, close + 1, close - 1, close, volume)));
        }
        when(candleHistoryRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        eq(symbol), eq(Timeframe.DAILY), any(), any()))
                .thenReturn(candles);
    }

    @Test
    @DisplayName("일평균 거래대금이 기준 이상이면 통과")
    void keeps_symbol_above_threshold() {
        givenCandles("005930", 70_000, 20_000_000, 20); // 70,000 × 20,000,000 = 1.4조/일

        List<String> result = sut.filter(List.of("005930"), AS_OF, 5_000_000_000d);

        assertThat(result).containsExactly("005930");
    }

    @Test
    @DisplayName("일평균 거래대금이 기준 미달이면 제외")
    void excludes_symbol_below_threshold() {
        givenCandles("999999", 5_000, 10_000, 20); // 5,000 × 10,000 = 5천만/일

        List<String> result = sut.filter(List.of("999999"), AS_OF, 5_000_000_000d);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("캔들 없는 종목은 거래대금 0으로 취급되어 제외")
    void excludes_symbol_without_candles() {
        when(candleHistoryRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        any(), any(), any(), any()))
                .thenReturn(List.of());

        assertThat(sut.filter(List.of("000000"), AS_OF, 1)).isEmpty();
    }

    @Test
    @DisplayName("최근 20거래일만 평균에 반영 (그 이전 캔들은 무시)")
    void only_uses_recent_window() {
        // 앞쪽(오래된) 40일은 저유동성, 최근 20일만 고유동성으로 채워 평균이 최근 값에 좌우되는지 확인
        List<CandleHistory> candles = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            candles.add(CandleHistory.ofDaily("123456",
                    new Candle(AS_OF.minusDays(60 - i), 1000, 1001, 999, 1000, 1))); // 거래대금 1,000
        }
        for (int i = 0; i < 20; i++) {
            candles.add(CandleHistory.ofDaily("123456",
                    new Candle(AS_OF.minusDays(20 - i), 70_000, 70_001, 69_999, 70_000, 20_000_000)));
        }
        when(candleHistoryRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        eq("123456"), eq(Timeframe.DAILY), any(), any()))
                .thenReturn(candles);

        assertThat(sut.filter(List.of("123456"), AS_OF, 5_000_000_000d)).containsExactly("123456");
    }
}
