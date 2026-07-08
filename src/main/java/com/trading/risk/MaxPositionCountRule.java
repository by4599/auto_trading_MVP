package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

// CLAUDE.md: 최대 보유 종목 5개.
@Component
public class MaxPositionCountRule implements RiskRule {

    private static final int MAX_POSITIONS = 5;

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();

        int count = account.getPositionCount();
        if (count >= MAX_POSITIONS) {
            return RiskResult.reject(String.format(
                    "최대 보유 종목 수 초과: 현재 %d개 (최대 %d개)", count, MAX_POSITIONS));
        }
        return RiskResult.pass();
    }
}
