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

    // ── 진입 사이징 / 손절 (방법론 §4.1·4.3 — P2-A) ──────────────────────────

    /** 1회 진입 허용 손실(1R) = 계좌의 1% */
    public static final double RISK_FRACTION_PER_TRADE = 0.01;

    /** 손절폭 = ATR(14) × 이 배수. ⚠ 잠정값 — B-3 백테스트 검증 대상 (1.5~2.0) */
    public static final double ATR_STOP_MULTIPLIER = 1.5;

    /** 단주 내림으로 실제 리스크가 1R 대비 이 비율을 벗어나면 진입 스킵 (방법론 §4.3) */
    public static final double SIZING_MAX_DISTORTION = 0.20;

    // ── 포지션 한도 (CLAUDE.md 7대 리스크 룰) ────────────────────────────────

    /** 종목당 최대 비중 10% (PositionLimitRule) */
    public static final double MAX_POSITION_WEIGHT = 0.10;

    /** 최대 보유 종목 수 5개 (MaxPositionCountRule) */
    public static final int MAX_POSITION_COUNT = 5;

    private RiskLimits() {}
}
