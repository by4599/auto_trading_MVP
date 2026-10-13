package com.trading.position;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 포트폴리오 수준의 영속 상태 (key-value).
 * peakEquity(ADR-001 2.2 "영구 보존")와 연속 손실 카운터(F-5)를
 * 재시작을 넘어 보장하기 위해 DB에 저장한다.
 */
@Entity
@Table(name = "portfolio_state")
public class PortfolioState {

    public static final String KEY_PEAK_EQUITY = "PEAK_EQUITY";
    /** 실측 검증으로 전고점을 낮추기 직전의 원값 — 오염 원인 조사용 보존 (최근 1회) */
    public static final String KEY_PEAK_EQUITY_RAW_BEFORE_CALIBRATION = "PEAK_EQUITY_RAW_BEFORE_CALIBRATION";
    /**
     * 전고점이 실측 근거로 클램프 교정돼 아직 사람 확인을 받지 못한 상태 (1=미검증, 0=확인 완료).
     * 미검증인 동안 MDD 자동 강제청산만 보류한다 — 매수 차단은 유지 (2026-08-21 감사 MEDIUM).
     * 재시작으로 조용히 풀리면 안 되므로 메모리 플래그가 아니라 여기에 남긴다.
     */
    public static final String KEY_PEAK_EQUITY_UNVERIFIED = "PEAK_EQUITY_UNVERIFIED";
    public static final String KEY_CONSECUTIVE_LOSS_COUNT = "CONSECUTIVE_LOSS_COUNT";
    // 연속 무중단 가동 기록 (릴리즈 검증 항목) — RunStreakRecorder가 거래일마다 갱신
    public static final String KEY_RUN_STREAK_DAYS = "RUN_STREAK_DAYS";
    public static final String KEY_RUN_STREAK_LAST_DATE = "RUN_STREAK_LAST_DATE";  // yyyyMMdd 숫자
    // 예약 청산 리허설 진행 표시 — 같은 날 중복 발동을 막는다 (둘 다 yyyyMMdd 숫자)
    public static final String KEY_DRILL_BUY_DATE  = "DRILL_BUY_DATE";
    public static final String KEY_DRILL_DONE_DATE = "DRILL_DONE_DATE";

    @Id
    @Column(name = "state_key", nullable = false, length = 50)
    private String stateKey;

    @Column(name = "state_value", nullable = false)
    private double stateValue;

    protected PortfolioState() {}

    public static PortfolioState of(String key, double value) {
        PortfolioState s = new PortfolioState();
        s.stateKey   = key;
        s.stateValue = value;
        return s;
    }

    public String getStateKey()   { return stateKey; }
    public double getStateValue() { return stateValue; }
}
