package com.trading.risk;

/**
 * 계좌 단위 리스크 임계값의 단일 출처.
 * 진입 게이트(DailyLossRule, GlobalEquityStopRule)와 상시 감시(RiskMonitor)가
 * 같은 값을 공유해야 두 경로의 판정이 어긋나지 않는다.
 */
public final class RiskLimits {

    /** 일일 손실 -3%: 당일 신규 매수 차단 (ADR-001 2.2) */
    public static final double DAILY_LOSS_BLOCK = -0.03;

    /** 일일 손실 -5%: 강제청산 트리거 (ADR-001 2.2) */
    public static final double DAILY_LOSS_LIQUIDATE = -0.05;

    /** 전고점(peakEquity) 대비 MDD 10% 초과: 강제청산 트리거 (ADR-001 2.2) */
    public static final double MDD_LIMIT = 0.10;

    private RiskLimits() {}
}
