package com.trading.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 전략 파라미터 (B-2/B-3).
 *
 * 프로덕션 기본값은 기존 상수(K=0.5)와 동일 — application.yml 미설정 시 동작 불변.
 * 백테스트 오케스트레이터가 K 스윕/Walk-Forward 런마다 setK()로 갈아끼운다
 * (라이브 프로파일에서는 아무도 setK를 호출하지 않는다).
 *
 * enabled(기본 true)는 방식2·3(BACKTEST-DESIGN §13) 소급 검증에서 VB/MA돌파/스캘핑을
 * 한 번에 하나씩만 켜서 전략별로 신호를 격리 측정하기 위한 스위치 — paper에서는
 * 아무도 false로 설정하지 않으므로 기존 동작 불변.
 */
@ConfigurationProperties(prefix = "trading.strategy")
public class StrategyParameters {

    private volatile double k = 0.5;
    private volatile boolean enabled = true;

    public double getK() { return k; }
    public void setK(double k) { this.k = k; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
