package com.trading.backtest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 백테스트 트레이드 원장 (B-2).
 *
 * 매수 체결을 종목별 랏(누적 원가)으로 쌓고, 매도 체결 시 원가 안분으로
 * 라운드트립 손익을 확정한다. 모든 금액은 비용(슬리피지·수수료·제세) 반영 후 값.
 */
@Component
@Profile("backtest")
public class TradeRecorder {

    /** 확정 라운드트립. cost/proceeds는 비용 포함 현금 기준. */
    public record ClosedTrade(String stockCode, LocalDate entryDate, LocalDate exitDate,
                              int quantity, double cost, double proceeds, String exitReason) {
        public double pnl()       { return proceeds - cost; }
        public double returnPct() { return cost > 0 ? pnl() / cost : 0.0; }
    }

    private record OpenLot(LocalDate entryDate, int quantity, double totalCost) {}

    private final Map<String, OpenLot> openLots = new HashMap<>();
    private final List<ClosedTrade> trades = new ArrayList<>();
    private final List<Double> dailyEquity = new ArrayList<>();

    public void onBuyFill(String stockCode, int quantity, double cashOut, LocalDate date) {
        OpenLot lot = openLots.get(stockCode);
        if (lot == null) {
            openLots.put(stockCode, new OpenLot(date, quantity, cashOut));
        } else {
            openLots.put(stockCode, new OpenLot(
                    lot.entryDate(), lot.quantity() + quantity, lot.totalCost() + cashOut));
        }
    }

    public void onSellFill(String stockCode, int quantity, double cashIn,
                           LocalDate date, String exitReason) {
        OpenLot lot = openLots.get(stockCode);
        if (lot == null || lot.quantity() <= 0) return; // 방어 — 원장 없는 매도는 무시

        int closeQty = Math.min(quantity, lot.quantity());
        double costPortion = lot.totalCost() * closeQty / lot.quantity();
        trades.add(new ClosedTrade(stockCode, lot.entryDate(), date,
                closeQty, costPortion, cashIn, exitReason));

        int remaining = lot.quantity() - closeQty;
        if (remaining <= 0) {
            openLots.remove(stockCode);
        } else {
            openLots.put(stockCode, new OpenLot(
                    lot.entryDate(), remaining, lot.totalCost() - costPortion));
        }
    }

    public void recordDayEnd(double equity) {
        dailyEquity.add(equity);
    }

    public void reset() {
        openLots.clear();
        trades.clear();
        dailyEquity.clear();
    }

    public List<ClosedTrade> getTrades() {
        return List.copyOf(trades);
    }

    public List<Double> getDailyEquity() {
        return List.copyOf(dailyEquity);
    }
}
