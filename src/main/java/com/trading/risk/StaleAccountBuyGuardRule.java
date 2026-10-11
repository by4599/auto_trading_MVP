package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 낡은 잔고로 신규 매수 금지 (2026-10-10, 28_audit M-3) — 라이브 전용 관문.
 *
 * <p>잔고 조회가 실패하면 {@code KisPositionManager}가 직전 캐시나 DB 폴백을 {@link Account#asStale()}로
 * 표시해 넘긴다. 청산·손절 감시는 이 표시를 보고 판정을 건너뛰지만, 매수 쪽 룰({@link DailyLossRule}·
 * {@link GlobalEquityStopRule})은 낡은 값을 그대로 읽는다 — 낡은 캐시는 보통 손실이 덜 반영된 값이라
 * 막아야 할 매수를 통과시킬 수 있다. 그래서 스냅샷이 낡았으면 매수를 보류한다.
 *
 * <p>매도는 절대 막지 않는다 — 손절·타임컷 같은 방어 경로를 늦추면 손실이 커진다.
 * RiskEngine은 수정하지 않는다 — {@code @Component}만으로 자동 주입된다.
 */
@Component
@Profile("!backtest")
public class StaleAccountBuyGuardRule implements RiskRule {

    static final String REASON = "잔고 정보가 낡음(조회 실패) — 신규 매수 보류";

    private final boolean enabled;

    public StaleAccountBuyGuardRule(@Value("${trading.risk.stale-account-buy-guard:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy() || !enabled) return RiskResult.pass();
        return account.isFresh() ? RiskResult.pass() : RiskResult.reject(REASON);
    }
}
