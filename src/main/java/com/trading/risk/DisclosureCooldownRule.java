package com.trading.risk;

import com.trading.position.Account;
import com.trading.research.DisclosureItem;
import com.trading.research.DisclosureRepository;
import com.trading.signal.Signal;
import com.trading.strategy.FilterProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;

/**
 * 공시 쿨다운 필터 (설계 §3.3 확장) — 이벤트성 공시 후 N일간 해당 종목 신규 매수 금지.
 *
 * 근거: B-4 이벤트 통계에서 공시 직후 D+5 시장 대비 초과수익이 전 유형에서
 * 일관되게 음(-)으로 확인됐다 (BACKTEST-DESIGN §8 — "공시 확인 후 진입은 늦다").
 * 기본 OFF — B-3 A/B에서 순정 대비 검증 PF·MDD 동반 우위일 때만 ON (원칙 ②).
 *
 * 정례·행정성 공시는 판정에서 제외한다 — 대형주는 지분보고가 거의 매일 나와서
 * 포함하면 필터가 아니라 상시 매매 중단이 된다.
 */
@Component
public class DisclosureCooldownRule implements RiskRule {

    /** 판정에서 제외할 정례 공시 유형 (DisclosureEventClassifier의 노이즈 유형) */
    private static final Set<String> ROUTINE_TYPES = Set.of(
            "INSIDER_OWNERSHIP", "LARGE_HOLDING", "IR_EVENT", "RELATED_PARTY", "REGULAR_FILING");

    private final FilterProperties filters;
    private final DisclosureRepository disclosureRepository;
    private final Clock clock;

    public DisclosureCooldownRule(FilterProperties filters,
                                  DisclosureRepository disclosureRepository,
                                  Clock clock) {
        this.filters = filters;
        this.disclosureRepository = disclosureRepository;
        this.clock = clock;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();
        FilterProperties.DisclosureCooldown config = filters.getDisclosureCooldown();
        if (!config.isEnabled()) return RiskResult.pass();

        // 당일 공시는 제외 — 공시 시각 불명(장 마감 후 가능)이라 당일 차단에 쓰면
        // 미래 정보 사용(선견 편향)이 된다. 어제까지 공표된 공시만 판정 (B-4 진입 관례와 대칭)
        LocalDate today = LocalDate.now(clock);
        LocalDate to = today.minusDays(1);
        LocalDate from = to.minusDays(config.getCooldownDays() - 1);

        for (DisclosureItem d : disclosureRepository
                .findByStockCodeAndDisclosedAtBetween(signal.getStockCode(), from, to)) {
            if (d.getEventType() == null || ROUTINE_TYPES.contains(d.getEventType())) continue;
            return RiskResult.reject(String.format(
                    "공시 쿨다운 — %s 공시(%s) 후 %d일 내 신규 매수 금지",
                    d.getEventType(), d.getDisclosedAt(), config.getCooldownDays()));
        }
        return RiskResult.pass();
    }
}
