package com.trading;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Spring Retry 활성화.
 *
 * 의존성 추가 필요 (build.gradle 또는 pom.xml):
 *   implementation 'org.springframework.retry:spring-retry'
 *   implementation 'org.springframework:spring-aspects'    // AOP 지원
 *
 * 용도: FillStateUpdater 의 @Retryable 메서드 — DB 커밋 단계의 낙관적 락 충돌만 재시도.
 *       HTTP 체결조회는 재호출하지 않으므로 API 사용량이 늘지 않는다.
 */
@Configuration
@EnableRetry
public class RetryConfig {}
