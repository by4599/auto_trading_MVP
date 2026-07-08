package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

// CLAUDE.md: 종목당 비중 최대 10%.
@Component
public class PositionLimitRule implements RiskRule {

    private static final double MAX_WEIGHT = 0.10;

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();

        double weight = account.getPositionWeight(signal.getStockCode());
        if (weight >= MAX_WEIGHT) {
            return RiskResult.reject(String.format(
                    "종목 비중 한도 초과: %s (현재 %.1f%% >= 최대 %.0f%%)",
                    signal.getStockCode(), weight * 100, MAX_WEIGHT * 100));
        }
        return RiskResult.pass();
    }
}
