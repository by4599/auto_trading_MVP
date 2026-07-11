package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import com.trading.strategy.FilterProperties;
import org.springframework.stereotype.Component;

/**
 * 지수 레짐 필터 (설계 문서 §3.3) — 지수 갭다운일의 개별 돌파는 무너지기 쉽다.
 * 기본 OFF. B-3 A/B에서 순정 대비 검증 PF·MDD 동반 우위일 때만 ON.
 */
@Component
public class IndexRegimeRule implements RiskRule {

    private final FilterProperties filters;
    private final IndexRegimeSource regimeSource;

    public IndexRegimeRule(FilterProperties filters, IndexRegimeSource regimeSource) {
        this.filters = filters;
        this.regimeSource = regimeSource;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();
        if (!filters.getIndexRegime().isEnabled()) return RiskResult.pass();

        return regimeSource.isBearishRegime()
                .filter(bearish -> bearish)
                .map(b -> RiskResult.reject("지수 레짐 필터 — KOSPI 갭다운일 신규 매수 금지"))
                .orElse(RiskResult.pass());
    }
}
