package com.trading.dashboard;

import com.trading.order.OrderHistory;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문 내역 조회 API (읽기 전용). DashboardController에서 분리했다 —
 * 기간·페이징과 미체결 조회를 얹으면 그 파일이 300줄 상한을 넘는다.
 *
 * <pre>
 * GET /api/orders/filled?days=&amp;page=&amp;size=   종결된 주문(체결·취소·실패)
 * GET /api/orders/open                          미체결 주문(접수됨·취소요청)
 * </pre>
 *
 * <p>/open이 새로 필요한 이유: /filled는 ACCEPTED·CANCEL_REQUESTED를 목록에서 빼기 때문에
 * <b>지금 걸려 있는 주문이 화면에 전혀 보이지 않았다.</b>
 *
 * <p>/filled의 기본 응답은 이전과 똑같다(파라미터 없으면 전 기간 최근 20건, JSON 배열).
 * 화면(tab-home.js)이 배열을 그대로 읽으므로 감싸는 형태로 바꾸지 않는다.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderQueryController {

    private static final int MAX_SIZE = 200;
    private static final int MAX_DAYS = 365;

    /** days 미지정 = 기간 제한 없음. LocalDateTime.MIN은 DB 날짜 범위를 벗어나므로 안전한 경계값을 쓴다 */
    private static final LocalDateTime NO_DATE_LIMIT = LocalDateTime.of(1900, 1, 1, 0, 0);

    /** 종결된 주문 — 더 이상 시장에 걸려 있지 않은 것들 */
    private static final List<OrderStatus> SETTLED_STATUSES = List.of(
            OrderStatus.FILLED,    OrderStatus.PARTIAL_FILLED,
            OrderStatus.CANCELLED, OrderStatus.FAILED, OrderStatus.CANCEL_FAILED);

    /** 아직 시장에 걸려 있는 주문 */
    private static final List<OrderStatus> OPEN_STATUSES = List.of(
            OrderStatus.ACCEPTED, OrderStatus.CANCEL_REQUESTED);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final OrderHistoryRepository orderHistoryRepository;
    private final Clock clock;

    public OrderQueryController(OrderHistoryRepository orderHistoryRepository, Clock clock) {
        this.orderHistoryRepository = orderHistoryRepository;
        this.clock = clock;
    }

    // ── 1. 종결된 주문 (기간·페이징) ──────────────────────────────────────────

    @GetMapping("/filled")
    public List<Map<String, Object>> getFilledOrders(
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.max(1, Math.min(size, MAX_SIZE)));

        return orderHistoryRepository
                .findByStatusInAndRequestedAtGreaterThanEqualOrderByRequestedAtDesc(
                        SETTLED_STATUSES, since(days), pageable)
                .getContent().stream()
                .map(OrderQueryController::settledRow)
                .toList();
    }

    /** days 미지정이면 기간 제한 없음. 지정하면 오늘을 포함한 최근 N일(일별 원장 API와 같은 셈법) */
    private LocalDateTime since(Integer days) {
        if (days == null) return NO_DATE_LIMIT;
        int bounded = Math.max(1, Math.min(days, MAX_DAYS));
        return LocalDate.now(clock).minusDays(bounded - 1L).atStartOfDay();
    }

    private static Map<String, Object> settledRow(OrderHistory o) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",          o.getId());
        item.put("stockCode",   o.getStockCode());
        item.put("side",        o.getSide().name());
        item.put("quantity",    o.getQuantity());
        item.put("filledQty",   o.getFilledQuantity());
        item.put("filledPrice", o.getFilledPrice() != null ? Math.round(o.getFilledPrice()) : null);
        item.put("status",      o.getStatus().name());
        item.put("requestedAt", o.getRequestedAt().format(FMT));
        return item;
    }

    // ── 2. 미체결 주문 ────────────────────────────────────────────────────────

    /** 미체결은 많아야 몇 건이라 페이징 없이 전부 준다 (최대 보유 종목 5개 × 주문 1건 수준) */
    @GetMapping("/open")
    public List<Map<String, Object>> getOpenOrders() {
        return orderHistoryRepository.findByStatusIn(OPEN_STATUSES).stream()
                .sorted(Comparator.comparing(OrderHistory::getRequestedAt).reversed())
                .map(OrderQueryController::openRow)
                .toList();
    }

    private static Map<String, Object> openRow(OrderHistory o) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",                o.getId());
        item.put("stockCode",         o.getStockCode());
        item.put("side",              o.getSide().name());
        item.put("quantity",          o.getQuantity());
        item.put("filledQty",         o.getFilledQuantity());
        item.put("pendingQty",        o.getQuantity() - o.getFilledQuantity());
        item.put("orderNo",           o.getOrderNo());
        item.put("status",            o.getStatus().name());
        item.put("requestedAt",       o.getRequestedAt().format(FMT));
        item.put("cancelRequestedAt", format(o.getCancelRequestedAt()));
        item.put("bucket",            o.getBucket() != null ? o.getBucket().name() : null);
        return item;
    }

    private static String format(LocalDateTime at) {
        return at == null ? null : at.format(FMT);
    }
}
