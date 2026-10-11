package com.trading.order;

import com.trading.bucket.StrategyBucket;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * 주문 생명주기의 source of truth.
 *
 * 상태 전이는 requireTransitionFrom()이 강제한다.
 * 터미널 상태(FILLED / CANCELLED / FAILED / TIMEOUT)에서 다른 상태로 전이를 시도하면
 * IllegalStateException이 발생하므로 "FILLED → ACCEPTED" 역전환이 불가능하다.
 *
 * filledQuantity로 누적 체결량을 추적한다.
 * FillPoller는 (현재 filledQuantity - 이전 filledQuantity)만큼만 Position에 반영해
 * 부분체결이 반복될 때 중복 가산을 막는다.
 */
@Entity
@Table(name = "order_history", indexes = {
        @Index(name = "idx_order_history_status",   columnList = "status"),
        @Index(name = "idx_order_history_order_no", columnList = "order_no")
})
public class OrderHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 낙관적 락 — 동시 폴링 두 스레드가 같은 주문 상태를 중복 갱신하는 것을 방지한다. */
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderSide side;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "order_no", length = 50)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    /** 누적 체결 수량. FillPoller가 이전값과 비교해 신규 체결분만 Position에 반영한다. */
    @Column(name = "filled_quantity", nullable = false)
    private int filledQuantity = 0;

    /** 평균 체결 단가 */
    @Column(name = "filled_price")
    private Double filledPrice;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "filled_at")
    private LocalDateTime filledAt;

    /** 취소 API를 KIS에 전송한 시각 — CANCEL_FAILED 타임아웃 판단 기준 */
    @Column(name = "cancel_requested_at")
    private LocalDateTime cancelRequestedAt;

    /** 지갑 칸 이름표 — null(칸 도입 이전 행·매도 주문)은 VB로 간주 (StrategyBucket.orDefault) */
    @Enumerated(EnumType.STRING)
    @Column(name = "bucket", length = 10)
    private StrategyBucket bucket;

    protected OrderHistory() {}

    public static OrderHistory accepted(String stockCode, OrderSide side, int quantity, String orderNo) {
        return accepted(stockCode, side, quantity, orderNo, null);
    }

    public static OrderHistory accepted(String stockCode, OrderSide side, int quantity,
                                        String orderNo, StrategyBucket bucket) {
        OrderHistory h = new OrderHistory();
        h.stockCode   = stockCode;
        h.side        = side;
        h.quantity    = quantity;
        h.orderNo     = orderNo;
        h.status      = OrderStatus.ACCEPTED;
        h.requestedAt = LocalDateTime.now();
        h.bucket      = bucket;
        return h;
    }

    /**
     * 접수되지 못한 주문 시도 기록 (터미널). 예전에는 실패 시 아무것도 남기지 않아
     * 시도 자체가 장부에서 사라졌고, 그래서 같은 매수를 몇 분 간격으로 반복했다
     * (2026-08-04: 034020 11분간 7회). 감사·재시도 억제의 근거로 남긴다.
     */
    public static OrderHistory failed(String stockCode, OrderSide side, int quantity,
                                      StrategyBucket bucket) {
        OrderHistory h = new OrderHistory();
        h.stockCode   = stockCode;
        h.side        = side;
        h.quantity    = quantity;
        h.status      = OrderStatus.FAILED;
        h.requestedAt = LocalDateTime.now();
        h.bucket      = bucket;
        return h;
    }

    // ── 상태 전이 ─────────────────────────────────────────────────────────────

    /** 일부 체결. totalFilledQty = KIS 응답의 tot_ccld_qty (누적값). */
    public void markPartialFilled(int totalFilledQty, double avgPrice) {
        requireTransitionFrom(OrderStatus.ACCEPTED, OrderStatus.PARTIAL_FILLED);
        requireQuantityNotDecreased(totalFilledQty);
        this.filledQuantity = totalFilledQty;
        this.filledPrice    = avgPrice;
        this.status         = OrderStatus.PARTIAL_FILLED;
    }

    /**
     * 전량 체결. totalFilledQty = KIS 응답의 tot_ccld_qty (누적값).
     * CANCEL_REQUESTED도 허용 — 취소 창 동안 전량 체결되면
     * finalizeAfterCancel()이 CANCEL_REQUESTED → FILLED로 전환한다.
     */
    public void markFilled(int totalFilledQty, double avgPrice) {
        requireTransitionFrom(OrderStatus.ACCEPTED, OrderStatus.PARTIAL_FILLED,
                              OrderStatus.CANCEL_REQUESTED);
        requireQuantityNotDecreased(totalFilledQty);
        this.filledQuantity = totalFilledQty;
        this.filledPrice    = avgPrice;
        this.status         = OrderStatus.FILLED;
        this.filledAt       = LocalDateTime.now();
    }

    /** KIS 취소 API가 rt_cd=="0" 반환 후 호출 — 다음 폴에서 최종 확인을 기다린다. */
    public void markCancelRequested() {
        requireTransitionFrom(OrderStatus.ACCEPTED, OrderStatus.PARTIAL_FILLED);
        this.status             = OrderStatus.CANCEL_REQUESTED;
        this.cancelRequestedAt  = LocalDateTime.now();
    }

    /** CANCEL_REQUESTED 24시간 초과 시 수동 확인 필요 상태로 전환 */
    public void markCancelFailed() {
        requireTransitionFrom(OrderStatus.CANCEL_REQUESTED);
        this.status = OrderStatus.CANCEL_FAILED;
    }

    /**
     * CANCEL_REQUESTED 상태에서 발생한 체결 반영.
     * 상태는 CANCEL_REQUESTED 유지 — finalizeAfterCancel()이 FILLED/CANCELLED로 전환한다.
     */
    public void recordFillDuringCancel(int totalFilledQty, double avgPrice) {
        requireTransitionFrom(OrderStatus.CANCEL_REQUESTED);
        requireQuantityNotDecreased(totalFilledQty);
        this.filledQuantity = totalFilledQty;
        this.filledPrice    = avgPrice;
    }

    public void markCancelled() {
        requireTransitionFrom(OrderStatus.ACCEPTED, OrderStatus.PARTIAL_FILLED,
                              OrderStatus.CANCEL_REQUESTED);
        this.status = OrderStatus.CANCELLED;
    }

    public void markTimeout() {
        requireTransitionFrom(OrderStatus.ACCEPTED, OrderStatus.PARTIAL_FILLED);
        this.status = OrderStatus.TIMEOUT;
    }

    private void requireQuantityNotDecreased(int incomingQty) {
        if (incomingQty < this.filledQuantity) {
            throw new IllegalStateException(String.format(
                    "체결 수량 감소 불가: 이전=%d → 신규=%d (ordNo=%s)",
                    this.filledQuantity, incomingQty, this.orderNo));
        }
    }

    /**
     * 허용된 현재 상태에서만 전이를 통과시킨다.
     * 터미널 상태(FILLED 등)에서 호출하면 IllegalStateException.
     */
    private void requireTransitionFrom(OrderStatus... allowed) {
        for (OrderStatus s : allowed) {
            if (this.status == s) return;
        }
        throw new IllegalStateException(String.format(
                "OrderHistory 상태 전이 불가: 현재=%s, 허용 시작상태=%s (ordNo=%s)",
                this.status, Arrays.toString(allowed), this.orderNo));
    }

    // ── getters ──────────────────────────────────────────────────────────────

    public Long getId()               { return id; }
    public Long getVersion()          { return version; }
    public String getStockCode()      { return stockCode; }
    public OrderSide getSide()        { return side; }
    public int getQuantity()          { return quantity; }
    public String getOrderNo()        { return orderNo; }
    public OrderStatus getStatus()    { return status; }
    public int getFilledQuantity()    { return filledQuantity; }
    public Double getFilledPrice()    { return filledPrice; }
    public LocalDateTime getRequestedAt()       { return requestedAt; }
    public LocalDateTime getFilledAt()          { return filledAt; }
    public LocalDateTime getCancelRequestedAt() { return cancelRequestedAt; }
    public StrategyBucket getBucket()           { return bucket; }
}
