package com.trading.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 리스크 룰이 신호를 막은 사건 한 건 (2026-09-22 신설).
 *
 * <p>왜 필요한가: 어떤 룰이 매수를 막았는지는 지금까지 {@code log.warn}에만 남아
 * <b>화면에서 "왜 안 샀나"를 볼 수 없었다.</b> 2026-09월 7거래일 매매 0건을 아무도
 * 못 본 이유 중 하나다.
 *
 * <p><b>읽기 전용 기록이다</b> — 어떤 판정에도 쓰이지 않는다.
 *
 * <p>{@code blockedCount}가 1이 아닐 수 있는 이유: 1초 루프에서 같은 종목·같은 룰이
 * 초 단위로 반복 거부되므로 {@link RiskBlockRecorder}가 창(기본 10분) 단위로 합친다.
 * 이 값은 <b>그 창 동안 실제로 막힌 횟수</b>다.
 */
@Entity
@Table(name = "risk_block_record", indexes = {
        @Index(name = "idx_risk_block_at",   columnList = "occurred_at"),
        @Index(name = "idx_risk_block_rule", columnList = "rule_name")
})
public class RiskBlockRecord {

    static final int REASON_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    /** 막은 룰 이름. 사유 문자열에서 되짚는다 — 모르면 UNKNOWN ({@link RiskRuleNameResolver}) */
    @Column(name = "rule_name", nullable = false, length = 60)
    private String ruleName;

    /** 룰이 준 사유 원문 — 룰 이름과 달리 <b>추정이 아니다</b> */
    @Column(name = "reason", length = REASON_MAX)
    private String reason;

    @Column(name = "strategy_name", length = 60)
    private String strategyName;

    /** 이 한 줄이 대표하는 실제 차단 횟수 (중복 억제된 것 포함, 최소 1) */
    @Column(name = "blocked_count", nullable = false)
    private int blockedCount;

    protected RiskBlockRecord() {}

    public static RiskBlockRecord of(LocalDateTime occurredAt, String stockCode, String ruleName,
                                     String reason, String strategyName, int blockedCount) {
        RiskBlockRecord r = new RiskBlockRecord();
        r.occurredAt    = occurredAt;
        r.stockCode     = stockCode;
        r.ruleName      = ruleName;
        r.reason        = clip(reason);
        r.strategyName  = strategyName;
        r.blockedCount  = Math.max(1, blockedCount);
        return r;
    }

    private static String clip(String value) {
        if (value == null) return null;
        return value.length() <= REASON_MAX ? value : value.substring(0, REASON_MAX);
    }

    public Long getId()                  { return id; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public String getStockCode()         { return stockCode; }
    public String getRuleName()          { return ruleName; }
    public String getReason()            { return reason; }
    public String getStrategyName()      { return strategyName; }
    public int getBlockedCount()         { return blockedCount; }
}
