package com.trading.risk;

import com.trading.order.OrderFailureTracker;
import com.trading.position.Account;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

/**
 * 주문 접수에 실패한 종목의 신규 매수를 잠시 막는 RiskRule (2026-08-04).
 *
 * 실패한 주문은 ACCEPTED로 남지 않아 {@link PendingOrderRule}이 중복을 못 막는다.
 * 그 틈에 전략이 매 틱 같은 신호를 내면 브로커가 불안정한 동안 같은 매수를 반복하게 되고,
 * 응답만 유실된 주문이 하나라도 실제 체결됐다면 의도보다 많이 사게 된다.
 *
 * 매도는 막지 않는다 — 손절·타임컷 등 방어 경로를 지연시키면 손실이 커진다.
 * RiskEngine 수정 없이 @Component 자동 주입으로 합류한다.
 */
@Component
public class OrderFailureCooldownRule implements RiskRule {

    private final OrderFailureTracker failureTracker;

    public OrderFailureCooldownRule(OrderFailureTracker failureTracker) {
        this.failureTracker = failureTracker;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) {
            return RiskResult.pass();
        }
        if (failureTracker.isCoolingDown(signal.getStockCode())) {
            return RiskResult.reject("직전 주문 실패로 대기 중: " + signal.getStockCode());
        }
        return RiskResult.pass();
    }
}
