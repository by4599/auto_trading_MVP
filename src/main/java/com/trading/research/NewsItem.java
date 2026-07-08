package com.trading.research;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * RSS 수집된 뉴스 항목.
 * stock_code가 null이면 시장 전체에 관련된 뉴스.
 * NewsAggregatorService가 30일 초과 항목을 자동 삭제한다.
 */
@Entity
@Table(name = "news_item", indexes = {
        @Index(name = "idx_news_stock_code",    columnList = "stock_code"),
        @Index(name = "idx_news_published_at",  columnList = "published_at"),
        @Index(name = "idx_news_url",           columnList = "url", unique = true)
})
public class NewsItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** null = 특정 종목 미분류 (시장 전체 뉴스) */
    @Column(name = "stock_code", length = 20)
    private String stockCode;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(nullable = false, unique = true, length = 600)
    private String url;

    @Column(length = 100)
    private String source;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    /** NewsSentimentAnalyzer 결과: POSITIVE / NEGATIVE / NEUTRAL */
    @Column(length = 10)
    private String sentiment;

    protected NewsItem() {}

    public static NewsItem of(String stockCode, String title, String url,
                               String source, LocalDateTime publishedAt, String sentiment) {
        NewsItem item = new NewsItem();
        item.stockCode   = stockCode;
        item.title       = title;
        item.url         = url;
        item.source      = source;
        item.publishedAt = publishedAt;
        item.fetchedAt   = LocalDateTime.now();
        item.sentiment   = sentiment;
        return item;
    }

    public Long          getId()          { return id; }
    public String        getStockCode()   { return stockCode; }
    public String        getTitle()       { return title; }
    public String        getUrl()         { return url; }
    public String        getSource()      { return source; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public LocalDateTime getFetchedAt()   { return fetchedAt; }
    public String        getSentiment()   { return sentiment; }
}
