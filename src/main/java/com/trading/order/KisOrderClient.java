package com.trading.order;

import com.trading.bucket.StrategyBucket;

public interface KisOrderClient {
    void buy(String stockCode);
    void sell(String stockCode);

    /** 수량 지정 매수 — OrderEngine이 R 사이징(OrderSizingService) 결과로 사용 */
    void buy(String stockCode, int quantity);

    /**
     * 지갑 칸 이름표를 단 매수 — 주문 기록(OrderHistory)에 칸을 남겨 체결 시
     * Position 귀속에 쓴다. 기본 구현은 칸을 무시한다 (백테스트 등 칸 미사용 경로 보존).
     */
    default void buy(String stockCode, int quantity, StrategyBucket bucket) {
        buy(stockCode, quantity);
    }

    /** 수량 지정 매도 — 평시 전량 매도(OrderEngine)와 강제청산(LiquidationService)이 사용 */
    void sell(String stockCode, int quantity);
}
