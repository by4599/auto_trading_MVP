package com.trading.position;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * 당일 시작 자산 기록. dailyPnlPercent = (현재 총자산 - startEquity) / startEquity.
 * 날짜가 PK이므로 "매일 리셋"은 별도 크론 없이 날짜 키 교체로 달성된다.
 * 장중 재시작에도 당일 기준값이 보존된다.
 */
@Entity
@Table(name = "daily_equity")
public class DailyEquity {

    @Id
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "start_equity", nullable = false)
    private double startEquity;

    protected DailyEquity() {}

    public static DailyEquity of(LocalDate tradeDate, double startEquity) {
        DailyEquity e = new DailyEquity();
        e.tradeDate   = tradeDate;
        e.startEquity = startEquity;
        return e;
    }

    public LocalDate getTradeDate()  { return tradeDate; }
    public double getStartEquity()   { return startEquity; }
}
