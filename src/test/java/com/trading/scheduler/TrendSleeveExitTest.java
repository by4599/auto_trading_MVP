package com.trading.scheduler;

import com.trading.NotificationService;
import com.trading.PaperProfileYaml;
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
import com.trading.order.OrderSizingService;
import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.risk.BrokerageApiClient;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A동(TREND) 출구 — <b>배포될 paper 설정 그대로</b>: 15:15 타임컷에서 빠져 이월되고,
 * 20거래일째(15:17) MaxHoldScheduler가 청산한다(BACKTEST-DESIGN §14 P3 "최대 20거래일").
 */
@DisplayName("A동 출구 — 타임컷 제외 · 20거래일 청산 (paper 설정 바인딩)")
class TrendSleeveExitTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-10-02(금) — 평일 */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);

    private PositionRepository positionRepository;
    private PositionManager positionManager;
    private KisOrderClient orderClient;
    private TradingStatusManager status;
    private KisProperties kis;
    private BucketParameterResolver resolver;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        positionManager = mock(PositionManager.class);
        orderClient = mock(KisOrderClient.class);
        status = new TradingStatusManager();
        when(positionManager.snapshotAccount()).thenReturn(new Account(10_000_000, 0.0, 0, List.of()));
        kis = new KisProperties();
        kis.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        kis.setAppkey("k");
        kis.setSecretkey("s");
        kis.setAccountNo("50000000-01");
        resolver = new BucketParameterResolver(
                PaperProfileYaml.bind("trading.bucket-params", BucketParameters.class),
                new RiskLimitsProperties(), PaperProfileYaml.bind("trading.filters", FilterProperties.class));
    }

    private MarketCalendarService calendarAt(LocalTime time, Clock[] out) {
        Clock clock = Clock.fixed(LocalDateTime.of(TODAY, time).atZone(KST).toInstant(), KST);
        out[0] = clock;
        return new MarketCalendarService(new MarketCalendarProperties(), clock);
    }

    private OrderEngine orderEngine() {
        return new OrderEngine(orderClient, status,
                new OrderSizingService(mock(MarketDataService.class), positionManager, new AtrCalculator(),
                        new RiskLimitsProperties(), BucketTestSupport.disabledProps(),
                        BucketTestSupport.disabledAccounts(), resolver),
                positionRepository);
    }

    private Position held(String code, StrategyBucket bucket, LocalDate entry) {
        Position p = Position.empty(code);
        p.assignBucketIfAbsent(bucket);
        p.applyBuy(10, 70_000);
        p.stampEntryDateIfAbsent(entry);
        return p;
    }

    private void givenPositions(Position... ps) {
        List<Position> list = List.of(ps);
        when(positionRepository.findAll()).thenReturn(list);
        when(positionRepository.findByStockCode(anyString())).thenAnswer(inv ->
                list.stream().filter(p -> p.getStockCode().equals(inv.getArgument(0))).findFirst());
    }

    @Test
    @DisplayName("15:15 타임컷 — B동 잔여분(VB)만 팔고 A동(TREND)은 이월한다")
    void time_cut_skips_trend_positions() {
        givenPositions(held("005930", StrategyBucket.TREND, TODAY), held("000660", StrategyBucket.VB, TODAY));
        Clock[] clock = new Clock[1];
        TimeCutScheduler sut = new TimeCutScheduler(positionRepository, mock(OrderHistoryRepository.class),
                positionManager, new RiskEngine(List.of()), orderEngine(), status, kis,
                calendarAt(LocalTime.of(15, 15), clock), resolver,
                mock(BrokerageApiClient.class), mock(NotificationService.class));

        sut.executeTimeCut();

        verify(orderClient).sell("000660", 10);
        verify(orderClient, never()).sell("005930", 10);
    }

    @Test
    @DisplayName("최대보유 — A동 20거래일째 청산, 19거래일째는 보유 유지")
    void max_hold_sells_trend_at_20_trading_days() {
        // 09-04(금) 진입 → 10-02(금)까지 평일 20일 / 09-07(월) 진입 → 19일 (테스트 캘린더는 주말만 휴장)
        givenPositions(held("005930", StrategyBucket.TREND, LocalDate.of(2026, 9, 4)),
                held("035420", StrategyBucket.TREND, LocalDate.of(2026, 9, 7)));
        Clock[] clock = new Clock[1];
        MarketCalendarService cal = calendarAt(LocalTime.of(15, 17), clock);
        MaxHoldScheduler sut = new MaxHoldScheduler(positionRepository, mock(OrderHistoryRepository.class),
                positionManager, new RiskEngine(List.of()), orderEngine(), status, kis, cal, resolver, clock[0]);

        sut.enforceMaxHold();

        verify(orderClient).sell("005930", 10);
        verify(orderClient, never()).sell("035420", 10);
    }
}
