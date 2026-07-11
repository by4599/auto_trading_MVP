package com.trading.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 전략 파라미터 (B-2/B-3).
 *
 * 프로덕션 기본값은 기존 상수(K=0.5)와 동일 — application.yml 미설정 시 동작 불변.
 * 백테스트 오케스트레이터가 K 스윕/Walk-Forward 런마다 setK()로 갈아끼운다
 * (라이브 프로파일에서는 아무도 setK를 호출하지 않는다).
 */
@ConfigurationProperties(prefix = "trading.strategy")
public class StrategyParameters {

    private volatile double k = 0.5;

    public double getK() { return k; }
    public void setK(double k) { this.k = k; }
}
