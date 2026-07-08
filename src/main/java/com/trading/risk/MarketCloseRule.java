package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

// CLAUDE.md: 15:20 이후 신규 매수 금지.
@Component
public class MarketCloseRule implements RiskRule {

    private static final LocalTime CUTOFF = LocalTime.of(15, 20);

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();

        if (LocalTime.now().isAfter(CUTOFF)) {
            return RiskResult.reject("15:20 이후 신규 매수 금지 (장 마감 임박)");
        }
        return RiskResult.pass();
    }
}
