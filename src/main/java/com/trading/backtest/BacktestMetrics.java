package com.trading.backtest;

import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 백테스트 성과 지표 산출 (설계 문서 §4 합격 기준의 분자들).
 * 모든 손익은 비용 차감 후 기준 — TradeRecorder가 이미 비용 반영 현금으로 기록한다.
 *
 * payoffRatio·avgHoldDays는 Exit Lab(§14, 손익비 재설계 실험)의 판독 지표다:
 *   payoffRatio = 평균이익 ÷ 평균손실 — "손익비가 뒤집혔는지"를 직접 보여준다(≥2가 목표).
 *   avgHoldDays = 평균 보유 달력일수 — 다일 보유 프로필이 실제로 오래 쥐는지 확인.
 */
public record BacktestMetrics(
        int tradeCount,
        double winRate,
        double profitFactor,
        double expectancyPct,   // 트레이드당 평균 수익률 (비용 차감 후)
        double maxDrawdown,     // 일말 자산 곡선 기준 MDD
        double totalReturnPct,
        double finalEquity,
        double payoffRatio,     // 평균이익 ÷ 평균손실 (손익비)
        double avgHoldDays      // 평균 보유 달력일수 (entryDate~exitDate)
) {

    public static BacktestMetrics of(List<TradeRecorder.ClosedTrade> trades,
                                     List<Double> dailyEquity,
                                     double initialEquity) {
        int count = trades.size();
        long wins = trades.stream().filter(t -> t.pnl() > 0).count();

        double grossProfit = trades.stream().mapToDouble(TradeRecorder.ClosedTrade::pnl)
                .filter(p -> p > 0).sum();
        double grossLoss = -trades.stream().mapToDouble(TradeRecorder.ClosedTrade::pnl)
                .filter(p -> p < 0).sum();
        double pf = grossLoss > 0 ? grossProfit / grossLoss
                : (grossProfit > 0 ? Double.POSITIVE_INFINITY : 0.0);

        double expectancy = trades.stream()
                .mapToDouble(TradeRecorder.ClosedTrade::returnPct).average().orElse(0.0);

        double finalEquity = dailyEquity.isEmpty() ? initialEquity
                : dailyEquity.get(dailyEquity.size() - 1);
        double totalReturn = initialEquity > 0 ? (finalEquity - initialEquity) / initialEquity : 0.0;

        long lossCount = trades.stream().filter(t -> t.pnl() < 0).count();
        double avgWin  = wins > 0 ? grossProfit / wins : 0.0;
        double avgLoss = lossCount > 0 ? grossLoss / lossCount : 0.0;
        double payoff = avgLoss > 0 ? avgWin / avgLoss
                : (avgWin > 0 ? Double.POSITIVE_INFINITY : 0.0);

        double avgHold = trades.stream()
                .mapToLong(t -> ChronoUnit.DAYS.between(t.entryDate(), t.exitDate()))
                .average().orElse(0.0);

        return new BacktestMetrics(count,
                count > 0 ? (double) wins / count : 0.0,
                pf, expectancy, maxDrawdown(dailyEquity, initialEquity),
                totalReturn, finalEquity, payoff, avgHold);
    }

    static double maxDrawdown(List<Double> dailyEquity, double initialEquity) {
        double peak = initialEquity;
        double mdd = 0.0;
        for (double equity : dailyEquity) {
            if (equity > peak) peak = equity;
            else if (peak > 0) mdd = Math.max(mdd, (peak - equity) / peak);
        }
        return mdd;
    }

    public String summaryLine() {
        return String.format(
                "trades=%d win=%.1f%% PF=%s payoff=%s expectancy=%.3f%% MDD=%.2f%% hold=%.1fd return=%.2f%% final=%,.0f",
                tradeCount, winRate * 100,
                Double.isInfinite(profitFactor) ? "inf" : String.format("%.2f", profitFactor),
                Double.isInfinite(payoffRatio) ? "inf" : String.format("%.2f", payoffRatio),
                expectancyPct * 100, maxDrawdown * 100, avgHoldDays, totalReturnPct * 100, finalEquity);
    }
}
