package com.trading.position;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 현재 보유 종목 상태.
 * 체결(FILLED)이 source of truth이고, Position은 그 결과로 파생된다.
 * FillPoller가 체결 확인 후 applyBuy / applySell을 호출한다.
 */
@Entity
@Table(name = "position", indexes = {
        @Index(name = "idx_position_stock_code", columnList = "stock_code", unique = true)
})
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 낙관적 락 — 동시 폴링이 같은 Position 행을 두 번 가산하는 것을 막는다. */
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "stock_code", nullable = false, unique = true, length = 20)
    private String stockCode;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "average_price", nullable = false)
    private double averagePrice;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Position() {}

    public static Position empty(String stockCode) {
        Position p = new Position();
        p.stockCode    = stockCode;
        p.quantity     = 0;
        p.averagePrice = 0.0;
        p.updatedAt    = LocalDateTime.now();
        return p;
    }

    /** 매수 체결 반영 — 수량 누적, 가중평균 단가 재계산 */
    public void applyBuy(int filledQty, double filledPrice) {
        double totalCost = (double) this.quantity * this.averagePrice
                + (double) filledQty * filledPrice;
        this.quantity     += filledQty;
        this.averagePrice  = totalCost / this.quantity;
        this.updatedAt     = LocalDateTime.now();
    }

    /** 매도 체결 반영 — 수량 감소, 평균단가 유지 */
    public void applySell(int filledQty) {
        if (filledQty > this.quantity) {
            throw new IllegalStateException(String.format(
                    "매도 수량(%d)이 보유 수량(%d)을 초과합니다: stockCode=%s",
                    filledQty, this.quantity, this.stockCode));
        }
        this.quantity -= filledQty;
        this.updatedAt = LocalDateTime.now();
    }

    // ── getters ──────────────────────────────────────────────────────────────

    public Long getId()              { return id; }
    public Long getVersion()         { return version; }
    public String getStockCode()     { return stockCode; }
    public int getQuantity()         { return quantity; }
    public double getAveragePrice()  { return averagePrice; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
