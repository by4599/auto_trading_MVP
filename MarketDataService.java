package com.trading.market;

import java.util.List;

public interface MarketDataService {
    List<Candle> getRecentCandles(String stockCode);
}
