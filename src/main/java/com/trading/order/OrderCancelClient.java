package com.trading.order;

/**
 * 주문 취소 인터페이스.
 * FillProcessor(타임아웃 취소)와 KisBrokerageApiClient(강제청산 전 일괄 취소)가 공유한다.
 */
public interface OrderCancelClient {

    /**
     * 잔량 전부 취소를 요청하고 결과를 분류해 돌려준다.
     * 최종 취소 확정(SENT의 경우)은 FillPoller가 이어서 확인한다.
     */
    CancelOutcome cancelAll(String orderNo);

    /**
     * 취소 API 결과 분류.
     *
     * NO_OPEN_QTY 는 "정정/취소할 수량이 없습니다"(rt_cd=1) 응답 — 그 주문은 브로커에서
     * 이미 체결(종료)돼 취소할 잔량이 없다는 확정 신호다. 체결조회(VTTC8001R)가 이 체결을
     * 빈 응답으로 놓치는 경우가 있어, 호출 측은 이 신호를 실제 잔고 대사의 트리거로 쓴다.
     */
    enum CancelOutcome {
        SENT,         // rt_cd=0 — 취소 접수됨
        NO_OPEN_QTY,  // rt_cd=1 "취소할 수량 없음" — 이미 체결/종료
        FAILED        // 그 외 실패(네트워크·기타) — 다음 폴에서 재시도
    }
}
