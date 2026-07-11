package com.trading.research;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 이벤트 유형 레지스트리 (설계 S3) — 유형별 과거 반응 통계와 승격 상태.
 *
 * 상태 전이: RECORDED(기본, 기록만) → CANDIDATE(통계 조건 충족) → PROMOTED(사람 승인).
 * PROMOTED 유형만 향후 EventDrivenStrategy(Phase 3d)가 신호로 쓴다.
 * **승격은 웹 UI에서 사람이 직접 — 자동 승격 경로는 만들지 않는다 (게이트 G2).**
 * 통계 갱신(EventStatsBacktester)은 status를 건드리지 않는다.
 */
@Entity
@Table(name = "event_type_registry")
public class EventTypeStat {

    public static final String STATUS_RECORDED  = "RECORDED";
    public static final String STATUS_CANDIDATE = "CANDIDATE";
    public static final String STATUS_PROMOTED  = "PROMOTED";

    @Id
    @Column(name = "event_type", length = 30)
    private String eventType;

    @Column(nullable = false, length = 12)
    private String status = STATUS_RECORDED;

    @Column(nullable = false)
    private int samples;

    /** D+5 종가 기준 승률 */
    @Column(name = "win_rate_d5", nullable = false)
    private double winRateD5;

    @Column(name = "median_d1",  nullable = false) private double medianD1;
    @Column(name = "median_d5",  nullable = false) private double medianD5;
    @Column(name = "median_d10", nullable = false) private double medianD10;
    @Column(name = "median_d20", nullable = false) private double medianD20;

    /** 하위 25% — 기대수익의 보수 추정치 (백테스트 §3.2: 평균은 소수 대박이 왜곡) */
    @Column(name = "p25_d5",  nullable = false) private double p25D5;
    @Column(name = "p25_d20", nullable = false) private double p25D20;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected EventTypeStat() {}

    public static EventTypeStat recorded(String eventType) {
        EventTypeStat s = new EventTypeStat();
        s.eventType = eventType;
        s.updatedAt = LocalDateTime.now();
        return s;
    }

    /** 통계 갱신 — status는 보존한다 (승격/강등은 별도 경로) */
    public void updateStats(int samples, double winRateD5,
                            double medianD1, double medianD5, double medianD10, double medianD20,
                            double p25D5, double p25D20) {
        this.samples   = samples;
        this.winRateD5 = winRateD5;
        this.medianD1  = medianD1;
        this.medianD5  = medianD5;
        this.medianD10 = medianD10;
        this.medianD20 = medianD20;
        this.p25D5     = p25D5;
        this.p25D20    = p25D20;
        this.updatedAt = LocalDateTime.now();
    }

    public void markCandidate() {
        if (STATUS_RECORDED.equals(status)) this.status = STATUS_CANDIDATE;
    }

    public String getEventType()  { return eventType; }
    public String getStatus()     { return status; }
    public int getSamples()       { return samples; }
    public double getWinRateD5()  { return winRateD5; }
    public double getMedianD1()   { return medianD1; }
    public double getMedianD5()   { return medianD5; }
    public double getMedianD10()  { return medianD10; }
    public double getMedianD20()  { return medianD20; }
    public double getP25D5()      { return p25D5; }
    public double getP25D20()     { return p25D20; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
