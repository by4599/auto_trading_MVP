package com.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 진입점.
 *
 * com.trading 패키지에 위치 → 하위 패키지 (order, market, risk, ...) 전부 컴포넌트 스캔.
 * RetryConfig(@EnableRetry), TradingScheduler(@Scheduled) 모두 같은 스캔 범위 안.
 *
 * @ConfigurationPropertiesScan: @ConfigurationProperties 클래스를 자동 등록한다.
 *   - KisProperties  (com.trading.market)
 *   - TelegramProperties (com.trading)
 */
@SpringBootApplication
// @EnableScheduling은 SchedulingConfig로 이동 — backtest 프로파일에서는 월클럭 스케줄러가
// 가상 시계 재생과 경쟁해 결정성을 깨뜨리므로 (daily_equity 동시 삽입 사고) 비활성화한다.
@ConfigurationPropertiesScan("com.trading")
public class TradingApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradingApplication.class, args);
    }
}
