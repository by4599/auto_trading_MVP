package com.trading.control;

import com.trading.NotificationService;
import com.trading.bucket.BucketAccountService;
import com.trading.bucket.BucketProperties;
import com.trading.bucket.BucketTestSupport;
import com.trading.market.AtrCalculator;
import com.trading.market.KisProperties;
import com.trading.market.MarketDataService;
import com.trading.order.KisOrderClient;
import com.trading.order.OrderEngine;
import com.trading.order.OrderSizingService;
import com.trading.position.Account;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResultRepository;
import com.trading.risk.ActualAccountInfo;
import com.trading.risk.BrokerageApiClient;
import com.trading.risk.LiquidationService;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskLimitsProperties;
import com.trading.risk.TradingStatusManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 리허설 조작 관문의 프로필 가드 — 실계좌 오발동 차단 (risk-auditor 2026-08-12 MEDIUM).
 *
 * <p>두 가지를 고정한다: ① 청산 리허설도 매수 리허설과 <b>같은</b> 모의(paper) 프로필 가드를
 * 지난다(예전엔 청산에만 가드가 없었다) ② 판정 조건은 "paper 포함"이 아니라
 * <b>"real 미포함 + paper 명시"</b>다 — {@code paper,real} 동시 활성은 실주문이 나갈 수 있는
 * 조합이라 통과시키지 않는다.
 *
 * <p>Java 25 인라인 목 제약: 구체 클래스(RiskEngine·OrderEngine·LiquidationService)는 목킹하지
 * 않고 인터페이스 의존성만 목으로 조립한다.
 */
@DisplayName("DrillService — 리허설 프로필 가드")
class DrillServiceTest {

    private TradingStatusManager statusManager;
    private LiquidationService liquidationService;
    private BrokerageApiClient brokerageClient;
    private KisOrderClient orderClient;
    private PositionManager positionManager;
    private PositionRepository positionRepository;
    private OrderEngine orderEngine;

    @BeforeEach
    void setUp() {
        statusManager = new TradingStatusManager();   // 기본 RUNNING
        brokerageClient = mock(BrokerageApiClient.class);
        when(brokerageClient.getActualAccountAsset()).thenReturn(new ActualAccountInfo(List.of()));
        liquidationService = new LiquidationService(
                brokerageClient, statusManager, mock(NotificationService.class));

        orderClient = mock(KisOrderClient.class);
        positionManager = mock(PositionManager.class);
        when(positionManager.snapshotAccount()).thenReturn(new Account(10_000_000, 0.0, 0, List.of()));
        positionRepository = mock(PositionRepository.class);
        when(positionRepository.findAll()).thenReturn(List.of());
        orderEngine = newOrderEngine();
    }

    private OrderEngine newOrderEngine() {
        BucketProperties bucketProps = new BucketProperties(
                false, "2026-07-20", 10_000_000, 10_000_000, 10_000_000, 10_000_000, false, false, false);
        OrderSizingService sizing = new OrderSizingService(
                mock(MarketDataService.class), positionManager, new AtrCalculator(),
                new RiskLimitsProperties(), bucketProps,
                new BucketAccountService(bucketProps, positionRepository, mock(TradeResultRepository.class)),
                BucketTestSupport.defaultParams());
        return new OrderEngine(orderClient, statusManager, sizing, positionRepository);
    }

    private DrillService drillWith(String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(activeProfiles);
        return drillWith((Environment) env);
    }

    private DrillService drillWith(Environment environment) {
        return new DrillService(configuredProps(), statusManager, new RiskEngine(List.of()),
                orderEngine, positionManager, positionRepository, liquidationService, environment);
    }

    private static KisProperties configuredProps() {
        KisProperties p = new KisProperties();
        p.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        p.setAppkey("test-key");
        p.setSecretkey("test-secret");
        p.setAccountNo("50000000-01");
        return p;
    }

    // ── liquidate(): 프로필 가드 (문제 A) ──────────────────────────────────────

    @Test
    @DisplayName("real 프로필에서는 청산 리허설을 개시하지 않는다")
    void liquidate_rejected_on_real_profile() {
        DrillOperations.Outcome outcome = drillWith("real").liquidate();

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("paper");
        assertThat(liquidationService.isAnyLiquidationInProgress()).isFalse();
        verify(brokerageClient, never()).getActualAccountAsset();
    }

    @Test
    @DisplayName("프로필 미설정(빈 배열)에서도 청산 리허설을 개시하지 않는다 — fail-closed")
    void liquidate_rejected_when_no_profile() {
        DrillOperations.Outcome outcome = drillWith(new MockEnvironment()).liquidate();

        assertThat(outcome.success()).isFalse();
        assertThat(liquidationService.isAnyLiquidationInProgress()).isFalse();
    }

    @Test
    @DisplayName("paper 프로필에서는 청산 리허설이 정상 개시된다 — 가드가 정당한 훈련을 막지 않는다")
    void liquidate_allowed_on_paper_profile() {
        DrillOperations.Outcome outcome = drillWith("paper").liquidate();

        assertThat(outcome.success()).isTrue();
        assertThat(liquidationService.isAnyLiquidationInProgress()).isTrue();
    }

    // ── paper+real 동시 활성 (문제 B) ──────────────────────────────────────────

    @Test
    @DisplayName("paper와 real이 동시에 켜져 있으면 청산 리허설을 차단한다")
    void liquidate_rejected_when_real_also_active() {
        DrillOperations.Outcome outcome = drillWith("paper", "real").liquidate();

        assertThat(outcome.success()).isFalse();
        assertThat(liquidationService.isAnyLiquidationInProgress()).isFalse();
    }

    @Test
    @DisplayName("paper와 real이 동시에 켜져 있으면 수동 매수 리허설도 차단한다")
    void manual_buy_rejected_when_real_also_active() {
        DrillOperations.Outcome outcome = drillWith("paper", "real").manualBuy();

        assertThat(outcome.success()).isFalse();
        verify(orderClient, never()).buy(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("paper 단독이면 수동 매수 리허설은 그대로 동작한다")
    void manual_buy_allowed_on_paper_profile() {
        DrillOperations.Outcome outcome = drillWith("paper").manualBuy();

        assertThat(outcome.success()).isTrue();
        verify(orderClient).buy(DrillService.STOCK_CODE, DrillService.QUANTITY,
                com.trading.bucket.StrategyBucket.VB);
    }
}
