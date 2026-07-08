package com.trading.order;

public interface KisOrderClient {
    void buy(String stockCode);
    void sell(String stockCode);

    /** 수량 지정 매도 — 강제청산(LiquidationService 경유)이 보유 전량을 지정할 때 사용 */
    void sell(String stockCode, int quantity);
}
