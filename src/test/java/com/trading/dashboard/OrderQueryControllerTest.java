package com.trading.dashboard;

import com.trading.order.OrderHistory;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 주문 조회 API — 체결 내역(기간·페이징)과 미체결 주문.
 *
 * <p>기존 /api/orders/filled는 ACCEPTED·CANCEL_REQUESTED를 빼고 보여줘서 <b>미체결 주문이
 * 화면에서 완전히 보이지 않았다.</b> /api/orders/open이 그 구멍을 메운다.
 *
 * <p>리포지토리(인터페이스)만 목으로 만들고 컨트롤러는 실객체로 조립한다 (Java 25 Mockito 제약).
 * 날짜 사실관계(시스템 도구로 검증): 2026-09-21 월.
 */
@DisplayName("OrderQueryController — 체결 내역 / 미체결 주문 조회")
class OrderQueryControllerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate MON_0921 = LocalDate.of(2026, 9, 21);

    private final OrderHistoryRepository orderRepo = mock(OrderHistoryRepository.class);
    private final Clock clock = Clock.fixed(MON_0921.atTime(22, 30).atZone(KST).toInstant(), KST);
    private final OrderQueryController sut = new OrderQueryController(orderRepo, clock);

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private static OrderHistory order(String code, OrderSide side, int qty,
                                      OrderStatus status, LocalDateTime requestedAt) {
        OrderHistory o = OrderHistory.accepted(code, side, qty, "ORD-" + code);
        ReflectionTestUtils.setField(o, "status", status);
        ReflectionTestUtils.setField(o, "requestedAt", requestedAt);
        return o;
    }

    private void givenFilledPage(OrderHistory... rows) {
        when(orderRepo.findByStatusInAndRequestedAtGreaterThanEqualOrderByRequestedAtDesc(
                anyList(), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(rows)));
    }

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepo).findByStatusInAndRequestedAtGreaterThanEqualOrderByRequestedAtDesc(
                anyList(), any(LocalDateTime.class), captor.capture());
        return captor.getValue();
    }

    private LocalDateTime captureFrom() {
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderRepo).findByStatusInAndRequestedAtGreaterThanEqualOrderByRequestedAtDesc(
                anyList(), captor.capture(), any(Pageable.class));
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<OrderStatus> captureStatuses() {
        ArgumentCaptor<List<OrderStatus>> captor = ArgumentCaptor.forClass(List.class);
        verify(orderRepo).findByStatusInAndRequestedAtGreaterThanEqualOrderByRequestedAtDesc(
                captor.capture(), any(LocalDateTime.class), any(Pageable.class));
        return captor.getValue();
    }

    // ── 체결 내역: 기존 동작 보존 ──────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/orders/filled")
    class FilledOrders {

        @Test
        @DisplayName("파라미터가 없으면 지금까지와 똑같다 — 전 기간 최근 20건")
        void defaults_match_previous_behaviour() {
            givenFilledPage();

            sut.getFilledOrders(null, 0, 20);

            Pageable pageable = capturePageable();
            assertThat(pageable.getPageNumber()).isZero();
            assertThat(pageable.getPageSize()).isEqualTo(20);
            // days 미지정 = 기간 제한 없음: 원장 시작보다 이른 경계값이어야 한 건도 잘리지 않는다
            assertThat(captureFrom()).isBefore(LocalDateTime.of(2020, 1, 1, 0, 0));
        }

        @Test
        @DisplayName("응답은 지금처럼 배열이다 — 화면(tab-home.js)이 그대로 읽는다")
        void response_stays_a_plain_array() {
            givenFilledPage(order("005930", OrderSide.BUY, 3, OrderStatus.FILLED,
                    MON_0921.atTime(9, 30)));

            List<Map<String, Object>> rows = sut.getFilledOrders(null, 0, 20);

            assertThat(rows).hasSize(1);
            Map<String, Object> row = rows.get(0);
            assertThat(row.get("stockCode")).isEqualTo("005930");
            assertThat(row.get("side")).isEqualTo("BUY");
            assertThat(row.get("status")).isEqualTo("FILLED");
            assertThat(row.get("requestedAt")).isEqualTo("09/21 09:30");
        }

        @Test
        @DisplayName("미체결 상태는 이 목록에 섞이지 않는다")
        void open_statuses_are_excluded() {
            givenFilledPage();

            sut.getFilledOrders(null, 0, 20);

            assertThat(captureStatuses())
                    .doesNotContain(OrderStatus.ACCEPTED, OrderStatus.CANCEL_REQUESTED)
                    .contains(OrderStatus.FILLED, OrderStatus.PARTIAL_FILLED,
                              OrderStatus.CANCELLED, OrderStatus.FAILED, OrderStatus.CANCEL_FAILED);
        }

        @Test
        @DisplayName("days는 오늘을 포함한 최근 N일 — days=1이면 오늘 0시부터")
        void days_counts_today_inclusive() {
            givenFilledPage();

            sut.getFilledOrders(1, 0, 20);

            assertThat(captureFrom()).isEqualTo(MON_0921.atStartOfDay());
        }

        @Test
        @DisplayName("days=7이면 6일 전 0시부터")
        void days_seven_starts_six_days_earlier() {
            givenFilledPage();

            sut.getFilledOrders(7, 0, 20);

            assertThat(captureFrom()).isEqualTo(LocalDate.of(2026, 9, 15).atStartOfDay());
        }

        @Test
        @DisplayName("page·size가 그대로 전달된다")
        void page_and_size_are_passed_through() {
            givenFilledPage();

            sut.getFilledOrders(null, 3, 50);

            Pageable pageable = capturePageable();
            assertThat(pageable.getPageNumber()).isEqualTo(3);
            assertThat(pageable.getPageSize()).isEqualTo(50);
        }

        @Test
        @DisplayName("size는 1~200, page는 0 이상, days는 1~365로 묶인다")
        void parameters_are_bounded() {
            givenFilledPage();
            sut.getFilledOrders(null, -5, 9_999);
            assertThat(capturePageable().getPageSize()).isEqualTo(200);
            assertThat(capturePageable().getPageNumber()).isZero();

            OrderHistoryRepository repo2 = mock(OrderHistoryRepository.class);
            when(repo2.findByStatusInAndRequestedAtGreaterThanEqualOrderByRequestedAtDesc(
                    anyList(), any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(Page.empty());
            new OrderQueryController(repo2, clock).getFilledOrders(0, 0, 0);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(repo2).findByStatusInAndRequestedAtGreaterThanEqualOrderByRequestedAtDesc(
                    anyList(), from.capture(), captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(1);
            assertThat(from.getValue()).isEqualTo(MON_0921.atStartOfDay());   // days=0 → 1로 묶임
        }
    }

    // ── 미체결 주문: 화면에 아예 없던 것 ───────────────────────────────────────

    @Nested
    @DisplayName("GET /api/orders/open")
    class OpenOrders {

        @Test
        @DisplayName("접수됨·취소요청 두 상태만 최신순으로 돌려준다")
        void returns_accepted_and_cancel_requested_newest_first() {
            when(orderRepo.findByStatusIn(anyList())).thenReturn(List.of(
                    order("005930", OrderSide.BUY, 3, OrderStatus.ACCEPTED,
                            MON_0921.atTime(9, 30)),
                    order("012330", OrderSide.SELL, 1, OrderStatus.CANCEL_REQUESTED,
                            MON_0921.atTime(15, 16))));

            List<Map<String, Object>> rows = sut.getOpenOrders();

            assertThat(rows).extracting(r -> r.get("stockCode"))
                    .containsExactly("012330", "005930");   // 최신 먼저
            assertThat(rows).extracting(r -> r.get("status"))
                    .containsExactly("CANCEL_REQUESTED", "ACCEPTED");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<OrderStatus>> captor = ArgumentCaptor.forClass(List.class);
            verify(orderRepo).findByStatusIn(captor.capture());
            assertThat(captor.getValue())
                    .containsExactlyInAnyOrder(OrderStatus.ACCEPTED, OrderStatus.CANCEL_REQUESTED);
        }

        @Test
        @DisplayName("남은 수량(주문 - 체결)을 함께 준다 — 얼마가 걸려 있는지가 핵심 정보다")
        void exposes_pending_quantity() {
            OrderHistory partial = order("005930", OrderSide.BUY, 10, OrderStatus.CANCEL_REQUESTED,
                    MON_0921.atTime(9, 30));
            ReflectionTestUtils.setField(partial, "filledQuantity", 4);
            when(orderRepo.findByStatusIn(anyList())).thenReturn(List.of(partial));

            Map<String, Object> row = sut.getOpenOrders().get(0);

            assertThat(row.get("quantity")).isEqualTo(10);
            assertThat(row.get("filledQty")).isEqualTo(4);
            assertThat(row.get("pendingQty")).isEqualTo(6);
            assertThat(row.get("orderNo")).isEqualTo("ORD-005930");
        }

        @Test
        @DisplayName("미체결이 없으면 빈 배열")
        void empty_when_nothing_is_pending() {
            when(orderRepo.findByStatusIn(anyList())).thenReturn(List.of());

            assertThat(sut.getOpenOrders()).isEmpty();
        }
    }
}
