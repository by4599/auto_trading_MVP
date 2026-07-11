package com.trading.backtest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.Instant;

/**
 * backtest 프로파일에서 ClockConfig의 시스템 시계 대신 가상 시계를 주입한다 (B-2).
 * @Primary — Clock을 주입받는 모든 빈(MarketCloseRule, ConsecutiveLossRule,
 * BacktestPositionManager 등)이 이 시계를 본다.
 */
@Configuration
@Profile("backtest")
public class BacktestClockConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.now());
    }
}
