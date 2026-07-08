package com.trading.order;

import java.time.LocalDateTime;

/**
 * FillStateUpdater.loadSnapshot()이 반환하는 불변 DTO.
 *
 * Detached Entity 대신 record를 사용해 트랜잭션 외부에서의 우발적 상태 변경을 방지한다.
 *
 * version 필드:
 *   DB 저장에는 사용하지 않지만 로그 분석에서 동시성 추적에 유용하다.
 *   예) "orderId=123 version=17 에서 충돌" → 어느 시점의 snapshot인지 파악 가능.
 *
 * cancelRequestedAt:
 *   감사(audit) 목적으로 CANCELLED/FILLED 후에도 DB에 보존한다.
 *   null = 아직 취소 미요청.
 */
public record OrderSnapshot(
        Long id,
        String orderNo,
        OrderStatus status,
        OrderSide side,
        String stockCode,
        int quantity,
        int filledQuantity,
        LocalDateTime requestedAt,
        LocalDateTime cancelRequestedAt,
        Long version
) {}
