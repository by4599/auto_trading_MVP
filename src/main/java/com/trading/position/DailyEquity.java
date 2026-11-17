package com.trading.position;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 거래일 하루치 자산 원장. 날짜가 PK이므로 "매일 리셋"은 별도 크론 없이 날짜 키 교체로
 * 달성되고, 장중 재시작에도 당일 기준값이 보존된다.
 *
 * <p>두 가지 용도를 겸한다:
 * <ul>
 *   <li><b>당일 등락률 기준</b> — dailyPnlPercent = (현재 총자산 - startEquity) / startEquity
 *       (KisPositionManager가 그날 첫 잔고 스냅샷에서 기록)</li>
 *   <li><b>일별 순손익 원장</b> — 장 마감 후 총자산·예수금을 한 번 더 찍어
 *       그 차이를 그날 순손익으로 남긴다 (DailyPnlRecorder)</li>
 * </ul>
 *
 * <p>예수금(deposit)은 수수료·세금이 이미 빠진 현금이다. 모의 체결조회가 매도에 빈 응답을
 * 주는 결함(CLAUDE.md 결함 5) 때문에 거래 단위 실현손익은 막혀 있지만, 현금 증감은
 * 증권사가 이미 보내주므로 "하루 동안 실제로 얼마가 늘고 줄었나"는 여기서 알 수 있다.
 *
 * <p>마감 관련 컬럼은 전부 nullable이다 — 이 기능 이전 행과 백테스트 행은 비어 있다.
 */
@Entity
@Table(name = "daily_equity")
public class DailyEquity {

    @Id
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "start_equity", nullable = false)
    private double startEquity;

    /** 장 시작 예수금(현금). 기록이 없으면 null */
    @Column(name = "start_deposit")
    private Double startDeposit;

    /** 장 마감 후 총자산 — null이면 "그날 마감을 아직 못 찍었다"는 뜻 */
    @Column(name = "end_equity")
    private Double endEquity;

    /** 장 마감 후 예수금(현금) */
    @Column(name = "end_deposit")
    private Double endDeposit;

    /**
     * 마감 손익 알림을 <b>실제로 보낸</b> 시각. null이면 아직 못 보냈다는 뜻이고
     * 다음 거래일 아침에 이월 발송 대상이 된다 (DailyPnlRecorder).
     * 이 기능 이전 행과 백테스트 행은 비어 있다.
     */
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    protected DailyEquity() {}

    /** 예수금을 모르는 기록 (이 기능 이전 행·백테스트) — startDeposit은 null로 남는다 */
    public static DailyEquity of(LocalDate tradeDate, double startEquity) {
        DailyEquity e = new DailyEquity();
        e.tradeDate   = tradeDate;
        e.startEquity = startEquity;
        return e;
    }

    public static DailyEquity of(LocalDate tradeDate, double startEquity, double startDeposit) {
        DailyEquity e = of(tradeDate, startEquity);
        e.startDeposit = startDeposit;
        return e;
    }

    /**
     * 장 마감 후 1회 기록. 이미 찍힌 날은 덮어쓰지 않는다 — 원장은 먼저 찍힌 마감값이 정본이고,
     * 장외에 늦게 읽은 값으로 하루의 결론이 바뀌면 안 된다.
     */
    public void recordClose(double endEquity, double endDeposit) {
        if (this.endEquity != null) return;
        this.endEquity  = endEquity;
        this.endDeposit = endDeposit;
    }

    public boolean isClosed() { return endEquity != null; }

    /**
     * 알림을 실제로 보낸 뒤에만 찍는다 — 이 값이 이월 발송 여부를 가르는 유일한 근거다.
     * 이미 찍힌 날은 덮어쓰지 않는다 (먼저 보낸 시각이 정본).
     */
    public void markNotified(LocalDateTime at) {
        if (this.notifiedAt != null) return;
        this.notifiedAt = at;
    }

    public boolean isNotified() { return notifiedAt != null; }

    /** 그날 순손익(원) = 마감 총자산 - 시작 총자산. 마감 기록 전에는 null */
    public Double getNetPnl() {
        return endEquity == null ? null : endEquity - startEquity;
    }

    /** 그날 현금 증감(원) = 마감 예수금 - 시작 예수금. 둘 중 하나라도 없으면 null */
    public Double getCashDelta() {
        return (endDeposit == null || startDeposit == null) ? null : endDeposit - startDeposit;
    }

    public LocalDate getTradeDate()  { return tradeDate; }
    public double getStartEquity()   { return startEquity; }
    public Double getStartDeposit()  { return startDeposit; }
    public Double getEndEquity()     { return endEquity; }
    public Double getEndDeposit()    { return endDeposit; }
    public LocalDateTime getNotifiedAt() { return notifiedAt; }
}
