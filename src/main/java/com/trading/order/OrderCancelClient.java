package com.trading.order;

/**
 * 주문 취소 인터페이스.
 * FillProcessor(타임아웃 취소)와 KisBrokerageApiClient(강제청산 전 일괄 취소)가 공유한다.
 */
public interface OrderCancelClient {

    /**
     * 잔량 전부 취소를 요청한다.
     * @return KIS가 취소 접수를 확인(rt_cd=0)하면 true. 최종 취소 확정은 FillPoller가 확인한다.
     */
    boolean cancelAll(String orderNo);
}
