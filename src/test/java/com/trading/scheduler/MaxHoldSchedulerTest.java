package com.trading.scheduler;

import com.trading.bucket.BucketParameterResolver;
import com.trading.bucket.BucketParameters;
import com.trading.bucket.StrategyBucket;
import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.order.KisOrderClient;
import com.trading.order.OrderEngine;
import com.trading.order.OrderHistoryRepository;
import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskLimitsProperties;
import com.trading.risk.TradingStatusManager;
import com.trading.strategy.FilterProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
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
 * 다일 보유 칸의 최대 보유일 청산 — 백테스트에만 있던 개념(최대 20거래일)을 모의투자로 옮겼다.
 * 가장 중요한 불변식: <b>설정을 넣기 전에는 아무것도 팔지 않는다</b>(기본 제한 없음).
 */
@DisplayName("MaxHoldScheduler — 다일 보유 칸 최대 보유일 청산")
class MaxHoldSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-07-15(수) — 평일 */
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);

    private PositionRepository positionRepository;
    private OrderHistoryRepository orderHistoryRepository;
    private KisOrderClient orderClient;
    private BucketParameters params;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        orderHistoryRepository = mock(OrderHistoryRepository.class);
        orderClient = mock(KisOrderClient.class);
        params = new BucketParameters();
    }

    private MaxHoldScheduler sut() {
        Clock clock = Clock.fixed(LocalDateTime.of(TODAY, LocalTime.of(15, 17)).atZone(KST).toInstant(), KST);
        MarketCalendarService cal = new MarketCalendarService(new MarketCalendarProperties(), clock);
        BucketParameterResolver resolver = new BucketParameterResolver(
                params, new RiskLimitsProperties(), new FilterProperties());

        PositionManager positionManager = mock(PositionManager.class);
        when(positionManager.snapshotAccount()).thenReturn(new Account(10_000_000, 0.0, 0, List.of()));

        KisProperties kis = new KisProperties();
        kis.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        kis.setAppkey("k"); kis.setSecretkey("s"); kis.setAccountNo("50000000-01");

        OrderEngine orderEngine = new OrderEngine(orderClient, new TradingStatusManager(),
                new com.trading.order.OrderSizingService(
                        mock(com.trading.market.MarketDataService.class), positionManager,
                        new com.trading.market.AtrCalculator(), new RiskLimitsProperties(),
                        com.trading.bucket.BucketTestSupport.disabledProps(),
                        com.trading.bucket.BucketTestSupport.disabledAccounts(), resolver),
                positionRepository);

        return new MaxHoldScheduler(positionRepository, orderHistoryRepository, positionManager,
                new RiskEngine(List.of()), orderEngine, new TradingStatusManager(), kis,
                cal, resolver, clock);
    }

    private Position held(String code, StrategyBucket bucket, LocalDate entry) {
        Position p = Position.empty(code);
        p.assignBucketIfAbsent(bucket);
        p.applyBuy(10, 70_000);
        if (entry != null) p.stampEntryDateIfAbsent(entry);
        return p;
    }

    private void givenPositions(Position... ps) {
        List<Position> list = List.of(ps);
        when(positionRepository.findAll()).thenReturn(list);
        // OrderEngine.executeSell이 보유 수량을 여기서 읽는다 — 스텁 없으면 0주로 보고 스킵한다
        when(positionRepository.findByStockCode(anyString())).thenAnswer(inv ->
                list.stream().filter(p -> p.getStockCode().equals(inv.getArgument(0))).findFirst());
    }

    private void withMaxHold(StrategyBucket bucket, int days) {
        BucketParameters.Overrides o = new BucketParameters.Overrides();
        o.setMaxHoldDays(days);
        params.getOverrides().put(bucket, o);
    }

    @Test
    @DisplayName("설정이 없으면 아무것도 팔지 않는다 — 도입 전과 동작 동일")
    void does_nothing_without_configuration() {
        givenPositions(held("005930", StrategyBucket.VB, TODAY.minusDays(60)));

        sut().enforceMaxHold();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("보유 거래일이 한도에 도달하면 매도한다")
    void sells_when_hold_days_reach_limit() {
        withMaxHold(StrategyBucket.VB, 5);
        // 07-08(수) 진입 → 07-15(수)까지 거래일 5일(09·10·13·14·15)
        givenPositions(held("005930", StrategyBucket.VB, LocalDate.of(2026, 7, 8)));

        sut().enforceMaxHold();

        verify(orderClient).sell("005930", 10);
    }

    @Test
    @DisplayName("한도 미만이면 그대로 둔다")
    void keeps_position_below_limit() {
        withMaxHold(StrategyBucket.VB, 20);
        givenPositions(held("005930", StrategyBucket.VB, LocalDate.of(2026, 7, 13)));

        sut().enforceMaxHold();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("진입일을 모르면 팔지 않고 오늘로 도장을 찍는다 — 모르는 과거로 앞당겨 팔지 않는다")
    void stamps_entry_date_instead_of_selling_when_unknown() {
        withMaxHold(StrategyBucket.VB, 1);
        Position p = held("005930", StrategyBucket.VB, null);  // 진입일 미상
        givenPositions(p);

        sut().enforceMaxHold();

        verify(orderClient, never()).sell(anyString(), anyInt());
        assertThat(p.getEntryDate()).isEqualTo(TODAY);
        verify(positionRepository).save(p);
    }

    @Test
    @DisplayName("최대 보유일이 설정되지 않은 칸은 건드리지 않는다")
    void ignores_buckets_without_limit() {
        withMaxHold(StrategyBucket.VB, 1);
        givenPositions(held("000660", StrategyBucket.MIX, LocalDate.of(2026, 1, 1)));

        sut().enforceMaxHold();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("보유일은 거래일로 센다 — 주말은 세지 않는다")
    void counts_trading_days_only() {
        MaxHoldScheduler s = sut();

        // 07-10(금) → 07-13(월): 달력 3일이지만 거래일은 1일
        assertThat(s.tradingDaysBetween(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 13)))
                .isEqualTo(1);
        assertThat(s.tradingDaysBetween(LocalDate.of(2026, 7, 8), TODAY)).isEqualTo(5);
    }

    @Test
    @DisplayName("미체결 매도가 있으면 중복 매도하지 않는다")
    void skips_when_pending_sell_exists() {
        withMaxHold(StrategyBucket.VB, 1);
        givenPositions(held("005930", StrategyBucket.VB, LocalDate.of(2026, 7, 1)));
        when(orderHistoryRepository.existsByStockCodeAndSideAndStatus(any(), any(), any()))
                .thenReturn(true);

        sut().enforceMaxHold();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }
}
