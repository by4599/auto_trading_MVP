package com.trading.risk;

import com.trading.NotificationService;
import com.trading.bucket.BucketProperties;
import com.trading.bucket.BucketTestSupport;
import com.trading.bucket.InMemoryPortfolioState;
import com.trading.bucket.SleeveDrawdownProperties;
import com.trading.bucket.SleeveEquityCalculator;
import com.trading.bucket.SleeveRealized;
import com.trading.bucket.SleeveRealizedLedger;
import com.trading.bucket.SleeveRealizedSource;
import com.trading.bucket.SleeveStateStore;
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
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 칸 낙폭 감시기 테스트 공용 조립 — 인터페이스(리포지토리·주문 클라이언트·잔고·알림)만 목, 나머지는 실객체
 * (Java 25 Mockito 제약). 그래서 정리 매도가 실제로 RiskEngine(잠금 룰 포함)과 OrderEngine을 지나 주문 클라이언트까지 간다.
 * 상태는 맵 기반 portfolio_state라 같은 픽스처로 감시기를 다시 만들면 재시작이 된다.
 */
final class SleeveMonitorFixture {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    final Map<String, Double> state = new HashMap<>();
    final List<Position> held = new ArrayList<>();
    final PositionRepository positionRepository = mock(PositionRepository.class);
    final OrderHistoryRepository orderHistoryRepository = mock(OrderHistoryRepository.class);
    final PositionManager positionManager = mock(PositionManager.class);
    final KisOrderClient orderClient = mock(KisOrderClient.class);
    final NotificationService notifier = mock(NotificationService.class);
    final TradingStatusManager statusManager = new TradingStatusManager();

    boolean bucketsEnabled = true;
    boolean eventEnabled = false;
    SleeveRealizedSource source = (bucket, from, to) -> SleeveRealized.none();

    SleeveDrawdownMonitor monitorAt(LocalDateTime now) {
        when(positionRepository.findAll()).thenAnswer(i -> List.copyOf(held));
        Clock clock = Clock.fixed(now.atZone(KST).toInstant(), KST);
        BucketProperties buckets = new BucketProperties(bucketsEnabled, "2026-07-20",
                10_000_000, 10_000_000, 10_000_000, 4_000_000, eventEnabled, false, true);
        SleeveStateStore store = store();
        SleeveRealizedLedger ledger = new SleeveRealizedLedger(source, store, buckets, clock);
        SleeveDrawdownGuard guard = new SleeveDrawdownGuard(
                store, new SleeveDrawdownProperties(0.12, 0.20), notifier, clock);
        OrderEngine orderEngine = new OrderEngine(orderClient, statusManager,
                new OrderSizingService(mock(MarketDataService.class), positionManager, new AtrCalculator(),
                        new RiskLimitsProperties(), BucketTestSupport.disabledProps(),
                        BucketTestSupport.disabledAccounts(), BucketTestSupport.defaultParams()),
                positionRepository);
        RiskEngine riskEngine = new RiskEngine(List.of(new SleeveLockRule(buckets, store)));
        SleeveLiquidator liquidator = new SleeveLiquidator(
                positionRepository, orderHistoryRepository, riskEngine, orderEngine);
        return new SleeveDrawdownMonitor(buckets, configuredKis(),
                new MarketCalendarService(new MarketCalendarProperties(), clock), statusManager,
                positionManager, ledger, new SleeveEquityCalculator(buckets, positionRepository, ledger, clock),
                guard, liquidator);
    }

    SleeveStateStore store() {
        return new SleeveStateStore(InMemoryPortfolioState.create(state));
    }

    void hold(String code, StrategyBucket bucket, int qty, double avg) {
        Position p = Position.empty(code);
        p.assignBucketIfAbsent(bucket);
        p.applyBuy(qty, avg);
        held.add(p);
        when(positionRepository.findByStockCode(code)).thenReturn(Optional.of(p));
    }

    Optional<Double> storedPeak(StrategyBucket bucket) {
        return Optional.ofNullable(state.get(SleeveStateStore.key("PEAK", bucket)));
    }

    boolean locked(StrategyBucket bucket) {
        return store().isLocked(bucket);
    }

    private static KisProperties configuredKis() {
        KisProperties kis = new KisProperties();
        kis.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        kis.setAppkey("test-appkey");
        kis.setSecretkey("test-secretkey");
        kis.setAccountNo("50000000-01");
        return kis;
    }
}
