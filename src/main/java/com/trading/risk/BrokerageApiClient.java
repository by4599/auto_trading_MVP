package com.trading.risk;

public interface BrokerageApiClient {
    void cancelAllPendingOrders();
    ActualAccountInfo getActualAccountAsset();
    void sendMarketOrder(String ticker, String side, int quantity);
    int getActualHoldingQuantity(String ticker);
}
