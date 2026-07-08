package com.trading.position;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 포트폴리오 수준의 영속 상태 (key-value).
 * 현재 유일한 용도는 peakEquity — ADR-001 2.2 "영구 보존, 08:30 리셋 대상 아님"을
 * 재시작을 넘어 보장하기 위해 DB에 저장한다.
 */
@Entity
@Table(name = "portfolio_state")
public class PortfolioState {

    public static final String KEY_PEAK_EQUITY = "PEAK_EQUITY";

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
