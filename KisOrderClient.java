package com.trading.order;

public interface KisOrderClient {
    void buy(String stockCode);
    void sell(String stockCode);
}
