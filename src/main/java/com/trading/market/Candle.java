package com.trading.market;

import java.time.LocalDate;

public record Candle(
        LocalDate date,
        double open,
        double high,
        double low,
        double close,
        long volume
) {
    public double getOpen()  { return open; }
    public double getHigh()  { return high; }
    public double getLow()   { return low; }
    public double getClose() { return close; }
}
