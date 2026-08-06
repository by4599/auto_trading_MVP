package com.trading.scheduler;

import com.trading.risk.RiskLimitsProperties;
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
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.position.Account;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskResult;
import com.trading.risk.RiskRule;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 15:15 타임컷 (F-4) 단위 테스트.
 *
 * 이 JVM(Java 25)의 Mockito는 구체 클래스를 목킹하지 못하므로
 * RiskEngine/OrderEngine/TradingStatusManager/KisProperties는 실객체로 구성하고
 * 인터페이스(KisOrderClient, PositionRepository 등)만 목킹한다.
 * → 타임컷이 Signal → RiskEngine → OrderEngine 경로를 실제로 통과하는지까지 검증된다.
 */
@DisplayName("TimeCutScheduler — 15:15 보유분 전량 정리")
class TimeCutSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private PositionRepository positionRepository;
    private OrderHistoryRepository orderHistoryRepository;
    private PositionManager positionManager;
    private KisOrderClient orderClient;
    private TradingStatusManager statusManager;
    private KisProperties kisProperties;
    private MarketCalendarService marketCalendarService;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        orderHistoryRepository = mock(OrderHistoryRepository.class);
        positionManager = mock(PositionManager.class);
        orderClient = mock(KisOrderClient.class);
        statusManager = new TradingStatusManager();
        marketCalendarService = marketCalendarAt(LocalDate.of(2026, 7, 15)); // 수요일, 평일

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

    /** 다일 보유로 지정된 칸을 가진 스케줄러 — 타임컷 제외 검증용 */
    private TimeCutScheduler schedulerWithMultiDay(com.trading.bucket.StrategyBucket bucket) {
        com.trading.bucket.BucketParameters params = new com.trading.bucket.BucketParameters();
        com.trading.bucket.BucketParameters.Overrides o = new com.trading.bucket.BucketParameters.Overrides();
        o.setMultiDayHold(true);
        params.getOverrides().put(bucket, o);
        com.trading.bucket.BucketParameterResolver resolver = new com.trading.bucket.BucketParameterResolver(
                params, new RiskLimitsProperties(), new com.trading.strategy.FilterProperties());
        OrderEngine orderEngine = new OrderEngine(orderClient, statusManager,
                new OrderSizingService(mock(MarketDataService.class), positionManager, new AtrCalculator(),
                        new RiskLimitsProperties(),
                        com.trading.bucket.BucketTestSupport.disabledProps(),
                        com.trading.bucket.BucketTestSupport.disabledAccounts(), resolver),
                positionRepository);
        return new TimeCutScheduler(
                positionRepository, orderHistoryRepository, positionManager,
                new RiskEngine(List.of()), orderEngine,
                statusManager, kisProperties, marketCalendarService, resolver);
    }

    private static Position holdingIn(String stockCode, com.trading.bucket.StrategyBucket bucket) {
        Position pos = Position.empty(stockCode);
        pos.assignBucketIfAbsent(bucket);
        pos.applyBuy(1, 72500.0);
        return pos;
    }

    @Test
    @DisplayName("다일 보유 칸(A동)은 15:15 타임컷에서 제외된다 — 며칠 들고 가는 것이 그 전략의 본체")
    void multi_day_bucket_is_excluded_from_timecut() {
        givenHoldings(holdingIn("005930", com.trading.bucket.StrategyBucket.VB));

        schedulerWithMultiDay(com.trading.bucket.StrategyBucket.VB).executeTimeCut();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("다일 보유로 지정되지 않은 칸은 종전대로 정리된다")
    void other_buckets_still_time_cut() {
        givenHoldings(holdingIn("000660", com.trading.bucket.StrategyBucket.MIX));

        schedulerWithMultiDay(com.trading.bucket.StrategyBucket.VB).executeTimeCut();

        verify(orderClient).sell("000660", 1);
    }

    private TimeCutScheduler scheduler(List<RiskRule> rules) {
        OrderEngine orderEngine = new OrderEngine(orderClient, statusManager,
                new OrderSizingService(mock(MarketDataService.class), positionManager, new AtrCalculator(), new RiskLimitsProperties(),
                        com.trading.bucket.BucketTestSupport.disabledProps(),
                        com.trading.bucket.BucketTestSupport.disabledAccounts(), com.trading.bucket.BucketTestSupport.defaultParams()),
                positionRepository);
        return new TimeCutScheduler(
                positionRepository, orderHistoryRepository, positionManager,
                new RiskEngine(rules), orderEngine,
                statusManager, kisProperties, marketCalendarService, com.trading.bucket.BucketTestSupport.defaultParams());
    }

    private static Position holding(String stockCode, int quantity, double price) {
        Position pos = Position.empty(stockCode);
        pos.applyBuy(quantity, price);
        return pos;
    }

    /** findAll(스케줄러 순회) + findByStockCode(OrderEngine 전량 매도) 스텁을 함께 구성 */
    private void givenHoldings(Position... positions) {
        when(positionRepository.findAll()).thenReturn(List.of(positions));
        for (Position p : positions) {
            when(positionRepository.findByStockCode(p.getStockCode())).thenReturn(Optional.of(p));
        }
    }

    // ── 기본 흐름 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("보유 포지션 존재 → SELL 신호가 RiskEngine→OrderEngine 경로로 매도 실행")
    void sells_held_position_through_order_engine() {
        givenHoldings(holding("005930", 1, 72500.0));

        scheduler(List.of()).executeTimeCut();

        verify(orderClient).sell("005930", 1);
    }

    @Test
    @DisplayName("보유 포지션 없음 → 매도 없음")
    void no_position_no_order() {
        when(positionRepository.findAll()).thenReturn(List.of());

        scheduler(List.of()).executeTimeCut();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("수량 0 포지션(정리된 행) → 매도 없음")
    void zero_quantity_position_skipped() {
        givenHoldings(Position.empty("005930"));

        scheduler(List.of()).executeTimeCut();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    // ── 가드 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FORCE_LIQUIDATING 모드 → 타임컷 양보 (청산 경로가 포지션 소유)")
    void yields_to_liquidation_mode() {
        statusManager.changeMode(TradingMode.FORCE_LIQUIDATING);
        givenHoldings(holding("005930", 1, 72500.0));

        scheduler(List.of()).executeTimeCut();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("EMERGENCY_STOPPED 모드 → 매도 없음")
    void skips_when_emergency_stopped() {
        statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);
        givenHoldings(holding("005930", 1, 72500.0));

        scheduler(List.of()).executeTimeCut();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("SAFE_MODE 모드 → 타임컷은 방어 로직이므로 계속 동작")
    void still_runs_in_safe_mode() {
        statusManager.changeMode(TradingMode.SAFE_MODE);
        givenHoldings(holding("005930", 1, 72500.0));

        scheduler(List.of()).executeTimeCut();

        verify(orderClient).sell("005930", 1);
    }

    @Test
    @DisplayName("KRX 휴장일 → 매도 없음 (cron은 MON-FRI까지만 알므로 방어 가드)")
    void skips_on_holiday() {
        marketCalendarService = marketCalendarAt(LocalDate.of(2026, 7, 18)); // 토요일
        givenHoldings(holding("005930", 1, 72500.0));

        scheduler(List.of()).executeTimeCut();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("KIS 자격증명 미설정 → 매도 없음")
    void skips_when_not_configured() {
        kisProperties.setAppkey("");
        givenHoldings(holding("005930", 1, 72500.0));

        scheduler(List.of()).executeTimeCut();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("미체결 SELL 주문 존재 → 중복 매도 방지")
    void skips_when_pending_sell_exists() {
        givenHoldings(holding("005930", 1, 72500.0));
        when(orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                "005930", OrderSide.SELL, OrderStatus.ACCEPTED)).thenReturn(true);

        scheduler(List.of()).executeTimeCut();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("RiskRule 거부 → 매도 없음 (RiskEngine을 건너뛰지 않는다)")
    void respects_risk_engine_rejection() {
        givenHoldings(holding("005930", 1, 72500.0));
        RiskRule rejectAll = (signal, account) -> RiskResult.reject("테스트 거부");

        scheduler(List.of(rejectAll)).executeTimeCut();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    // ── 종목별 예외 격리 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("한 종목 매도 실패해도 나머지 종목은 계속 진행")
    void one_failure_does_not_stop_the_rest() {
        givenHoldings(holding("005930", 1, 72500.0), holding("000660", 1, 190000.0));
        doThrow(new IllegalStateException("KIS 오류")).when(orderClient).sell("005930", 1);

        scheduler(List.of()).executeTimeCut();

        verify(orderClient).sell("000660", 1);
    }
}
