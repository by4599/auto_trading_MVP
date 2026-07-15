package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

// CLAUDE.md: 종목당 비중 최대 10% (한도는 설정 UI로 조정 가능).
@Component
public class PositionLimitRule implements RiskRule {

    private final RiskLimitsProperties limits;

    public PositionLimitRule(RiskLimitsProperties limits) {
        this.limits = limits;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();

        double maxWeight = limits.getMaxPositionWeight();
        double weight = account.getPositionWeight(signal.getStockCode());
        if (weight >= maxWeight) {
            return RiskResult.reject(String.format(
                    "종목 비중 한도 초과: %s (현재 %.1f%% >= 최대 %.0f%%)",
                    signal.getStockCode(), weight * 100, maxWeight * 100));
        }
        return RiskResult.pass();
    }
}
