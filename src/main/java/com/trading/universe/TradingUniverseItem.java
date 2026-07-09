package com.trading.universe;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 매매 유니버스 — 자동 매매 대상 종목 (TradingScheduler가 순회).
 *
 * research.WatchlistItem(뉴스 수집용)과 의도적으로 분리된 별도 테이블이다.
 * 편입은 웹 UI에서 사람이 직접 한다 (RECOMMENDATION-TO-TRADE-DESIGN §3 수동 게이트 G1) —
 * 관심 종목·추천에 올랐다고 자동으로 매매 대상이 되는 경로는 존재하지 않는다.
 */
@Entity
@Table(name = "trading_universe", indexes = {
        @Index(name = "idx_universe_stock_code", columnList = "stock_code", unique = true)
})
public class TradingUniverseItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, unique = true, length = 20)
    private String stockCode;

    @Column(name = "stock_name", length = 100)
    private String stockName;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    protected TradingUniverseItem() {}

    public static TradingUniverseItem of(String stockCode, String stockName) {
        TradingUniverseItem item = new TradingUniverseItem();
        item.stockCode = stockCode;
        item.stockName = stockName != null ? stockName : stockCode;
        item.addedAt   = LocalDateTime.now();
        return item;
    }

    public Long getId()              { return id; }
    public String getStockCode()     { return stockCode; }
    public String getStockName()     { return stockName; }
    public LocalDateTime getAddedAt(){ return addedAt; }
}
