package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

// ADR 2.2: -3% 신규 매수 차단, -5% 강제청산.
// 이 룰은 진입 게이트로서 매수 "거부"만 담당한다.
// -5% 강제청산 트리거는 신호와 무관하게 도는 RiskMonitor가 담당한다 (역할 분리).
// 일일 리셋은 별도 크론이 아니라 daily_equity의 날짜 키(당일 시작 자산)가 담당한다.
@Component
public class DailyLossRule implements RiskRule {

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();

        double pnl = account.getDailyPnlPercent();

        if (pnl <= RiskLimits.DAILY_LOSS_LIQUIDATE) {
            return RiskResult.reject(String.format(
                    "일일 손실 한도 -5%% 도달 (현재 %.2f%%) — 신규 매수 금지", pnl * 100));
        }
        if (pnl <= RiskLimits.DAILY_LOSS_BLOCK) {
            return RiskResult.reject(String.format(
                    "일일 손실 한도 -3%% 도달 (현재 %.2f%%) — 당일 신규 매수 중지", pnl * 100));
        }
        return RiskResult.pass();
    }
}
