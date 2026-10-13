package com.trading.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대시보드 체결 내역 조회 쿼리가 <b>실제 DB에서</b> 도는지 확인한다.
 *
 * <p>목으로만 검증하면 "컨트롤러가 리포지토리를 불렀다"까지만 증명된다 — 파생 쿼리 이름이
 * 잘못되면 그 사실은 애플리케이션 기동 때(즉 운영에서) 드러난다. 여기서 H2로 한 번 돌려
 * 이름 파싱·정렬·페이징·기간 필터를 모두 고정한다.
 *
 * <p>@DataJpaTest는 임베디드 DB로 갈아끼우므로 운영 DB(trading-db)를 건드리지 않는다
 * (FillStateConcurrencyTest와 같은 방식).
 */
@DataJpaTest
@ActiveProfiles("paper")
@DisplayName("OrderHistoryRepository — 대시보드 체결 내역 페이지 조회")
class OrderHistoryQueryTest {

    private static final List<OrderStatus> SETTLED = List.of(
            OrderStatus.FILLED,    OrderStatus.PARTIAL_FILLED,
            OrderStatus.CANCELLED, OrderStatus.FAILED, OrderStatus.CANCEL_FAILED);

    private static final LocalDateTime NO_DATE_LIMIT = LocalDateTime.of(1900, 1, 1, 0, 0);

    @Autowired OrderHistoryRepository orderRepo;

    private void given(String stockCode, OrderStatus status, LocalDateTime requestedAt) {
        OrderHistory o = OrderHistory.accepted(stockCode, OrderSide.BUY, 1, "ORD-" + stockCode);
        ReflectionTestUtils.setField(o, "status", status);
        ReflectionTestUtils.setField(o, "requestedAt", requestedAt);
        orderRepo.save(o);
    }

    private Page<OrderHistory> query(LocalDateTime from, int page, int size) {
        return orderRepo.findByStatusInAndRequestedAtGreaterThanEqualOrderByRequestedAtDesc(
                SETTLED, from, PageRequest.of(page, size));
    }

    @Test
    @DisplayName("최신순으로 정렬되고, 미체결 상태는 섞이지 않는다")
    void newest_first_and_open_orders_excluded() {
        given("000001", OrderStatus.FILLED,           LocalDateTime.of(2026, 9, 10, 9, 0));
        given("000002", OrderStatus.CANCELLED,        LocalDateTime.of(2026, 9, 21, 9, 0));
        given("000003", OrderStatus.ACCEPTED,         LocalDateTime.of(2026, 9, 21, 10, 0));
        given("000004", OrderStatus.CANCEL_REQUESTED, LocalDateTime.of(2026, 9, 21, 11, 0));

        Page<OrderHistory> page = query(NO_DATE_LIMIT, 0, 20);

        assertThat(page.getContent()).extracting(OrderHistory::getStockCode)
                .containsExactly("000002", "000001");
    }

    @Test
    @DisplayName("기간 하한보다 이른 주문은 빠진다")
    void date_lower_bound_filters_older_orders() {
        given("000001", OrderStatus.FILLED, LocalDateTime.of(2026, 9, 10, 9, 0));
        given("000002", OrderStatus.FILLED, LocalDateTime.of(2026, 9, 21, 9, 0));

        Page<OrderHistory> page = query(LocalDateTime.of(2026, 9, 15, 0, 0), 0, 20);

        assertThat(page.getContent()).extracting(OrderHistory::getStockCode)
                .containsExactly("000002");
    }

    @Test
    @DisplayName("페이지 크기와 페이지 번호가 실제로 잘라낸다")
    void paging_slices_the_result() {
        for (int i = 1; i <= 5; i++) {
            given(String.format("%06d", i), OrderStatus.FILLED, LocalDateTime.of(2026, 9, i + 10, 9, 0));
        }

        Page<OrderHistory> first  = query(NO_DATE_LIMIT, 0, 2);
        Page<OrderHistory> second = query(NO_DATE_LIMIT, 1, 2);

        assertThat(first.getContent()).extracting(OrderHistory::getStockCode)
                .containsExactly("000005", "000004");    // 9/15, 9/14
        assertThat(second.getContent()).extracting(OrderHistory::getStockCode)
                .containsExactly("000003", "000002");    // 9/13, 9/12
        assertThat(first.getTotalElements()).isEqualTo(5);
    }
}
