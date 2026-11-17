package com.trading.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 돈치안 채널 돌파 전략(전략1=VB 대체 후보, 2026-08) 설정.
 *
 * 매수: 직전 {@code lookback}일 고가를 종가가 상향 돌파 + 장기 추세 필터(종가 > MA{@code trendMaPeriod}).
 * 고전 추세추종(터틀). VB(당일 변동성 돌파)보다 잡음이 적은 N일 고가 돌파로 교체 검증한다.
 *
 * enabled 기본 false — 검증 전이라 paper에서 도지 않게(§14 교훈: 백테스트 통과 전 실매매 금지),
 * 백테스트에서만 orchestrator가 켠다. 다른 전략과 신호가 섞이지 않도록 한 번에 하나만 켠다.
 */
@ConfigurationProperties(prefix = "trading.donchian")
public class DonchianProperties {

    private volatile boolean enabled = false;
    private volatile int lookback = 20;        // 직전 N일 고가
    private volatile int trendMaPeriod = 120;  // 추세 필터 이동평균 기간

    public boolean isEnabled()            { return enabled; }
    public void setEnabled(boolean v)     { this.enabled = v; }
    public int  getLookback()             { return lookback; }
    public void setLookback(int v)        { this.lookback = v; }
    public int  getTrendMaPeriod()        { return trendMaPeriod; }
    public void setTrendMaPeriod(int v)   { this.trendMaPeriod = v; }
}
