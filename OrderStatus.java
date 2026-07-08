package com.trading.order;

/**
 * 주문 상태 전이 (허용 경로만 OrderHistory.requireTransitionFrom()이 강제한다)
 *
 *   ACCEPTED ──┬─→ PARTIAL_FILLED ──┬─→ FILLED             (정상 완전 체결)
 *              │                    ├─→ CANCEL_REQUESTED ─→ FILLED    (취소 중 전량 체결)
 *              │                    └─→ CANCEL_REQUESTED ─→ CANCELLED (취소 확정)
 *              ├─→ FILLED                                              (단번에 전량 체결)
 *              ├─→ CANCEL_REQUESTED ─→ FILLED
 *              ├─→ CANCEL_REQUESTED ─→ CANCELLED
 *              └─→ FAILED                                              (API 오류)
 *
 * TIMEOUT 미사용: 타임아웃 도달 시 취소 API를 먼저 호출하고 CANCEL_REQUESTED로 전환한다.
 */
public enum OrderStatus {
    ACCEPTED,          // 접수됨, 체결 확인 전
    PARTIAL_FILLED,    // 일부 체결 — 나머지 미체결
    CANCEL_REQUESTED,  // KIS에 취소 접수 완료 — 다음 폴에서 최종 확인
    FILLED,            // 전량 체결 (터미널)
    CANCELLED,         // 취소 확정 (터미널)
    FAILED,            // 주문 API 자체 실패 (터미널)
    CANCEL_FAILED,     // 취소 접수 후 24h 초과 미확정 → 수동 확인 필요 (터미널)
    TIMEOUT            // 예비 — 현재 미사용
}
