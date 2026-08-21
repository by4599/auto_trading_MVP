package com.trading.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이동평균 정배열 돌파 전략(방식2) 스위치.
 *
 * enabled(기본 true)는 방식2·3 소급 검증(BACKTEST-DESIGN §13)에서 VB/MA돌파/스캘핑을
 * 한 번에 하나씩만 켜서 전략별로 신호를 격리 측정하기 위한 것 — paper에서는 아무도
 * false로 설정하지 않으므로 기존 동작 불변.
 */
@ConfigurationProperties(prefix = "trading.ma-breakout")
public class MaBreakoutProperties {

    private volatile boolean enabled = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
