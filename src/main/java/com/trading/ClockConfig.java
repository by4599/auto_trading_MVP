package com.trading;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * KRX 거래 시간 판정용 Clock (감사 F-8 해소).
 *
 * LocalTime.now()는 서버 타임존을 따르므로, 서버가 KST가 아니면 장 마감 룰이
 * 엉뚱한 시각에 작동한다. Asia/Seoul 고정 Clock을 주입해 타임존 의존을 제거하고,
 * 백테스트(B-2)에서는 이 빈을 가상 시계로 교체한다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
