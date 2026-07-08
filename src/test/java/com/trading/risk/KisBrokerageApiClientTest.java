package com.trading.risk;

import com.trading.order.FillStateUpdater;
import com.trading.order.KisOrderClient;
import com.trading.order.OrderCancelClient;
import com.trading.order.OrderHistory;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import com.trading.position.BalanceClient;
import com.trading.position.BalanceClient.BalanceSnapshot;
import com.trading.position.BalanceClient.Holding;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResultTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이 JVM(Java 25)의 Mockito는 구체 클래스를 목킹하지 못하므로,
 * FillStateUpdater는 실객체(+인터페이스 의존성 목킹)로 구성하고
 * 취소 마킹은 엔티티 상태 전이로 검증한다.
 */
class KisBrokerageApiClientTest {

    private BalanceClient balanceClient;
    private KisOrderClient orderClient;
    private OrderCancelClient cancelClient;
    private OrderHistoryRepository orderHistoryRepository;
    private KisBrokerageApiClient sut;

    @BeforeEach
    void setUp() {
        balanceClient = mock(BalanceClient.class);
        orderClient = mock(KisOrderClient.class);
        cancelClient = mock(OrderCancelClient.class);
        orderHistoryRepository = mock(OrderHistoryRepository.class);
        FillStateUpdater fillStateUpdater = new FillStateUpdater(
                orderHistoryRepository, mock(PositionRepository.class),
                mock(ApplicationEventPublisher.class),
                new TradeResultTracker(mock(PortfolioStateRepository.class)));
        sut = new KisBrokerageApiClient(balanceClient, orderClient, cancelClient,
                orderHistoryRepository, fillStateUpdater);
    }

    private static OrderHistory acceptedOrder(long id, String stockCode, String ordNo) {
        OrderHistory order = OrderHistory.accepted(stockCode, OrderSide.BUY, 1, ordNo);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    // ── 잔고 매핑 ─────────────────────────────────────────────────────────────

    @Test
    void actual_account_asset_maps_holdings_from_balance() {
        when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(50_000_000,
                List.of(new Holding("005930", 3, 79_000, 80_000),
                        new Holding("000660", 2, 190_000, 200_000))));

        ActualAccountInfo info = sut.getActualAccountAsset();

        assertThat(info.holdings()).containsExactly(
                new ActualPosition("005930", 3), new ActualPosition("000660", 2));
    }

    @Test
    void holding_quantity_returns_zero_for_unknown_ticker() {
        when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(50_000_000,
                List.of(new Holding("005930", 3, 79_000, 80_000))));

        assertThat(sut.getActualHoldingQuantity("005930")).isEqualTo(3);
        assertThat(sut.getActualHoldingQuantity("000660")).isEqualTo(0);
    }

    // ── 시장가 매도 ───────────────────────────────────────────────────────────

    @Test
    void sendMarketOrder_sell_delegates_with_quantity() {
        sut.sendMarketOrder("005930", "SELL", 3);

        verify(orderClient).sell("005930", 3);
    }

    @Test
    void sendMarketOrder_rejects_buy_side() {
        // ADR 2.3: 청산 경로는 매도 전용 — 매수는 OrderEngine 경로만
        assertThatThrownBy(() -> sut.sendMarketOrder("005930", "BUY", 1))
                .isInstanceOf(IllegalArgumentException.class);
        verify(orderClient, never()).sell("005930", 1);
    }

    // ── 미체결 일괄 취소 ─────────────────────────────────────────────────────

    @Test
    void cancelAllPendingOrders_cancels_each_and_marks_cancel_requested() {
        OrderHistory o1 = acceptedOrder(1L, "005930", "ORD-1");
        OrderHistory o2 = acceptedOrder(2L, "000660", "ORD-2");
        when(orderHistoryRepository.findByStatusIn(anyList())).thenReturn(List.of(o1, o2));
        when(orderHistoryRepository.findById(1L)).thenReturn(Optional.of(o1));
        when(orderHistoryRepository.findById(2L)).thenReturn(Optional.of(o2));
        when(cancelClient.cancelAll("ORD-1")).thenReturn(true);
        when(cancelClient.cancelAll("ORD-2")).thenReturn(true);

        sut.cancelAllPendingOrders();

        assertThat(o1.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(o2.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
    }

    @Test
    void cancel_failure_of_one_order_does_not_stop_the_rest() {
        OrderHistory o1 = acceptedOrder(1L, "005930", "ORD-1");
        OrderHistory o2 = acceptedOrder(2L, "000660", "ORD-2");
        when(orderHistoryRepository.findByStatusIn(anyList())).thenReturn(List.of(o1, o2));
        when(orderHistoryRepository.findById(2L)).thenReturn(Optional.of(o2));
        when(cancelClient.cancelAll("ORD-1")).thenReturn(false); // 접수 실패
        when(cancelClient.cancelAll("ORD-2")).thenReturn(true);

        sut.cancelAllPendingOrders();

        assertThat(o1.getStatus()).isEqualTo(OrderStatus.ACCEPTED);          // 실패 건은 유지
        assertThat(o2.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);  // 나머지는 계속
    }

    @Test
    void cancelAllPendingOrders_is_noop_when_nothing_pending() {
        when(orderHistoryRepository.findByStatusIn(anyList())).thenReturn(List.of());

        sut.cancelAllPendingOrders();

        verify(cancelClient, never()).cancelAll(anyString());
    }
}
