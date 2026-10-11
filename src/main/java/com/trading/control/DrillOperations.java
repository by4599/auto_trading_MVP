package com.trading.control;

/**
 * 청산 리허설 조작의 단일 관문.
 *
 * 웹 요청(TradingController)과 예약 실행(LiquidationDrillScheduler)이 같은 가드를 지나도록
 * 인터페이스 뒤에 숨긴다 — 구현체를 갈아끼우는 목적이 아니라, 두 진입점이 판정 로직을
 * 각자 복사해 갖는 것을 막기 위해서다.
 */
public interface DrillOperations {

    /** 조작 결과 — 성공 여부와 사람이 읽을 사유 */
    record Outcome(boolean success, String message) {}

    /** 리허설용 포지션 확보 (고정 종목·고정 수량 시장가 매수). 리스크 룰은 그대로 통과시킨다. */
    Outcome manualBuy();

    /** 강제청산 개시 — 미체결 취소 후 보유 전량 시장가 매도, 종료 후 EMERGENCY_STOPPED */
    Outcome liquidate();

    /** 청산할 보유분이 있는가 */
    boolean hasAnyPosition();
}
