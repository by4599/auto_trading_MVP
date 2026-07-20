package com.trading.order;

/**
 * FillStateUpdater가 주문 부분 체결을 DB에 커밋할 때 발행하는 도메인 이벤트.
 *
 * 전량 체결 전이라도 이미 체결된 수량은 포지션에 반영되어 있으므로,
 * StopLossArmer가 이 이벤트를 받아 손절선을 장착한다 (알려진 결함 #5 해소 —
 * 이전에는 전량 체결 이벤트에만 장착되어 부분 체결 보유분이 무방비였다).
 *
 * avgPrice는 KIS 응답의 누적 평균 체결가 — 이후 추가 체결 시 갱신된 평균으로
 * 다시 발행되어 손절선이 재계산된다 (체결가 기준 원칙, 방법론 §4.3).
 */
public record OrderPartialFilledEvent(
        OrderSide side,
        String stockCode,
        int totalFilledQty,
        double avgPrice
) {}
