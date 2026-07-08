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
    public static final String KEY_CONSECUTIVE_LOSS_COUNT = "CONSECUTIVE_LOSS_COUNT";

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
