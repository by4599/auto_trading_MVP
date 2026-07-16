package com.trading.risk;

import com.trading.market.MarketCalendarService;
import com.trading.position.Account;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

// CLAUDE.md: 장 마감 10분 전 이후 신규 매수 금지 (평시 15:20 — 수능일 등 특례일은
// MarketCalendarService의 당일 마감 시각 기준으로 자동으로 밀린다, OPERATIONS §5.1).
// Clock 주입(F-8) — 서버 타임존 무관하게 KST 기준으로 판정한다.
@Component
public class MarketCloseRule implements RiskRule {

    private static final int CUTOFF_MINUTES_BEFORE_CLOSE = 10;

    private final Clock clock;
    private final MarketCalendarService marketCalendarService;

    public MarketCloseRule(Clock clock, MarketCalendarService marketCalendarService) {
        this.clock = clock;
        this.marketCalendarService = marketCalendarService;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();

        LocalDate today = LocalDate.now(clock);
        LocalTime cutoff = marketCalendarService.closeTime(today).minusMinutes(CUTOFF_MINUTES_BEFORE_CLOSE);
        if (LocalTime.now(clock).isAfter(cutoff)) {
            return RiskResult.reject(String.format(
                    "%s 이후 신규 매수 금지 (장 마감 임박)", cutoff));
        }
        return RiskResult.pass();
    }
}
