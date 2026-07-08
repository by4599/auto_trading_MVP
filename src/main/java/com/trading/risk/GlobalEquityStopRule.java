package com.trading.risk;

import com.trading.position.Account;
import com.trading.position.ShadowPortfolio;
import com.trading.signal.Signal;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// ADR 2.2: 전고점(peakEquity) 대비 MDD > 10%. 리셋 없음 — 영구 추적.
// 이 룰은 진입 게이트로서 매수 "거부"만 담당한다.
// 강제청산 트리거는 신호와 무관하게 도는 RiskMonitor가 담당한다 (역할 분리).
@Component
@Profile("paper")
public class GlobalEquityStopRule implements RiskRule {

    private final ShadowPortfolio shadowPortfolio;

    public GlobalEquityStopRule(ShadowPortfolio shadowPortfolio) {
        this.shadowPortfolio = shadowPortfolio;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        double peak = shadowPortfolio.getPeakEquity();
        if (peak <= 0) return RiskResult.pass(); // 초기 상태: peakEquity 미확인

        double current = account.getTotalAssetValue();
        if (current <= 0) return RiskResult.pass(); // 잔고 폴백 등 비정상 스냅샷 — 오탐 방지

        double drawdown = (peak - current) / peak;
        if (drawdown > RiskLimits.MDD_LIMIT) {
            return RiskResult.reject(String.format(
                    "전고점 대비 MDD %.2f%% 초과 — 신규 매수 금지", drawdown * 100));
        }
        return RiskResult.pass();
    }
}
