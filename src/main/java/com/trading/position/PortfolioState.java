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
    public static final String KEY_CONSECUTIVE_LOSS_COUNT = "CONSECUTIVE_LOSS_COUNT";
    // 연속 무중단 가동 기록 (릴리즈 검증 항목) — RunStreakRecorder가 거래일마다 갱신
    public static final String KEY_RUN_STREAK_DAYS = "RUN_STREAK_DAYS";
    public static final String KEY_RUN_STREAK_LAST_DATE = "RUN_STREAK_LAST_DATE";  // yyyyMMdd 숫자

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
