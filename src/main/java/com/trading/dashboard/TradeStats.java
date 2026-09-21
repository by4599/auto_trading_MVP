package com.trading.dashboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 거래 묶음 하나의 성적 (승률·손익비 등). 전체·종목별·칸별이 같은 계산을 쓴다.
 *
 * <p>전부 <b>추정 매도가 기준</b>이며 수수료·세금은 빼지 않았다({@link EstimatedTrade} 주석).
 */
record TradeStats(int trades, int wins, int losses, int breakEven,
                  double totalPnl, double avgProfit, double avgLoss,
                  double avgReturnPercent, Double winRatePercent,
                  Double payoffRatio, Double profitFactor) {

    static TradeStats of(List<EstimatedTrade> trades) {
        if (trades.isEmpty()) {
            return new TradeStats(0, 0, 0, 0, 0, 0, 0, 0, null, null, null);
        }
        List<EstimatedTrade> wins   = trades.stream().filter(EstimatedTrade::isWin).toList();
        List<EstimatedTrade> losses = trades.stream().filter(EstimatedTrade::isLoss).toList();

        double profitSum = wins.stream().mapToDouble(EstimatedTrade::pnl).sum();
        double lossSum   = Math.abs(losses.stream().mapToDouble(EstimatedTrade::pnl).sum());
        double avgProfit = wins.isEmpty()   ? 0 : profitSum / wins.size();
        double avgLoss   = losses.isEmpty() ? 0 : lossSum   / losses.size();

        return new TradeStats(
                trades.size(), wins.size(), losses.size(),
                trades.size() - wins.size() - losses.size(),
                trades.stream().mapToDouble(EstimatedTrade::pnl).sum(),
                avgProfit, avgLoss,
                trades.stream().mapToDouble(EstimatedTrade::returnPercent).average().orElse(0),
                round(wins.size() * 100.0 / trades.size()),
                // 손익 어느 한쪽이 없으면 비율이 성립하지 않는다 — 0이나 무한대로 꾸미지 않고 null
                (avgLoss > 0 && avgProfit > 0) ? round(avgProfit / avgLoss) : null,
                (lossSum > 0 && profitSum > 0) ? round(profitSum / lossSum) : null);
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalTrades",      trades);
        m.put("wins",             wins);
        m.put("losses",           losses);
        m.put("breakEven",        breakEven);
        m.put("winRatePercent",   winRatePercent);
        m.put("totalPnl",         Math.round(totalPnl));
        m.put("avgProfit",        Math.round(avgProfit));
        m.put("avgLoss",          Math.round(avgLoss));
        m.put("payoffRatio",      payoffRatio);     // 평균이익 ÷ 평균손실 (손익비)
        m.put("profitFactor",     profitFactor);    // 총이익 ÷ 총손실
        m.put("avgReturnPercent", round(avgReturnPercent));
        return m;
    }

    /** 소수 둘째 자리 */
    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
