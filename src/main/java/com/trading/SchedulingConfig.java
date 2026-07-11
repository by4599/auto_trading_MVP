package com.trading;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 월클럭 스케줄링 활성화 — backtest 프로파일 제외.
 *
 * 백테스트는 MutableClock으로 시간을 재생하는데, @Scheduled(실제 시계)가 함께 돌면
 * ShadowPortfolio.tick() 등이 재생 스레드와 경쟁해 결정성이 깨진다
 * (실사고: daily_equity 동시 삽입 PK 충돌). 백테스트에서 tick()은 BacktestRunner가
 * 봉마다 직접 호출한다.
 */
@Configuration
@EnableScheduling
@Profile("!backtest")
public class SchedulingConfig {
}
