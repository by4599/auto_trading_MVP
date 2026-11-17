package com.trading.risk;

import com.trading.NotificationService;
import com.trading.bucket.StrategyBucket;
import com.trading.market.CandleHistoryClient;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.Account;
import com.trading.signal.Signal;
import com.trading.strategy.FilterProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 스프링 배선 — 실행 중인 앱을 재시작하지 않고 확인할 수 있는 범위에서, 실제 컨테이너로
 * "paper에서는 새 지수 데이터원이 두 룰에 꽂히고, backtest에서는 관문 룰이 생기지 않는다"를 고정한다.
 * (옛 NoOp 데이터원과 동시에 뜨면 빈이 두 개라 기동이 실패한다 — 그 경로도 여기서 닫힌다.)
 */
@DisplayName("지수 추세 배선 — paper: 실데이터원+관문 / backtest: 관문 없음")
class IndexTrendWiringTest {

    private static AnnotationConfigApplicationContext context(String profile) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().setActiveProfiles(profile);
        FilterProperties filters = new FilterProperties();
        filters.getIndexTrend().setEnabled(true);
        filters.getIndexTrend().setMaPeriod(120);
        ctx.registerBean(FilterProperties.class, () -> filters);
        ctx.registerBean(CandleHistoryClient.class, () -> mock(CandleHistoryClient.class));
        ctx.registerBean(NotificationService.class, () -> mock(NotificationService.class));
        ctx.registerBean(Clock.class, Clock::systemDefaultZone);
        ctx.registerBean(MarketCalendarService.class,
                () -> new MarketCalendarService(new MarketCalendarProperties(), Clock.systemDefaultZone()));
        ctx.register(KisIndexRegimeSource.class, IndexTrendRule.class, IndexTrendDataGateRule.class,
                RiskEngine.class);
        return ctx;
    }

    @Test
    @DisplayName("paper — 지수 데이터원은 KisIndexRegimeSource 하나, 첫 조회 전 매수는 관문이 막는다")
    void paper_wires_real_source_and_gate() {
        try (AnnotationConfigApplicationContext ctx = context("paper")) {
            ctx.refresh();

            assertThat(ctx.getBeansOfType(IndexRegimeSource.class).values())
                    .singleElement().isInstanceOf(KisIndexRegimeSource.class);
            assertThat(ctx.getBeansOfType(IndexTrendDataGateRule.class)).hasSize(1);

            RiskResult result = ctx.getBean(RiskEngine.class).check(
                    Signal.buy("005930", "DONCHIAN", StrategyBucket.TREND),
                    new Account(10_000_000, 0.0, 0, List.of()));
            assertThat(result.isPass()).isFalse();
            assertThat(result.getReason()).contains("지수 추세 판정 불가");
        }
    }

    @Test
    @DisplayName("backtest — 관문 룰은 등록조차 되지 않는다 (회귀 앵커의 워밍업 동작 보존)")
    void backtest_profile_has_no_gate() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("backtest");
            ctx.registerBean(FilterProperties.class, FilterProperties::new);
            ctx.registerBean(IndexRegimeSource.class, () -> () -> java.util.Optional.empty());
            ctx.register(IndexTrendDataGateRule.class, KisIndexRegimeSource.class);
            ctx.refresh();

            assertThat(ctx.getBeansOfType(IndexTrendDataGateRule.class)).isEmpty();
            assertThat(ctx.getBeansOfType(KisIndexRegimeSource.class)).isEmpty();
        }
    }
}
