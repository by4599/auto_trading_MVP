package com.trading.order;

import java.time.LocalDateTime;

/**
 * CANCEL_REQUESTED 24시간 초과로 CANCEL_FAILED 전환 시 발행하는 도메인 이벤트.
 *
 * @TransactionalEventListener(AFTER_COMMIT)으로 수신해 운영자 알림을 보낸다.
 */
public record CancelFailedEvent(
        String stockCode,
        String orderNo,
        LocalDateTime cancelRequestedAt
) {}
