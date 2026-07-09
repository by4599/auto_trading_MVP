package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalTime;

// CLAUDE.md: 15:20 이후 신규 매수 금지.
// Clock 주입(F-8) — 서버 타임존 무관하게 KST 기준으로 판정한다.
@Component
public class MarketCloseRule implements RiskRule {

    private static final LocalTime CUTOFF = LocalTime.of(15, 20);

    private final Clock clock;

    public MarketCloseRule(Clock clock) {
        this.clock = clock;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();

        if (LocalTime.now(clock).isAfter(CUTOFF)) {
            return RiskResult.reject("15:20 이후 신규 매수 금지 (장 마감 임박)");
        }
        return RiskResult.pass();
    }
}
