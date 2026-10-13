package com.trading.risk;

import com.trading.NotificationService;
import com.trading.PaperProfileYaml;
import com.trading.bucket.BucketProperties;
import com.trading.bucket.BucketTestSupport;
import com.trading.bucket.InMemoryPortfolioState;
import com.trading.bucket.SleeveDrawdownProperties;
import com.trading.bucket.SleeveEquityCalculator;
import com.trading.bucket.SleeveRealizedLedger;
import com.trading.bucket.SleeveRealizedSource;
import com.trading.bucket.SleeveStateStore;
import com.trading.bucket.StrategyBucket;
import com.trading.control.SleeveController;
import com.trading.dashboard.SellPriceEstimator;
import com.trading.dashboard.SleeveRealizedPnlAdapter;
import com.trading.market.AtrCalculator;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.market.MarketDataService;
import com.trading.order.KisOrderClient;
import com.trading.order.OrderEngine;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSizingService;
import com.trading.position.Account;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResultRepository;
import com.trading.signal.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 스프링 배선 — 실제 컨테이너로 "paper에는 감시기·잠금 룰·조회 API가 생기고 서로 꽂힌다 /
 * backtest에는 하나도 생기지 않는다(결정성)"를 고정한다. paper 쪽은 배포될 yml을 그대로 읽어
 * 한도 키(12%/20%)가 실제로 빈까지 들어가는지도 본다 — 키가 어긋나면 기본값으로 조용히 떨어진다.
 */
@DisplayName("칸 낙폭 상한 배선 — paper: 감시기·잠금 룰·조회 API / backtest: 없음")
class SleeveDrawdownWiringTest {

    private static final Account ACCOUNT = new Account(10_000_000, 0.0, 0, List.of());

    /** 감시기 묶음 전체 — 외부 연동(인터페이스)만 목으로 채운다 */
    private static AnnotationConfigApplicationContext paperContext(Map<String, Double> state) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().setActiveProfiles("paper");
        PaperProfileYaml.sources().forEach(ctx.getEnvironment().getPropertySources()::addLast);
        ctx.registerBean(PortfolioStateRepository.class, () -> InMemoryPortfolioState.create(state));
        ctx.registerBean(PositionRepository.class, () -> mock(PositionRepository.class));
        ctx.registerBean(OrderHistoryRepository.class, () -> mock(OrderHistoryRepository.class));
        ctx.registerBean(TradeResultRepository.class, () -> mock(TradeResultRepository.class));
        ctx.registerBean(CandleHistoryRepository.class, () -> mock(CandleHistoryRepository.class));
        ctx.registerBean(PositionManager.class, () -> mock(PositionManager.class));
        ctx.registerBean(KisOrderClient.class, () -> mock(KisOrderClient.class));
        ctx.registerBean(NotificationService.class, () -> mock(NotificationService.class));
        ctx.registerBean(Clock.class, Clock::systemDefaultZone);
        ctx.registerBean(KisProperties.class, KisProperties::new);
        ctx.registerBean(MarketCalendarService.class,
                () -> new MarketCalendarService(new MarketCalendarProperties(), Clock.systemDefaultZone()));
        ctx.registerBean(OrderSizingService.class, () -> new OrderSizingService(mock(MarketDataService.class),
                mock(PositionManager.class), new AtrCalculator(), new RiskLimitsProperties(),
                BucketTestSupport.disabledProps(), BucketTestSupport.disabledAccounts(),
                BucketTestSupport.defaultParams()));
        ctx.register(BucketProperties.class, SleeveDrawdownProperties.class, SleeveStateStore.class,
                SleeveLockRule.class, RiskEngine.class, TradingStatusManager.class, OrderEngine.class,
                SellPriceEstimator.class, SleeveRealizedPnlAdapter.class, SleeveRealizedLedger.class,
                SleeveEquityCalculator.class, SleeveDrawdownGuard.class, SleeveLiquidator.class,
                SleeveDrawdownMonitor.class, SleeveController.class);
        return ctx;
    }

    @Test
    @DisplayName("paper — 감시기·매도기·판정기·계산기·원천 어댑터·조회 API가 하나씩 생긴다")
    void paper_context_creates_the_whole_chain() {
        try (AnnotationConfigApplicationContext ctx = paperContext(new HashMap<>())) {
            ctx.refresh();

            assertThat(ctx.getBeansOfType(SleeveDrawdownMonitor.class)).hasSize(1);
            assertThat(ctx.getBeansOfType(SleeveLiquidator.class)).hasSize(1);
            assertThat(ctx.getBeansOfType(SleeveDrawdownGuard.class)).hasSize(1);
            assertThat(ctx.getBeansOfType(SleeveEquityCalculator.class)).hasSize(1);
            assertThat(ctx.getBeansOfType(SleeveController.class)).hasSize(1);
            assertThat(ctx.getBeansOfType(SleeveRealizedSource.class).values())
                    .singleElement().isInstanceOf(SleeveRealizedPnlAdapter.class);
        }
    }

    @Test
    @DisplayName("paper — yml 한도(12%/20%)가 빈에 들어가고, 잠긴 A동의 매수만 RiskEngine이 막는다")
    void paper_yml_limits_and_lock_rule_are_wired() {
        Map<String, Double> state = new HashMap<>();
        new SleeveStateStore(InMemoryPortfolioState.create(state)).lock(StrategyBucket.TREND,
                new SleeveStateStore.LockState(true, Instant.EPOCH, 0.125, 3_500_000, 0.12));
        try (AnnotationConfigApplicationContext ctx = paperContext(state)) {
            ctx.refresh();

            SleeveDrawdownProperties limits = ctx.getBean(SleeveDrawdownProperties.class);
            assertThat(limits.limitOf(StrategyBucket.TREND)).isEqualTo(0.12);
            assertThat(limits.limitOf(StrategyBucket.VB)).isEqualTo(0.20);

            RiskEngine engine = ctx.getBean(RiskEngine.class);
            RiskResult trendBuy = engine.check(Signal.buy("005930", "DONCHIAN", StrategyBucket.TREND), ACCOUNT);
            assertThat(trendBuy.isPass()).isFalse();
            assertThat(trendBuy.getReason()).contains("칸 손실 상한");
            assertThat(engine.check(Signal.sell("005930", "StopLoss-ATR"), ACCOUNT).isPass()).isTrue();
            assertThat(engine.check(Signal.buy("000660", "VB", StrategyBucket.VB), ACCOUNT).isPass()).isTrue();
        }
    }

    @Test
    @DisplayName("backtest — 잠금 룰·감시기·조회 API·원천 어댑터가 등록조차 되지 않는다 (결정성)")
    void backtest_profile_has_none_of_them() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("backtest");
            ctx.register(SleeveStateStore.class, SleeveLockRule.class, SleeveRealizedPnlAdapter.class,
                    SleeveRealizedLedger.class, SleeveEquityCalculator.class, SleeveDrawdownGuard.class,
                    SleeveLiquidator.class, SleeveDrawdownMonitor.class, SleeveController.class);
            ctx.refresh();

            assertThat(ctx.getBeansOfType(SleeveLockRule.class)).isEmpty();
            assertThat(ctx.getBeansOfType(SleeveStateStore.class)).isEmpty();
            assertThat(ctx.getBeansOfType(SleeveDrawdownMonitor.class)).isEmpty();
            assertThat(ctx.getBeansOfType(SleeveController.class)).isEmpty();
            assertThat(ctx.getBeansOfType(SleeveRealizedSource.class)).isEmpty();
        }
    }
}
