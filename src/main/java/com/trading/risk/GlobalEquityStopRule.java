package com.trading.risk;

import com.trading.position.Account;
import com.trading.position.ShadowPortfolio;
import com.trading.signal.Signal;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// ADR 2.2: 전고점(peakEquity) 대비 MDD > 10%. 리셋 없음 — 영구 추적.
// 이 룰은 진입 게이트로서 매수 "거부"만 담당한다.
// 강제청산 트리거는 신호와 무관하게 도는 RiskMonitor가 담당한다 (역할 분리).
//
// 전고점이 클램프 교정된 미검증 값이어도 이 매수 차단은 그대로 유지한다 —
// 보류되는 것은 RiskMonitor의 자동 강제청산뿐이고(과대 MDD로 인한 헛청산 방지),
// 신규 진입은 계속 막는 쪽이 보수적이다. 여기서는 사유 문구만 그 상태를 알린다.
@Component
@Profile({"paper", "backtest"})   // backtest 미포함 시 MDD 매수 게이트가 조용히 사라진다
public class GlobalEquityStopRule implements RiskRule {

    private final ShadowPortfolio shadowPortfolio;
    private final RiskLimitsProperties limits;

    public GlobalEquityStopRule(ShadowPortfolio shadowPortfolio, RiskLimitsProperties limits) {
        this.shadowPortfolio = shadowPortfolio;
        this.limits = limits;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        double peak = shadowPortfolio.getPeakEquity();
        if (peak <= 0) return RiskResult.pass(); // 초기 상태: peakEquity 미확인

        double current = account.getTotalAssetValue();
        if (current <= 0) return RiskResult.pass(); // 잔고 폴백 등 비정상 스냅샷 — 오탐 방지

        double drawdown = (peak - current) / peak;
        if (drawdown > limits.getMddLimit()) {
            return RiskResult.reject(String.format(
                    "전고점 대비 MDD %.2f%% 초과 — 신규 매수 금지%s", drawdown * 100,
                    shadowPortfolio.isPeakUnverified()
                            ? " (전고점이 교정된 미검증 값 — 자동 강제청산은 사람 확인까지 보류 중)" : ""));
        }
        return RiskResult.pass();
    }
}
