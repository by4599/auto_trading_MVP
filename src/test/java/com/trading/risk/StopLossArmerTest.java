package com.trading.risk;

import com.trading.market.AtrCalculator;
import com.trading.market.Candle;
import com.trading.market.MarketDataService;
import com.trading.order.OrderPartialFilledEvent;
import com.trading.order.OrderSide;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 매수 체결 → ATR 손절선 장착 (방법론 §4.1 — 체결가 기준).
 */
@DisplayName("StopLossArmer — 체결가 기준 손절선 장착")
class StopLossArmerTest {

    private MarketDataService marketDataService;
    private PositionRepository positionRepository;
    private StopLossArmer sut;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        positionRepository = mock(PositionRepository.class);
        sut = new StopLossArmer(marketDataService, new AtrCalculator(), positionRepository, new RiskLimitsProperties());
    }

    private void givenAtr(double range) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            candles.add(new Candle(LocalDate.now().minusDays(15 - i),
                    100_000, 100_000 + range / 2, 100_000 - range / 2, 100_000, 1000));
        }
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(candles);
    }

    @Test
    @DisplayName("체결가 72,500·ATR 2,000 → 손절가 69,500 (72,500 − 2,000×1.5)")
    void arms_stop_at_fill_price_minus_atr_multiple() {
        givenAtr(2_000);
        Position pos = Position.empty("005930");
        pos.applyBuy(33, 72_500.0);
        when(positionRepository.findByStockCode("005930")).thenReturn(Optional.of(pos));

        sut.arm("005930", 72_500.0);

        assertThat(pos.getStopPrice()).isEqualTo(72_500.0 - 2_000.0 * RiskLimits.ATR_STOP_MULTIPLIER);
    }

    @Test
    @DisplayName("ATR 산출 불가 → 손절선 미장착 (타임컷이 최후 방어선)")
    void no_arm_when_atr_unavailable() {
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(List.of());
        Position pos = Position.empty("005930");
        pos.applyBuy(1, 72_500.0);
        when(positionRepository.findByStockCode("005930")).thenReturn(Optional.of(pos));

        sut.arm("005930", 72_500.0);

        assertThat(pos.getStopPrice()).isNull();
    }

    @Test
    @DisplayName("부분 체결(BUY) 이벤트 → 누적 평균 체결가 기준 손절선 장착 (결함 #5 해소)")
    void partial_fill_buy_arms_stop_loss() {
        givenAtr(2_000);
        Position pos = Position.empty("005930");
        pos.applyBuy(10, 72_500.0);
        when(positionRepository.findByStockCode("005930")).thenReturn(Optional.of(pos));

        sut.onOrderPartialFilled(new OrderPartialFilledEvent(OrderSide.BUY, "005930", 10, 72_500.0));

        assertThat(pos.getStopPrice()).isEqualTo(72_500.0 - 2_000.0 * RiskLimits.ATR_STOP_MULTIPLIER);
    }

    @Test
    @DisplayName("부분 체결(SELL) 이벤트 → 아무것도 하지 않음")
    void partial_fill_sell_is_ignored() {
        sut.onOrderPartialFilled(new OrderPartialFilledEvent(OrderSide.SELL, "005930", 10, 72_500.0));

        verifyNoInteractions(marketDataService, positionRepository);
    }

    @Test
    @DisplayName("일봉 조회 예외 → 전파 없이 미장착")
    void exception_does_not_propagate() {
        when(marketDataService.getDailyCandles(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("KIS 장애"));
        Position pos = Position.empty("005930");
        pos.applyBuy(1, 72_500.0);
        when(positionRepository.findByStockCode("005930")).thenReturn(Optional.of(pos));

        sut.arm("005930", 72_500.0); // 예외 없이 리턴

        assertThat(pos.getStopPrice()).isNull();
    }
}
