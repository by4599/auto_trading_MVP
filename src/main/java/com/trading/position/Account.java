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
    /**
     * 이 스냅샷이 KIS 실잔고로 갓 조회된 신선한 값인지 여부.
     * 잔고 API 실패로 낡은 캐시/DB 폴백을 쓰면 false — 청산처럼 신선 데이터가
     * 필수인 판정(RiskMonitor)은 false일 때 건너뛴다(낡은 값 오판 방지).
     */
    private final boolean fresh;
    /**
     * 잔고 총자산 대조 판정 (2026-10-11, 결함 6) — {@code fresh}와 같은 방식의 표시다.
     * 기본값은 판정 불가(= 전고점 인정 가능)라 공개 생성자를 쓰는 곳(백테스트 포함)은 동작이 그대로다.
     * 지금은 {@code ShadowPortfolio}의 전고점 인정 여부에만 쓰인다.
     */
    private final EquityCrossCheck equityCheck;

    public Account(double totalAssetValue,
                   double dailyPnlPercent,
                   int    consecutiveLossCount,
                   List<PositionSnapshot> positions) {
        this.totalAssetValue      = totalAssetValue;
        this.dailyPnlPercent      = dailyPnlPercent;
        this.consecutiveLossCount = consecutiveLossCount;
        this.positions            = List.copyOf(positions);
        this.fresh                = true;
        this.equityCheck          = EquityCrossCheck.unchecked();
    }

    /** 표시(신선도·대조 판정)만 바꾼 사본 — 값은 그대로 */
    private Account(Account base, boolean fresh, EquityCrossCheck equityCheck) {
        this.totalAssetValue      = base.totalAssetValue;
        this.dailyPnlPercent      = base.dailyPnlPercent;
        this.consecutiveLossCount = base.consecutiveLossCount;
        this.positions            = base.positions;
        this.fresh                = fresh;
        this.equityCheck          = equityCheck;
    }

    /** 낡은(폴백) 스냅샷 표시본 — 신선 데이터가 필요한 판정에서 걸러내기 위함. 대조 판정은 그대로 둔다 */
    public Account asStale() {
        return new Account(this, false, equityCheck);
    }

    /** 대조 판정을 단 사본 — 신선도는 그대로 둔다. null이면 판정 불가 */
    public Account withEquityCheck(EquityCrossCheck check) {
        return new Account(this, fresh, check == null ? EquityCrossCheck.unchecked() : check);
    }

    public EquityCrossCheck getEquityCheck()       { return equityCheck; }
    /** 증권사 총자산이 직접 계산한 값과 허용오차 넘게 어긋났는가 — 전고점 인정 거부 신호 */
    public boolean isEquityMismatch()              { return equityCheck.isMismatch(); }

    public double  getTotalAssetValue()            { return totalAssetValue; }
    public double  getDailyPnlPercent()            { return dailyPnlPercent; }
    public boolean isFresh()                       { return fresh; }
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
