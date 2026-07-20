package com.trading.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 스캘핑 전략(방식3) 파라미터 — 2026-07-20, 검증 없이 모의투자 직결(사용자 판단).
 *
 * 눌림목 반등 모멘텀: 최근 관찰창(windowSize틱)의 고점 대비 pullbackPct 이상 눌렸다가
 * 그 저점 대비 reboundPct 이상 반등하면 매수. 청산은 ATR 손절/15:15 타임컷에 더해
 * takeProfitPct 목표 수익 도달 시 즉시 익절(StopLossMonitor).
 */
@ConfigurationProperties(prefix = "trading.scalping")
public class ScalpingProperties {

    private volatile boolean enabled = false;
    private volatile int windowSize = 10;
    private volatile double pullbackPct = 0.005;   // 고점 대비 0.5% 이상 눌림
    private volatile double reboundPct = 0.003;    // 저점 대비 0.3% 이상 반등 시 진입
    private volatile double takeProfitPct = 0.008; // +0.8% 목표 익절

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getWindowSize() { return windowSize; }
    public void setWindowSize(int windowSize) { this.windowSize = windowSize; }

    public double getPullbackPct() { return pullbackPct; }
    public void setPullbackPct(double pullbackPct) { this.pullbackPct = pullbackPct; }

    public double getReboundPct() { return reboundPct; }
    public void setReboundPct(double reboundPct) { this.reboundPct = reboundPct; }

    public double getTakeProfitPct() { return takeProfitPct; }
    public void setTakeProfitPct(double takeProfitPct) { this.takeProfitPct = takeProfitPct; }
}
