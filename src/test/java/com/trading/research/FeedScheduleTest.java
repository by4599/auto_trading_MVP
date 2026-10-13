package com.trading.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 뉴스·공시 수집 주기는 "끝난 뒤 30분"(fixedDelay)이어야 한다. fixedRate면 PC가 절전에서 깨어날 때
 * 밀린 횟수만큼 몰아서 돈다 — 10-09 실측 뉴스 181회/약 9.4분. 그동안 같은 I/O 풀을 쓰는 하트비트·미러가 밀린다(42_audit M-1).
 */
@DisplayName("뉴스·공시 수집 — 절전 뒤 몰아 돌기 없는 주기")
class FeedScheduleTest {

    @Test
    @DisplayName("뉴스 수집은 fixedDelay 30분")
    void news_aggregate_uses_fixed_delay() throws Exception {
        Scheduled s = NewsAggregatorService.class.getMethod("aggregate").getAnnotation(Scheduled.class);

        assertThat(s.fixedDelay()).isEqualTo(1_800_000L);
        assertThat(s.fixedRate()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("공시 수집은 fixedDelay 30분, 첫 실행은 기동 2분 뒤 그대로")
    void dart_aggregate_uses_fixed_delay() throws Exception {
        Scheduled s = DartDisclosureService.class.getMethod("scheduledAggregate").getAnnotation(Scheduled.class);

        assertThat(s.fixedDelay()).isEqualTo(1_800_000L);
        assertThat(s.fixedRate()).isEqualTo(-1L);
        assertThat(s.initialDelay()).isEqualTo(120_000L);
    }
}
