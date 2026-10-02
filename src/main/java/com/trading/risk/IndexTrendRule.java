package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import com.trading.strategy.FilterProperties;
import org.springframework.stereotype.Component;

/**
 * 지수 장기 추세 필터 (BACKTEST-DESIGN §14.4) — 지수가 하락 추세일 때는 신규 매수를 막는다.
 *
 * 근거: §14.3 약세장 스트레스에서 2022 금리 쇼크 4분기 연속 손실(합성 -30%)이 후보를
 * 무너뜨렸고, 그 구간의 매매 건수가 오히려 늘었다(Q1 14 → Q2~Q4 34/59/48). 하락 추세
 * 도중의 반등을 "정배열 회복"으로 오인해 사고 다시 꺾여 잘리는 휩쏘다.
 *
 * <p>{@link IndexRegimeRule}(갭다운, 하루짜리 판정)과 <b>독립</b>이다 — 둘 다 켜도 서로
 * 간섭하지 않고 각자 매수를 막는다(RiskEngine이 룰을 순회하며 하나라도 거부하면 거부).
 * 기본 OFF이므로 켜지 않으면 paper/real·기존 백테스트 결과에 아무 영향이 없다.
 */
@Component
public class IndexTrendRule implements RiskRule {

    private final FilterProperties filters;
    private final IndexRegimeSource regimeSource;

    public IndexTrendRule(FilterProperties filters, IndexRegimeSource regimeSource) {
        this.filters = filters;
        this.regimeSource = regimeSource;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();
        FilterProperties.IndexTrend config = filters.getIndexTrend();
        if (!config.isEnabled()) return RiskResult.pass();

        return regimeSource.isBelowTrend(config.getMaPeriod())
                .filter(below -> below)
                .map(b -> RiskResult.reject(String.format(
                        "지수 추세 필터 — 지수가 MA%d 아래(하락 추세) 신규 매수 금지",
                        config.getMaPeriod())))
                .orElse(RiskResult.pass());
    }
}
