package com.trading.order;

/**
 * FillStateUpdater가 주문 전량 체결을 DB에 커밋할 때 발행하는 도메인 이벤트.
 *
 * @TransactionalEventListener(AFTER_COMMIT)으로 수신해 Telegram 알림을 보낸다.
 * 이벤트는 트랜잭션 내부에서 발행되고, 커밋 성공 후에만 리스너가 호출된다.
 */
public record OrderFilledEvent(
        OrderSide side,
        String stockCode,
        int filledQty,
        double avgPrice,
        boolean duringCancel   // true = 취소 창 중 전량 체결, false = 정상 체결
) {}
