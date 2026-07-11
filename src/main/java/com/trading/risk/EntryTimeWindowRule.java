package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import com.trading.strategy.FilterProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalTime;

/**
 * 진입 시간창 필터 (설계 문서 §3.3) — 장초반 휩소성 가짜 돌파 회피.
 * 기본 OFF. 일봉 백테스트로는 돌파 시각을 몰라 검증 불가 —
 * 분봉 축적(MinuteCandleCollector) 후 A/B를 거쳐야 켤 수 있다.
 */
@Component
public class EntryTimeWindowRule implements RiskRule {

    private final FilterProperties filters;
    private final Clock clock;

    public EntryTimeWindowRule(FilterProperties filters, Clock clock) {
        this.filters = filters;
        this.clock = clock;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();
        if (!filters.getEntryWindow().isEnabled()) return RiskResult.pass();

        LocalTime now = LocalTime.now(clock);
        if (now.isBefore(filters.getEntryWindow().getNotBefore())) {
            return RiskResult.reject(String.format(
                    "진입 시간창 필터 — %s 이전 신규 매수 금지 (현재 %s)",
                    filters.getEntryWindow().getNotBefore(), now));
        }
        return RiskResult.pass();
    }
}
