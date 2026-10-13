package com.trading.bucket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 칸별 낙폭 한도 — ADR-001 §2.2 개정(2026-08-07): A동 −12% / B동 −20%.
 */
@DisplayName("SleeveDrawdownProperties — 칸별 낙폭 한도")
class SleeveDrawdownPropertiesTest {

    @Test
    @DisplayName("설정이 없으면 기본값이 ADR 값이다 — A동(TREND) 12%, B동(VB·EVENT·MIX) 20%")
    void defaults_are_adr_values() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(SleeveDrawdownProperties.class);
            ctx.refresh();
            SleeveDrawdownProperties limits = ctx.getBean(SleeveDrawdownProperties.class);

            assertThat(limits.limitOf(StrategyBucket.TREND)).isEqualTo(0.12);
            assertThat(limits.limitOf(StrategyBucket.VB)).isEqualTo(0.20);
            assertThat(limits.limitOf(StrategyBucket.EVENT)).isEqualTo(0.20);
            assertThat(limits.limitOf(StrategyBucket.MIX)).isEqualTo(0.20);
        }
    }

    @Test
    @DisplayName("이름표 없는(null) 칸은 VB(B동)로 본다")
    void null_bucket_is_b_sleeve() {
        assertThat(new SleeveDrawdownProperties(0.12, 0.20).limitOf(null)).isEqualTo(0.20);
    }

    @Test
    @DisplayName("0 이하·1 이상·숫자 아님(NaN)은 기동을 막는다 — 한도가 조용히 꺼지면 안 된다")
    void rejects_values_that_would_disable_the_guard() {
        assertThatThrownBy(() -> new SleeveDrawdownProperties(0.0, 0.20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SleeveDrawdownProperties(0.12, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SleeveDrawdownProperties(Double.NaN, 0.20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
