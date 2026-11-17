package com.trading.risk;

import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import com.trading.position.PositionRepository;
import com.trading.signal.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PendingOrderRuleTest {

    private static final String CODE = "005930";

    private PositionRepository positionRepository;
    private OrderHistoryRepository orderHistoryRepository;
    private PendingOrderRule sut;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        orderHistoryRepository = mock(OrderHistoryRepository.class);
        when(positionRepository.findByStockCode(CODE)).thenReturn(Optional.empty());
        sut = new PendingOrderRule(positionRepository, orderHistoryRepository);
    }

    @Test
    @DisplayName("취소 확인 중(CANCEL_REQUESTED)인 매수 주문이 있으면 재매수를 막는다")
    void rejects_buy_while_cancel_pending() {
        when(orderHistoryRepository.existsByStockCodeAndSideAndStatus(CODE, OrderSide.BUY, OrderStatus.CANCEL_REQUESTED))
                .thenReturn(true);

        RiskResult result = sut.validate(Signal.buy(CODE, "test"), null);

        assertThat(result.isPass()).isFalse();
        assertThat(result.getReason()).contains("취소 확인 중");
    }

    @Test
    @DisplayName("보유도 대기 주문도 없으면 통과한다")
    void passes_when_nothing_pending() {
        assertThat(sut.validate(Signal.buy(CODE, "test"), null).isPass()).isTrue();
    }

    @Test
    @DisplayName("매도는 취소 확인 중인 매수가 있어도 막지 않는다")
    void never_blocks_sell() {
        when(orderHistoryRepository.existsByStockCodeAndSideAndStatus(CODE, OrderSide.BUY, OrderStatus.CANCEL_REQUESTED))
                .thenReturn(true);

        assertThat(sut.validate(Signal.sell(CODE, "test"), null).isPass()).isTrue();
    }
}
