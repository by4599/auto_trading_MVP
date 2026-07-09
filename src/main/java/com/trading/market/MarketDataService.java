package com.trading.market;

import java.util.List;

public interface MarketDataService {

    List<Candle> getRecentCandles(String stockCode);

    /**
     * 최근 완성 일봉 N개 (과거→최신 순). 당일 미완성 봉은 제외한다.
     * ATR 산출(AtrCalculator) 등 기간 지표용 — AtrCalculator.PERIOD+1개 이상 요청할 것.
     */
    List<Candle> getDailyCandles(String stockCode, int days);
}
