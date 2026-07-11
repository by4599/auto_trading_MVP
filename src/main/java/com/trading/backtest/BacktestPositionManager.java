package com.trading.backtest;

import com.trading.position.Account;
import com.trading.position.DailyEquity;
import com.trading.position.DailyEquityRepository;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResultTracker;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 인메모리 현금 + JPA Position 혼합 계좌 (B-2).
 *
 * equity = 현금 + Σ(수량 × 시뮬 현재가) — F-1 교정(현금 포함)과 동일 정의.
 * dailyPnl은 KisPositionManager와 같은 daily_equity 패턴이되 시뮬 Clock 날짜를 쓴다.
 * 캐시 없음 — 결정적 재생을 위해 매 호출 신선 계산.
 */
@Service
@Profile("backtest")
public class BacktestPositionManager implements PositionManager {

    private final PositionRepository positionRepository;
    private final DailyEquityRepository dailyEquityRepository;
    private final TradeResultTracker tradeResultTracker;
    private final BacktestMarketDataService market;
    private final BacktestDataProperties properties;
    private final Clock clock;

    private volatile double cash;

    public BacktestPositionManager(PositionRepository positionRepository,
                                   DailyEquityRepository dailyEquityRepository,
                                   TradeResultTracker tradeResultTracker,
                                   BacktestMarketDataService market,
                                   BacktestDataProperties properties,
                                   Clock clock) {
        this.positionRepository = positionRepository;
        this.dailyEquityRepository = dailyEquityRepository;
        this.tradeResultTracker = tradeResultTracker;
        this.market = market;
        this.properties = properties;
        this.clock = clock;
        this.cash = properties.getInitialCash();
    }

    // ── 현금 원장 (BacktestOrderClient가 호출) ────────────────────────────────

    public double getCash() {
        return cash;
    }

    public void debit(double amount) {
        cash -= amount;
    }

    public void credit(double amount) {
        cash += amount;
    }

    /** 런 간 리셋 — 초기 현금으로 복원 */
    public void resetCash() {
        cash = properties.getInitialCash();
    }

    // ── PositionManager 구현 ──────────────────────────────────────────────────

    @Override
    public Account snapshotAccount() {
        List<Account.PositionSnapshot> snapshots = positionRepository.findAll().stream()
                .filter(p -> p.getQuantity() > 0)
                .map(p -> new Account.PositionSnapshot(
                        p.getStockCode(), p.getQuantity(), p.getAveragePrice(),
                        market.currentPrice(p.getStockCode())))
                .toList();

        double holdingsValue = snapshots.stream()
                .mapToDouble(s -> s.quantity() * s.currentPrice())
                .sum();
        double totalAssetValue = cash + holdingsValue;

        return new Account(totalAssetValue, computeDailyPnl(totalAssetValue),
                tradeResultTracker.getConsecutiveLossCount(), snapshots);
    }

    /** 당일(시뮬 날짜) 첫 스냅샷 자산을 기준으로 등락률 계산 — KisPositionManager와 동일 패턴 */
    private double computeDailyPnl(double currentEquity) {
        if (currentEquity <= 0) return 0.0;

        LocalDate today = LocalDate.now(clock);
        DailyEquity start = dailyEquityRepository.findById(today).orElseGet(
                () -> dailyEquityRepository.save(DailyEquity.of(today, currentEquity)));

        if (start.getStartEquity() <= 0) return 0.0;
        return (currentEquity - start.getStartEquity()) / start.getStartEquity();
    }
}
