package com.trading.strategy;

import com.trading.market.Candle;
import com.trading.signal.Signal;

import java.util.List;

public interface Strategy {
    String getName();
    List<Signal> evaluate(String stockCode, List<Candle> candles);
}
