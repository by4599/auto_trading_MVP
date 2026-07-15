package com.trading.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderHistoryRepository extends JpaRepository<OrderHistory, Long> {

    List<OrderHistory> findByStatus(OrderStatus status);

    /** FillPoller: ACCEPTED + PARTIAL_FILLED 동시 조회 */
    List<OrderHistory> findByStatusIn(List<OrderStatus> statuses);

    /** PendingOrderRule: 동일 종목에 ACCEPTED 상태의 매수 주문이 있는지 확인 */
    boolean existsByStockCodeAndSideAndStatus(String stockCode, OrderSide side, OrderStatus status);

    /** PerformanceBackfillService: 체결분이 있는 전 주문 (시간 정렬은 서비스에서) */
    List<OrderHistory> findByFilledQuantityGreaterThan(int filledQuantity);
}
