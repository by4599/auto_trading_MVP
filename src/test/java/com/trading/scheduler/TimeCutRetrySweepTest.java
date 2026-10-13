package com.trading.scheduler;

import com.trading.NotificationService;
import com.trading.bucket.BucketParameterResolver;
import com.trading.bucket.BucketParameters;
import com.trading.bucket.BucketTestSupport;
import com.trading.bucket.StrategyBucket;
import com.trading.market.AtrCalculator;
import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.market.MarketDataService;
import com.trading.order.KisOrderClient;
import com.trading.order.OrderEngine;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderSizingService;
import com.trading.order.OrderStatus;
import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.risk.BrokerageApiClient;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskLimitsProperties;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import com.trading.strategy.FilterProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 타임컷 재시도 스윕 단위 테스트.
 *
 * 2026-09-01: 15:16:34 066570 5주 매도가 Read timeout으로 끊겼는데 재시도 경로가 없어
 * 그 종목만 정리되지 않은 채 밤을 넘겼다. 스윕은 그 구멍을 막는다.
 *
 * TimeCutSchedulerTest와 같은 조립 방식 — Java 25 인라인 Mockito 제약 때문에
 * 인터페이스(PositionRepository/KisOrderClient/BrokerageApiClient/NotificationService)만
 * 목이고 RiskEngine/OrderEngine/TradingStatusManager는 실객체다.
 */
@DisplayName("TimeCutScheduler 재시도 스윕 — 15:15에 못 판 보유분을 마감 전에 다시 판다")
class TimeCutRetrySweepTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private PositionRepository positionRepository;
    private OrderHistoryRepository orderHistoryRepository;
    private PositionManager positionManager;
    private KisOrderClient orderClient;
    private BrokerageApiClient brokerageClient;
    private NotificationService notifier;
    private TradingStatusManager statusManager;
    private KisProperties kisProperties;
    private MarketCalendarService marketCalendarService;
    private BucketParameterResolver bucketParams;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        orderHistoryRepository = mock(OrderHistoryRepository.class);
        positionManager = mock(PositionManager.class);
        orderClient = mock(KisOrderClient.class);
        brokerageClient = mock(BrokerageApiClient.class);
        notifier = mock(NotificationService.class);
        statusManager = new TradingStatusManager();
        marketCalendarService = marketCalendarAt(LocalDate.of(2026, 9, 1)); // 화요일, 평일
        bucketParams = BucketTestSupport.defaultParams();

        kisProperties = new KisProperties();
        kisProperties.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        kisProperties.setAppkey("test-appkey");
        kisProperties.setSecretkey("test-secretkey");
        kisProperties.setAccountNo("50000000-01");

        when(positionManager.snapshotAccount())
                .thenReturn(new Account(1_000_000.0, 0.0, 0, List.of()));
    }

    private static MarketCalendarService marketCalendarAt(LocalDate date) {
        Clock fixed = Clock.fixed(LocalDateTime.of(date, LocalTime.NOON).atZone(KST).toInstant(), KST);
        return new MarketCalendarService(new MarketCalendarProperties(), fixed);
    }

    private TimeCutScheduler scheduler() {
        OrderEngine orderEngine = new OrderEngine(orderClient, statusManager,
                new OrderSizingService(mock(MarketDataService.class), positionManager, new AtrCalculator(),
                        new RiskLimitsProperties(), BucketTestSupport.disabledProps(),
                        BucketTestSupport.disabledAccounts(), bucketParams),
                positionRepository);
        return new TimeCutScheduler(
                positionRepository, orderHistoryRepository, positionManager,
                new RiskEngine(List.of()), orderEngine,
                statusManager, kisProperties, marketCalendarService, bucketParams,
                brokerageClient, notifier);
    }

    private static Position holding(String stockCode, int quantity, double price) {
        Position pos = Position.empty(stockCode);
        pos.applyBuy(quantity, price);
        return pos;
    }

    private static Position holdingIn(String stockCode, int quantity, StrategyBucket bucket) {
        Position pos = Position.empty(stockCode);
        pos.assignBucketIfAbsent(bucket);
        pos.applyBuy(quantity, 72_500.0);
        return pos;
    }

    /** findAll(스윕 순회) + findByStockCode(OrderEngine 전량 매도) 스텁을 함께 구성 */
    private void givenHoldings(Position... positions) {
        when(positionRepository.findAll()).thenReturn(List.of(positions));
        for (Position p : positions) {
            when(positionRepository.findByStockCode(p.getStockCode())).thenReturn(Optional.of(p));
        }
    }

    private void givenBrokerHolds(String stockCode, int quantity) {
        when(brokerageClient.getActualHoldingQuantity(stockCode)).thenReturn(quantity);
    }

    // ── 핵심 회귀 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("2026-09-01 066570 사고 재현 — 15:15 매도가 타임아웃으로 실패해도 스윕이 다시 매도한다")
    void resells_leftover_after_timecut_failure() {
        givenHoldings(holding("066570", 5, 187_175.0));
        givenBrokerHolds("066570", 5);
        doThrow(new IllegalStateException("Read timed out"))
                .doNothing()
                .when(orderClient).sell("066570", 5);

        TimeCutScheduler sut = scheduler();
        sut.executeTimeCut();            // 15:15 — 타임아웃으로 실패
        sut.executeRetrySweep(false);    // 15:20 — 스윕이 이어받는다

        verify(orderClient, times(2)).sell("066570", 5);
        verify(brokerageClient, never()).sendMarketOrder(anyString(), anyString(), anyInt());
    }

    // ── 중복 매도 방지 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("브로커 보유가 0이면 재매도하지 않는다 — 타임아웃이 곧 미접수는 아니다")
    void skips_resell_when_broker_holds_nothing() {
        givenHoldings(holding("066570", 5, 187_175.0));
        givenBrokerHolds("066570", 0);

        scheduler().executeRetrySweep(false);

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("브로커 잔고 조회가 실패하면 재매도한다 — 불확실하면 안전한 방향")
    void resells_when_balance_lookup_fails() {
        givenHoldings(holding("066570", 5, 187_175.0));
        when(brokerageClient.getActualHoldingQuantity("066570"))
                .thenThrow(new IllegalStateException("잔고 조회 실패"));

        scheduler().executeRetrySweep(false);

        verify(orderClient).sell("066570", 5);
    }

    @Test
    @DisplayName("미체결 SELL이 이미 있으면 재매도하지 않는다 (잔고 조회도 하지 않는다)")
    void skips_when_pending_sell_exists() {
        givenHoldings(holding("066570", 5, 187_175.0));
        when(orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                "066570", OrderSide.SELL, OrderStatus.ACCEPTED)).thenReturn(true);

        scheduler().executeRetrySweep(false);

        verify(orderClient, never()).sell(anyString(), anyInt());
        verify(brokerageClient, never()).getActualHoldingQuantity(anyString());
    }

    // ── 가드 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("다일 보유 칸은 스윕 대상이 아니다 — 타임컷과 정확히 같은 집합")
    void multi_day_bucket_is_not_swept() {
        BucketParameters params = new BucketParameters();
        BucketParameters.Overrides o = new BucketParameters.Overrides();
        o.setMultiDayHold(true);
        params.getOverrides().put(StrategyBucket.VB, o);
        bucketParams = new BucketParameterResolver(params, new RiskLimitsProperties(), new FilterProperties());

        givenHoldings(holdingIn("005930", 3, StrategyBucket.VB));
        givenBrokerHolds("005930", 3);

        scheduler().executeRetrySweep(false);

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("KRX 휴장일이면 스윕이 아무것도 하지 않는다")
    void does_nothing_on_holiday() {
        marketCalendarService = marketCalendarAt(LocalDate.of(2026, 9, 5)); // 토요일
        givenHoldings(holding("066570", 5, 187_175.0));
        givenBrokerHolds("066570", 5);

        scheduler().executeRetrySweep(false);

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("청산 모드(FORCE_LIQUIDATING)면 스윕이 양보한다 — 포지션 소유권은 청산 상태머신")
    void yields_to_liquidation_mode() {
        statusManager.changeMode(TradingMode.FORCE_LIQUIDATING);
        givenHoldings(holding("066570", 5, 187_175.0));
        givenBrokerHolds("066570", 5);

        scheduler().executeRetrySweep(false);

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    // ── 최종 스윕 알림 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("최종 스윕에서도 남으면 사람을 부른다 — 알림 1건")
    void final_sweep_alerts_when_still_held() {
        givenHoldings(holding("066570", 5, 187_175.0));
        givenBrokerHolds("066570", 5);
        doThrow(new IllegalStateException("Read timed out")).when(orderClient).sell("066570", 5);

        scheduler().executeRetrySweep(true);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifier).sendCritical(message.capture());
        assertThat(message.getValue()).contains("066570").contains("5");
    }

    @Test
    @DisplayName("최종 스윕에서 다 정리됐으면 알림 없음")
    void final_sweep_silent_when_cleared() {
        Position pos = holding("066570", 5, 187_175.0);
        when(positionRepository.findByStockCode("066570")).thenReturn(Optional.of(pos));
        when(positionRepository.findAll())
                .thenReturn(List.of(pos))   // 스윕 대상 조회
                .thenReturn(List.of());     // 재매도 뒤 다시 읽으면 비어 있다
        givenBrokerHolds("066570", 5);

        scheduler().executeRetrySweep(true);

        verify(orderClient).sell("066570", 5);
        verify(notifier, never()).sendCritical(anyString());
    }
}
