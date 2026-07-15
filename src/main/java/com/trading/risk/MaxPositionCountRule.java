package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

// CLAUDE.md: 최대 보유 종목 5개 (한도는 설정 UI로 조정 가능).
@Component
public class MaxPositionCountRule implements RiskRule {

    private final RiskLimitsProperties limits;

    public MaxPositionCountRule(RiskLimitsProperties limits) {
        this.limits = limits;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();

        int maxPositions = limits.getMaxPositionCount();
        int count = account.getPositionCount();
        if (count >= maxPositions) {
            return RiskResult.reject(String.format(
                    "최대 보유 종목 수 초과: 현재 %d개 (최대 %d개)", count, maxPositions));
        }
        return RiskResult.pass();
    }
}
