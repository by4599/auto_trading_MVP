package com.trading.position;

import com.trading.bucket.StrategyBucket;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 매도 체결의 실현손익 기록 (실적 대시보드의 원천 데이터).
 *
 * 저장 지점은 FillStateUpdater(라이브 체결 경로)뿐이다 — TradeResultTracker에
 * 저장을 두면 BacktestOrderClient도 같은 메서드를 호출하므로 백테스트 실행이
 * 라이브 실적 테이블을 오염시킨다 (BACKTEST 격리).
 * BACKFILL은 과거 order_history 재생으로 생성된 소급 레코드다.
 */
@Entity
@Table(name = "trade_result", indexes = {
        @Index(name = "idx_trade_result_date", columnList = "trade_date")
})
public class TradeResult {

    public enum Source { LIVE, BACKFILL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "buy_avg_price", nullable = false)
    private double buyAvgPrice;

    @Column(name = "sell_price", nullable = false)
    private double sellPrice;

    @Column(name = "realized_pnl", nullable = false)
    private double realizedPnl;

    @Column(name = "realized_pnl_rate", nullable = false)
    private double realizedPnlRate;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "sold_at", nullable = false)
    private LocalDateTime soldAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private Source source;

    /** 지갑 칸 귀속 — null(칸 도입 이전 행)은 VB로 간주 (StrategyBucket.orDefault) */
    @Enumerated(EnumType.STRING)
    @Column(name = "bucket", length = 10)
    private StrategyBucket bucket;

    protected TradeResult() {}

    public static TradeResult live(String stockCode, int quantity,
                                   double buyAvgPrice, double sellPrice) {
        return live(stockCode, quantity, buyAvgPrice, sellPrice, null);
    }

    public static TradeResult live(String stockCode, int quantity,
                                   double buyAvgPrice, double sellPrice, StrategyBucket bucket) {
        TradeResult r = of(stockCode, quantity, buyAvgPrice, sellPrice, LocalDateTime.now(), Source.LIVE);
        r.bucket = bucket;
        return r;
    }

    public static TradeResult backfill(String stockCode, int quantity,
                                       double buyAvgPrice, double sellPrice,
                                       LocalDateTime soldAt) {
        return of(stockCode, quantity, buyAvgPrice, sellPrice, soldAt, Source.BACKFILL);
    }

    private static TradeResult of(String stockCode, int quantity,
                                  double buyAvgPrice, double sellPrice,
                                  LocalDateTime soldAt, Source source) {
        TradeResult r = new TradeResult();
        r.stockCode       = stockCode;
        r.quantity        = quantity;
        r.buyAvgPrice     = buyAvgPrice;
        r.sellPrice       = sellPrice;
        r.realizedPnl     = (sellPrice - buyAvgPrice) * quantity;
        r.realizedPnlRate = buyAvgPrice > 0 ? (sellPrice / buyAvgPrice - 1.0) : 0.0;
        r.soldAt          = soldAt;
        r.tradeDate       = soldAt.toLocalDate();
        r.source          = source;
        return r;
    }

    public Long getId()               { return id; }
    public String getStockCode()      { return stockCode; }
    public int getQuantity()          { return quantity; }
    public double getBuyAvgPrice()    { return buyAvgPrice; }
    public double getSellPrice()      { return sellPrice; }
    public double getRealizedPnl()    { return realizedPnl; }
    public double getRealizedPnlRate(){ return realizedPnlRate; }
    public LocalDate getTradeDate()   { return tradeDate; }
    public LocalDateTime getSoldAt()  { return soldAt; }
    public Source getSource()         { return source; }
    public StrategyBucket getBucket() { return bucket; }
}
