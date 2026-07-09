package com.trading.order;

public interface KisOrderClient {
    void buy(String stockCode);
    void sell(String stockCode);

    /** 수량 지정 매수 — OrderEngine이 R 사이징(OrderSizingService) 결과로 사용 */
    void buy(String stockCode, int quantity);

    /** 수량 지정 매도 — 평시 전량 매도(OrderEngine)와 강제청산(LiquidationService)이 사용 */
    void sell(String stockCode, int quantity);
}
