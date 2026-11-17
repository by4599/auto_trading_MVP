package com.trading.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("BacktestMetrics — PF/MDD/기대값 수치 검증")
class BacktestMetricsTest {

    private static final LocalDate D = LocalDate.of(2026, 1, 5);

    private static TradeRecorder.ClosedTrade trade(double cost, double proceeds) {
        return new TradeRecorder.ClosedTrade("005930", D, D, 1, cost, proceeds, "TimeCut-1515");
    }

    private static TradeRecorder.ClosedTrade trade(LocalDate entry, LocalDate exit,
                                                   double cost, double proceeds) {
        return new TradeRecorder.ClosedTrade("005930", entry, exit, 1, cost, proceeds, "TimeCut-1515");
    }

    @Test
    @DisplayName("PF = 총이익 / |총손실|")
    void profitFactor() {
        List<TradeRecorder.ClosedTrade> trades = List.of(
                trade(100_000, 103_000),  // +3000
                trade(100_000, 101_000),  // +1000
                trade(100_000, 98_000));  // -2000

        BacktestMetrics m = BacktestMetrics.of(trades, List.of(1_000_000.0), 1_000_000);

        assertThat(m.profitFactor()).isCloseTo(4000.0 / 2000.0, within(1e-9));
        assertThat(m.tradeCount()).isEqualTo(3);
        assertThat(m.winRate()).isCloseTo(2.0 / 3, within(1e-9));
    }

    @Test
    @DisplayName("손실 트레이드가 없으면 PF = ∞")
    void profitFactor_noLosses() {
        BacktestMetrics m = BacktestMetrics.of(
                List.of(trade(100_000, 101_000)), List.of(1_001_000.0), 1_000_000);
        assertThat(m.profitFactor()).isInfinite();
    }

    @Test
    @DisplayName("MDD는 자산 곡선의 전고점 대비 최대 낙폭이다")
    void maxDrawdown() {
        // 100 → 120(피크) → 90(-25%) → 110
        List<Double> equity = List.of(100.0, 120.0, 90.0, 110.0);
        assertThat(BacktestMetrics.maxDrawdown(equity, 100.0))
                .isCloseTo(0.25, within(1e-9));
    }

    @Test
    @DisplayName("기대값 = 트레이드 수익률 평균 (비용 차감 후)")
    void expectancy() {
        List<TradeRecorder.ClosedTrade> trades = List.of(
                trade(100_000, 102_000),   // +2%
                trade(100_000, 99_000));   // -1%
        BacktestMetrics m = BacktestMetrics.of(trades, List.of(1_000_000.0), 1_000_000);
        assertThat(m.expectancyPct()).isCloseTo(0.005, within(1e-9));
    }

    @Test
    @DisplayName("트레이드 0건이면 모든 지표가 0으로 안전하게 떨어진다")
    void emptyTrades() {
        BacktestMetrics m = BacktestMetrics.of(List.of(), List.of(), 1_000_000);
        assertThat(m.tradeCount()).isZero();
        assertThat(m.winRate()).isZero();
        assertThat(m.profitFactor()).isZero();
        assertThat(m.maxDrawdown()).isZero();
        assertThat(m.payoffRatio()).isZero();
        assertThat(m.avgHoldDays()).isZero();
    }

    @Test
    @DisplayName("손익비 = 평균이익 ÷ 평균손실 (§14 손익비 재설계 판독 지표)")
    void payoffRatio() {
        // 이익 2건 평균 +2000, 손실 1건 -1000 → 손익비 2.0
        List<TradeRecorder.ClosedTrade> trades = List.of(
                trade(100_000, 103_000),  // +3000
                trade(100_000, 101_000),  // +1000  (평균이익 2000)
                trade(100_000, 99_000));  // -1000  (평균손실 1000)
        BacktestMetrics m = BacktestMetrics.of(trades, List.of(1_000_000.0), 1_000_000);
        assertThat(m.payoffRatio()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    @DisplayName("손실이 없으면 손익비 = ∞, 이익도 없으면 0")
    void payoffRatio_edges() {
        assertThat(BacktestMetrics.of(List.of(trade(100_000, 101_000)),
                List.of(1_001_000.0), 1_000_000).payoffRatio()).isInfinite();
        assertThat(BacktestMetrics.of(List.of(trade(100_000, 100_000)),
                List.of(1_000_000.0), 1_000_000).payoffRatio()).isZero(); // 본전만
    }

    @Test
    @DisplayName("평균 보유일수 = entryDate~exitDate 달력일 평균")
    void avgHoldDays() {
        List<TradeRecorder.ClosedTrade> trades = List.of(
                trade(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5), 100_000, 101_000),  // 0일
                trade(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 11), 100_000, 99_000)); // 6일
        BacktestMetrics m = BacktestMetrics.of(trades, List.of(1_000_000.0), 1_000_000);
        assertThat(m.avgHoldDays()).isCloseTo(3.0, within(1e-9));
    }
}
