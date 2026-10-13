package com.trading.position;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 백테스트용 무검증 구현체 — 전고점을 있는 그대로 둔다.
 *
 * 백테스트도 시뮬 날짜마다 daily_equity를 쌓기 때문에(BacktestPositionManager.computeDailyPnl)
 * 실측 검증을 그대로 켜면 하루 상승폭이 큰 구간에서 전고점 갱신이 막혀 MDD 매수 게이트·
 * 강제청산 모델의 판단이 달라질 수 있다. G0 회귀 앵커(BACKTEST-DESIGN §14.4)의 재현성이
 * 우선이므로 백테스트에서는 검증을 걸지 않는다 — 오염은 실계좌 잔고 API에서 오는 문제다.
 */
@Component
@Profile("backtest")
public class NoOpPeakEquityCalibrator implements PeakEquityCalibrator {

    @Override
    public double calibrate(double storedPeak) {
        return storedPeak;
    }

    @Override
    public boolean isImplausible(double equity) {
        return false;
    }
}
