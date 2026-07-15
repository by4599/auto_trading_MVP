package com.trading.order;

import com.trading.risk.RiskLimitsProperties;
import com.trading.market.AtrCalculator;
import com.trading.market.Candle;
import com.trading.market.MarketDataService;
import com.trading.position.Account;
import com.trading.position.PositionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R 기반 수량 역산 (방법론 §4.3).
 * ATR은 실계산(AtrCalculator) — 갭 없는 일정 레인지 캔들로 ATR = 레인지가 되게 구성한다.
 * 손절폭 = ATR × 1.5 (RiskLimits.ATR_STOP_MULTIPLIER).
 */
@DisplayName("OrderSizingService — 1R 수량 역산·단주 내림·왜곡 스킵")
class OrderSizingServiceTest {

    private MarketDataService marketDataService;
    private PositionManager positionManager;
    private OrderSizingService sut;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        positionManager = mock(PositionManager.class);
        sut = new OrderSizingService(marketDataService, positionManager, new AtrCalculator(), new RiskLimitsProperties());
    }

    private void givenAtr(double atr) {
        List<Candle> candles = new ArrayList<>();
        double mid = 100_000;
        for (int i = 0; i < 15; i++) {
            candles.add(new Candle(LocalDate.now().minusDays(15 - i),
                    mid, mid + atr / 2, mid - atr / 2, mid, 1000));
        }
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(candles);
    }

    private void givenEquity(double equity) {
        when(positionManager.snapshotAccount())
                .thenReturn(new Account(equity, 0.0, 0, List.of()));
    }

    @Test
    @DisplayName("1R=10만, 손절폭=3천 → 33주 (내림), 왜곡 1%로 통과")
    void computes_floor_quantity() {
        givenAtr(2_000);              // 손절폭 = 2000 × 1.5 = 3,000
        givenEquity(10_000_000);      // 1R = 100,000

        OrderSizingService.SizingResult result = sut.sizeBuy("005930");

        assertThat(result.executable()).isTrue();
        assertThat(result.quantity()).isEqualTo(33);   // floor(100000/3000)=33
        assertThat(result.stopDistance()).isEqualTo(3_000.0);
    }

    @Test
    @DisplayName("1주 리스크가 1R 초과 (고가 종목) → 스킵")
    void skips_when_one_share_exceeds_one_r() {
        givenAtr(100_000);            // 손절폭 = 150,000 > 1R 100,000
        givenEquity(10_000_000);

        OrderSizingService.SizingResult result = sut.sizeBuy("005930");

        assertThat(result.executable()).isFalse();
        assertThat(result.skipReason()).contains("1R");
    }

    @Test
    @DisplayName("단주 내림 왜곡 40% > 한도 20% → 스킵")
    void skips_on_rounding_distortion() {
        givenAtr(40_000);             // 손절폭 = 60,000 → qty=1, 실제리스크 60,000 (1R 대비 -40%)
        givenEquity(10_000_000);

        OrderSizingService.SizingResult result = sut.sizeBuy("005930");

        assertThat(result.executable()).isFalse();
        assertThat(result.skipReason()).contains("왜곡");
    }

    @Test
    @DisplayName("일봉 부족으로 ATR 산출 불가 → 스킵")
    void skips_when_atr_unavailable() {
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(List.of());
        givenEquity(10_000_000);

        assertThat(sut.sizeBuy("005930").executable()).isFalse();
    }

    @Test
    @DisplayName("일봉 조회 예외 → 스킵 (예외 전파 없음)")
    void skips_on_market_data_failure() {
        when(marketDataService.getDailyCandles(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("KIS 장애"));
        givenEquity(10_000_000);

        assertThat(sut.sizeBuy("005930").executable()).isFalse();
    }

    @Test
    @DisplayName("총자산 조회 불가 (equity<=0) → 스킵")
    void skips_when_equity_unknown() {
        givenAtr(2_000);
        givenEquity(0);

        assertThat(sut.sizeBuy("005930").executable()).isFalse();
    }
}
