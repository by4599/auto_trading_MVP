package com.trading.market;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 과거 캔들 적재 테이블 (B-1) — 백테스트(B-2/B-3)와 이벤트 통계(B-4)의 데이터 자산.
 *
 * 일봉은 candle_time = 00:00 고정(유니크 인덱스가 NULL을 중복 허용하는 H2 특성 회피),
 * 분봉은 체결 시각을 기록한다. 지수(KOSPI)는 stock_code에 지수 코드를 그대로 쓴다.
 */
@Entity
@Table(name = "candle_history", indexes = {
        @Index(name = "idx_candle_lookup",
               columnList = "stock_code, timeframe, candle_date, candle_time", unique = true),
        @Index(name = "idx_candle_date", columnList = "candle_date")
})
public class CandleHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "timeframe", nullable = false, length = 10)
    private Timeframe timeframe;

    @Column(name = "candle_date", nullable = false)
    private LocalDate candleDate;

    /** 일봉은 00:00 고정, 분봉은 봉 시각 */
    @Column(name = "candle_time", nullable = false)
    private LocalTime candleTime;

    @Column(name = "open_price", nullable = false)
    private double open;

    @Column(name = "high_price", nullable = false)
    private double high;

    @Column(name = "low_price", nullable = false)
    private double low;

    @Column(name = "close_price", nullable = false)
    private double close;

    @Column(name = "volume", nullable = false)
    private long volume;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    protected CandleHistory() {}

    public static CandleHistory ofDaily(String stockCode, Candle candle) {
        return of(stockCode, Timeframe.DAILY, candle.date(), LocalTime.MIDNIGHT,
                candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(),
                candle.volume());
    }

    public static CandleHistory ofMinute(String stockCode, MinuteCandle candle) {
        return of(stockCode, Timeframe.MINUTE, candle.date(), candle.time(),
                candle.open(), candle.high(), candle.low(), candle.close(), candle.volume());
    }

    private static CandleHistory of(String stockCode, Timeframe timeframe,
                                    LocalDate date, LocalTime time,
                                    double open, double high, double low, double close,
                                    long volume) {
        CandleHistory c = new CandleHistory();
        c.stockCode  = stockCode;
        c.timeframe  = timeframe;
        c.candleDate = date;
        c.candleTime = time;
        c.open       = open;
        c.high       = high;
        c.low        = low;
        c.close      = close;
        c.volume     = volume;
        c.fetchedAt  = LocalDateTime.now();
        return c;
    }

    /** 백테스트 재생용 도메인 변환 */
    public Candle toCandle() {
        return new Candle(candleDate, open, high, low, close, volume);
    }

    public Long getId()              { return id; }
    public String getStockCode()     { return stockCode; }
    public Timeframe getTimeframe()  { return timeframe; }
    public LocalDate getCandleDate() { return candleDate; }
    public LocalTime getCandleTime() { return candleTime; }
    public double getOpen()          { return open; }
    public double getHigh()          { return high; }
    public double getLow()           { return low; }
    public double getClose()         { return close; }
    public long getVolume()          { return volume; }
    public LocalDateTime getFetchedAt() { return fetchedAt; }
}
