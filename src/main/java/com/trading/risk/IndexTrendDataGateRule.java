package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import com.trading.strategy.FilterProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 지수 추세 판정 불가 시 신규 매수 보류 (fail-closed, 2026-10-01) — 라이브 전용 관문.
 *
 * <p>{@link IndexTrendRule}은 데이터원이 판정 불가(empty)를 주면 <b>통과</b>시킨다. 백테스트에서는
 * 이 동작이 MA 워밍업 구간(표본 부족)에서만 나오고, 회귀 앵커가 그 결과를 기억하고 있어서
 * 계약을 바꾸지 않았다. 그러나 라이브에서 그 "조용한 통과"는 곧 무발동 사고다 — paper 데이터원이
 * 항상 empty였던 동안 필터를 켜도 아무것도 막지 못했다(B-3 갭다운 필터 무발동과 같은 모양).
 *
 * <p>그래서 이 룰이 <b>필터가 켜져 있는데 판정이 없으면</b> 매수를 막는다. 판정이 있으면 통과시키고
 * 막을지 말지는 {@link IndexTrendRule}이 정한다(두 룰은 겹치지 않는다). 매도는 절대 막지 않는다.
 * 다일 보유 전략은 거래가 드물어 하루 쉬어도 손해가 작지만, 검증된 필터 없이 사는 것은 검증
 * 범위 밖의 매매다. RiskEngine은 수정하지 않는다 — {@code @Component}만으로 자동 주입된다.
 */
@Component
@Profile("!backtest")
public class IndexTrendDataGateRule implements RiskRule {

    private final FilterProperties filters;
    private final IndexRegimeSource regimeSource;

    public IndexTrendDataGateRule(FilterProperties filters, IndexRegimeSource regimeSource) {
        this.filters = filters;
        this.regimeSource = regimeSource;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();
        FilterProperties.IndexTrend config = filters.getIndexTrend();
        if (!config.isEnabled()) return RiskResult.pass();
        if (regimeSource.isBelowTrend(config.getMaPeriod()).isPresent()) return RiskResult.pass();

        return RiskResult.reject(String.format(
                "지수 추세 판정 불가 — KOSPI MA%d 판정용 일봉을 아직 받지 못해 신규 매수 보류 (fail-closed)",
                config.getMaPeriod()));
    }
}
