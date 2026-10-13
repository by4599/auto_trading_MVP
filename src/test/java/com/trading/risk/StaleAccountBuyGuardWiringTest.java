package com.trading.risk;

import com.trading.PaperProfileYaml;
import com.trading.bucket.StrategyBucket;
import com.trading.position.Account;
import com.trading.signal.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스프링 배선 — 실제 paper 설정 파일로 컨테이너를 띄워 {@code @Value} 키와 yml 키가 같은지 고정한다.
 * 키가 어긋나면 기본값(false)으로 조용히 떨어져 가드가 꺼진 채 단위 테스트는 전부 통과한다(40_audit L-1).
 */
@DisplayName("낡은 잔고 매수 차단 배선 — paper: 켜져서 막는다 / backtest: 빈 없음")
class StaleAccountBuyGuardWiringTest {

    @Test
    @DisplayName("paper 설정으로 띄우면 룰이 켜져 낡은 스냅샷의 매수를 막는다")
    void paper_config_turns_the_guard_on() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("paper");
            PaperProfileYaml.sources().forEach(ctx.getEnvironment().getPropertySources()::addLast);
            ctx.register(StaleAccountBuyGuardRule.class, RiskEngine.class);
            ctx.refresh();

            RiskResult result = ctx.getBean(RiskEngine.class).check(
                    Signal.buy("005930", "DONCHIAN", StrategyBucket.TREND),
                    new Account(10_000_000, 0.0, 0, List.of()).asStale());
            assertThat(result.isPass()).isFalse();
            assertThat(result.getReason()).contains("잔고 정보가 낡음");
        }
    }

    @Test
    @DisplayName("backtest — 룰이 등록조차 되지 않는다")
    void backtest_profile_has_no_guard() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("backtest");
            ctx.register(StaleAccountBuyGuardRule.class);
            ctx.refresh();

            assertThat(ctx.getBeansOfType(StaleAccountBuyGuardRule.class)).isEmpty();
        }
    }
}
