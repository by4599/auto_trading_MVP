package com.trading.backtest;

import com.trading.market.CandleHistoryRepository;
import com.trading.risk.RiskLimitsProperties;

import static org.mockito.Mockito.mock;

/** 테스트 픽스처용 — 꺼진(기본값) 하락장 상대강도 스위치 */
final class BearRsGates {

    private BearRsGates() {
    }

    static BearRsGate off() {
        return new BearRsGate(mock(CandleHistoryRepository.class), new BacktestDataProperties(),
                new RiskLimitsProperties(), new ScaleInRunStats());
    }
}
