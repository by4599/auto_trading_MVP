package com.trading.position;

import com.trading.order.OrderHistory;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

class PerformanceBackfillServiceTest {

    private OrderHistoryRepository orderHistoryRepository;
    private TradeResultRepository tradeResultRepository;
    private PerformanceBackfillService sut;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 7, 1, 9, 30);

    @BeforeEach
    void setUp() {
        orderHistoryRepository = mock(OrderHistoryRepository.class);
        tradeResultRepository = mock(TradeResultRepository.class);
        when(tradeResultRepository.findFirstBySourceOrderBySoldAtAsc(TradeResult.Source.LIVE))
                .thenReturn(Optional.empty());
        sut = new PerformanceBackfillService(orderHistoryRepository, tradeResultRepository);
    }

    /** 체결 이력 스텁 — filledAt 시각을 부여해 재생 순서를 고정한다 */
    private static OrderHistory fill(String code, OrderSide side, int qty, double price, LocalDateTime at) {
        OrderHistory o = OrderHistory.accepted(code, side, qty, "ORD-" + at.toString());
        ReflectionTestUtils.setField(o, "filledQuantity", qty);
        ReflectionTestUtils.setField(o, "filledPrice", price);
        ReflectionTestUtils.setField(o, "filledAt", at);
        return o;
    }

    @Test
    void buy_then_sell_creates_backfill_with_profit() {
        when(orderHistoryRepository.findByFilledQuantityGreaterThan(0)).thenReturn(List.of(
                fill("005930", OrderSide.BUY,  10, 70_000, T0),
                fill("005930", OrderSide.SELL, 10, 74_000, T0.plusHours(2))));

        var result = sut.backfill();

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.warnings()).isEmpty();

        ArgumentCaptor<TradeResult> captor = ArgumentCaptor.forClass(TradeResult.class);
        verify(tradeResultRepository).save(captor.capture());
        TradeResult saved = captor.getValue();
        assertThat(saved.getRealizedPnl()).isCloseTo(40_000, within(0.01)); // (74000-70000)*10
        assertThat(saved.getSource()).isEqualTo(TradeResult.Source.BACKFILL);
    }

    @Test
    void moving_average_price_is_used_for_partial_buys() {
        // 10주@70,000 + 10주@80,000 → 평단 75,000. 이후 20주@73,000 매도 = -40,000
        when(orderHistoryRepository.findByFilledQuantityGreaterThan(0)).thenReturn(List.of(
                fill("005930", OrderSide.BUY,  10, 70_000, T0),
                fill("005930", OrderSide.BUY,  10, 80_000, T0.plusMinutes(30)),
                fill("005930", OrderSide.SELL, 20, 73_000, T0.plusHours(3))));

        sut.backfill();

        ArgumentCaptor<TradeResult> captor = ArgumentCaptor.forClass(TradeResult.class);
        verify(tradeResultRepository).save(captor.capture());
        assertThat(captor.getValue().getRealizedPnl()).isCloseTo(-40_000, within(0.01));
    }

    @Test
    void sell_exceeding_replayed_quantity_is_skipped_with_warning() {
        when(orderHistoryRepository.findByFilledQuantityGreaterThan(0)).thenReturn(List.of(
                fill("005930", OrderSide.SELL, 5, 70_000, T0))); // 매수 기록 없음

        var result = sut.backfill();

        assertThat(result.created()).isZero();
        assertThat(result.warnings()).hasSize(1);
        verify(tradeResultRepository, never()).save(any());
    }

    @Test
    void fills_after_live_cutoff_are_not_duplicated() {
        TradeResult live = TradeResult.live("005930", 1, 70_000, 71_000);
        ReflectionTestUtils.setField(live, "soldAt", T0.plusHours(1));
        when(tradeResultRepository.findFirstBySourceOrderBySoldAtAsc(TradeResult.Source.LIVE))
                .thenReturn(Optional.of(live));

        when(orderHistoryRepository.findByFilledQuantityGreaterThan(0)).thenReturn(List.of(
                fill("005930", OrderSide.BUY,  10, 70_000, T0),
                fill("005930", OrderSide.SELL,  5, 72_000, T0.plusMinutes(30)),  // cutoff 이전 — 생성
                fill("005930", OrderSide.SELL,  5, 74_000, T0.plusHours(2))));   // cutoff 이후 — 스킵

        var result = sut.backfill();

        assertThat(result.created()).isEqualTo(1);
    }

    @Test
    void backfill_is_idempotent_deletes_previous_backfill_records() {
        when(orderHistoryRepository.findByFilledQuantityGreaterThan(0)).thenReturn(List.of());

        sut.backfill();

        verify(tradeResultRepository).deleteBySource(TradeResult.Source.BACKFILL);
    }
}
