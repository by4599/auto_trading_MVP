package com.trading.risk;

// 주문이 명시적으로 거부됐거나 미체결 잔여가 확인된 상태.
// executeWithRetry() 3회 후에도 remaining > 0이면 던진다.
public class ExplicitRejectLiquidationException extends RuntimeException {
    public ExplicitRejectLiquidationException(String message) {
        super(message);
    }
}
