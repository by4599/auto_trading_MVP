package com.trading.research;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DART 공시 항목 — 뉴스(NewsItem)보다 상위 신뢰도의 1차 정보원 (방법론 §1).
 *
 * 이벤트 백테스트(B-4)의 표본이 되므로 뉴스와 달리 자동 삭제하지 않는다 —
 * 수집 시작일부터의 축적이 곧 통계 검증의 데이터 자산이다.
 */
@Entity
@Table(name = "disclosure_item", indexes = {
        @Index(name = "idx_disclosure_stock_code", columnList = "stock_code"),
        @Index(name = "idx_disclosure_date",       columnList = "disclosed_at"),
        @Index(name = "idx_disclosure_receipt",    columnList = "receipt_no", unique = true)
})
public class DisclosureItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "corp_name", length = 100)
    private String corpName;

    /** DART 접수번호 — 중복 수집 방지 키 */
    @Column(name = "receipt_no", nullable = false, unique = true, length = 20)
    private String receiptNo;

    @Column(name = "report_name", nullable = false, columnDefinition = "TEXT")
    private String reportName;

    @Column(name = "disclosed_at", nullable = false)
    private LocalDate disclosedAt;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    /** 보고서명 키워드 1차 분류: POSITIVE / NEGATIVE / NEUTRAL */
    @Column(length = 10)
    private String sentiment;

    protected DisclosureItem() {}

    public static DisclosureItem of(String stockCode, String corpName, String receiptNo,
                                    String reportName, LocalDate disclosedAt, String sentiment) {
        DisclosureItem item = new DisclosureItem();
        item.stockCode   = stockCode;
        item.corpName    = corpName;
        item.receiptNo   = receiptNo;
        item.reportName  = reportName;
        item.disclosedAt = disclosedAt;
        item.fetchedAt   = LocalDateTime.now();
        item.sentiment   = sentiment;
        return item;
    }

    /** DART 원문 열람 URL */
    public String dartUrl() {
        return "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receiptNo;
    }

    public Long getId()               { return id; }
    public String getStockCode()      { return stockCode; }
    public String getCorpName()       { return corpName; }
    public String getReceiptNo()      { return receiptNo; }
    public String getReportName()     { return reportName; }
    public LocalDate getDisclosedAt() { return disclosedAt; }
    public LocalDateTime getFetchedAt() { return fetchedAt; }
    public String getSentiment()      { return sentiment; }
}
