package com.trading.position;

import java.util.List;

/**
 * 현재 계좌 상태 스냅샷 (불변 객체).
 * TradingScheduler → RiskEngine → 각 RiskRule에서 읽는다.
 *
 * 기존 Account.java가 있다면 이 파일을 참고해 필드를 병합하면 된다.
 * RiskEngine 5개 룰이 필요로 하는 최소 필드 기준으로 설계됐다.
 */
public final class Account {

    /**
     * 종목별 보유 현황 스냅샷.
     * currentPrice는 KisPositionManager가 Position 테이블의 averagePrice로 대체하며,
     * Sprint 3에서 현재가 API 호출로 교체 예정이다.
     */
    public record PositionSnapshot(
            String stockCode,
            int    quantity,
            double averagePrice,
            double currentPrice
    ) {
        double marketValue() { return (double) quantity * currentPrice; }
    }

    private final double totalAssetValue;
    private final double dailyPnlPercent;        // DailyLossRule 사용
    private final int    consecutiveLossCount;    // ConsecutiveLossRule 사용
    private final List<PositionSnapshot> positions;

    public Account(double totalAssetValue,
                   double dailyPnlPercent,
                   int    consecutiveLossCount,
                   List<PositionSnapshot> positions) {
        this.totalAssetValue      = totalAssetValue;
        this.dailyPnlPercent      = dailyPnlPercent;
        this.consecutiveLossCount = consecutiveLossCount;
        this.positions            = List.copyOf(positions);
    }

    public double getDailyPnlPercent()            { return dailyPnlPercent; }
    public int    getConsecutiveLossCount()        { return consecutiveLossCount; }
    public int    getPositionCount()               { return positions.size(); }
    public List<PositionSnapshot> getPositions()  { return positions; }

    /**
     * 특정 종목의 포트폴리오 비중 (0.0 ~ 1.0).
     * PositionLimitRule이 10% 초과 여부를 판단하는 데 사용한다.
     */
    public double getPositionWeight(String stockCode) {
        if (totalAssetValue <= 0) return 0.0;
        return positions.stream()
                .filter(p -> p.stockCode().equals(stockCode))
                .mapToDouble(p -> p.marketValue() / totalAssetValue)
                .findFirst()
                .orElse(0.0);
    }
}
