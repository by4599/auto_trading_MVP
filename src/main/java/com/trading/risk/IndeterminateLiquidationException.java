package com.trading.risk;

// 잔고 조회가 연속 실패하여 체결 여부를 확인할 수 없는 상태.
// LiquidationService가 수동 개입을 요청해야 한다고 판단할 때 던진다.
public class IndeterminateLiquidationException extends RuntimeException {
    public IndeterminateLiquidationException(String message) {
        super(message);
    }
}
