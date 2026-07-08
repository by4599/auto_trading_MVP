package com.trading.research;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 리서치용 관심 종목.
 * TradingScheduler.WATCH_LIST(자동 매매 대상)와 완전히 분리된 별도 엔티티.
 * 이 테이블에 종목을 추가해도 자동 매매에는 영향이 없다.
 */
@Entity
@Table(name = "watchlist_item", indexes = {
        @Index(name = "idx_watchlist_stock_code", columnList = "stock_code", unique = true)
})
public class WatchlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, unique = true, length = 20)
    private String stockCode;

    @Column(name = "stock_name", length = 100)
    private String stockName;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    protected WatchlistItem() {}

    public static WatchlistItem of(String stockCode, String stockName, String notes) {
        WatchlistItem item = new WatchlistItem();
        item.stockCode = stockCode;
        item.stockName = stockName != null ? stockName : stockCode;
        item.notes     = notes;
        item.addedAt   = LocalDateTime.now();
        return item;
    }

    public Long          getId()        { return id; }
    public String        getStockCode() { return stockCode; }
    public String        getStockName() { return stockName; }
    public String        getNotes()     { return notes; }
    public LocalDateTime getAddedAt()   { return addedAt; }

    public void updateStockName(String name) { this.stockName = name; }
}
