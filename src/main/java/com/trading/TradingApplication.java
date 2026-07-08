package com.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

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
@EnableScheduling
@ConfigurationPropertiesScan("com.trading")
public class TradingApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradingApplication.class, args);
    }
}
