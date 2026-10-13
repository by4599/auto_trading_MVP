package com.trading.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RSI(2) 평균회귀 전략(전략3=스캘핑 대체 후보, 2026-08) 설정.
 *
 * 매수: 장기 추세 필터(종가 &gt; MA{@code trendMaPeriod})를 통과한 종목이 단기 RSI({@code rsiPeriod})로
 * {@code oversoldThreshold} 미만까지 과매도되면 종가에 매수(Larry Connors RSI2 계열, 눌림목 되돌림).
 * 출구는 다른 전략과 동일하게 프레임워크(ATR 손절·다일 트레일링·최대보유)가 맡는다 —
 * §14 검증 경로(진입만 교체)로 MA·Donchian과 apples-to-apples 비교한다.
 *
 * enabled 기본 false — 검증 전이라 paper에서 도지 않게(§14 교훈: 백테스트 통과 전 실매매 금지),
 * 백테스트에서만 orchestrator가 켠다. 다른 전략과 신호가 섞이지 않도록 한 번에 하나만 켠다.
 * trendMaPeriod는 백테스트 워밍업(260 달력일 ≈ 거래일 180) 안에서 채워지도록 120을 기본값으로 둔다
 * (MA200은 워밍업 부족 — Donchian과 동일한 이유).
 */
@ConfigurationProperties(prefix = "trading.rsi")
public class RsiProperties {

    private volatile boolean enabled = false;
    private volatile int rsiPeriod = 2;                 // 단기 RSI 기간
    private volatile double oversoldThreshold = 10.0;   // 이 값 미만이면 과매도 진입
    private volatile int trendMaPeriod = 120;           // 추세 필터 이동평균 기간

    public boolean isEnabled()                { return enabled; }
    public void setEnabled(boolean v)         { this.enabled = v; }
    public int  getRsiPeriod()                { return rsiPeriod; }
    public void setRsiPeriod(int v)           { this.rsiPeriod = v; }
    public double getOversoldThreshold()      { return oversoldThreshold; }
    public void setOversoldThreshold(double v){ this.oversoldThreshold = v; }
    public int  getTrendMaPeriod()            { return trendMaPeriod; }
    public void setTrendMaPeriod(int v)       { this.trendMaPeriod = v; }
}
