package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

// ADR 2.2: -3% 신규 매수 차단, -5% 강제청산 (한도는 설정 UI로 조정 가능).
// 이 룰은 진입 게이트로서 매수 "거부"만 담당한다.
// 강제청산 트리거는 신호와 무관하게 도는 RiskMonitor가 담당한다 (역할 분리).
// 일일 리셋은 별도 크론이 아니라 daily_equity의 날짜 키(당일 시작 자산)가 담당한다.
@Component
public class DailyLossRule implements RiskRule {

    private final RiskLimitsProperties limits;

    public DailyLossRule(RiskLimitsProperties limits) {
        this.limits = limits;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();

        double pnl = account.getDailyPnlPercent();

        if (pnl <= limits.getDailyLossLiquidate()) {
            return RiskResult.reject(String.format(
                    "일일 손실 한도 %s%% 도달 (현재 %.2f%%) — 신규 매수 금지",
                    pct(limits.getDailyLossLiquidate()), pnl * 100));
        }
        if (pnl <= limits.getDailyLossBlock()) {
            return RiskResult.reject(String.format(
                    "일일 손실 한도 %s%% 도달 (현재 %.2f%%) — 당일 신규 매수 중지",
                    pct(limits.getDailyLossBlock()), pnl * 100));
        }
        return RiskResult.pass();
    }

    /** -0.03 → "-3", -0.035 → "-3.5" (소수점 불필요 시 생략) */
    private static String pct(double fraction) {
        double v = fraction * 100;
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
