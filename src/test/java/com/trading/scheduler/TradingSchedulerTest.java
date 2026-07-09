package com.trading.scheduler;

import com.trading.market.AtrCalculator;
import com.trading.market.Candle;
import com.trading.market.KisProperties;
import com.trading.market.MarketDataService;
import com.trading.order.KisOrderClient;
import com.trading.order.OrderEngine;
import com.trading.order.OrderSizingService;
import com.trading.position.Account;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.risk.RiskEngine;
import com.trading.risk.TradingStatusManager;
import com.trading.signal.Signal;
import com.trading.signal.SignalDispatcher;
import com.trading.strategy.Strategy;
import com.trading.universe.TradingUniverseItem;
import com.trading.universe.TradingUniverseRepository;
import com.trading.universe.TradingUniverseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 매매 유니버스 라운드로빈 순회 검증 —
 * KIS 모의계좌 레이트리밋(초당 2건) 때문에 틱당 1종목만 평가한다.
 */
@DisplayName("TradingScheduler — 유니버스 라운드로빈")
class TradingSchedulerTest {

    private MarketDataService marketDataService;
    private PositionManager positionManager;
    private TradingUniverseRepository universeRepository;
    private KisProperties kisProperties;
    private TradingScheduler sut;

    /** 신호를 내지 않는 스텁 전략 — 순회 여부만 검증한다 */
    private static final Strategy NO_SIGNAL_STRATEGY = new Strategy() {
        @Override public String getName() { return "stub"; }
        @Override public List<Signal> evaluate(String stockCode, List<Candle> candles) {
            return List.of();
        }
    };

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        positionManager = mock(PositionManager.class);
        universeRepository = mock(TradingUniverseRepository.class);
        KisOrderClient orderClient = mock(KisOrderClient.class);
        PositionRepository positionRepository = mock(PositionRepository.class);
        TradingStatusManager statusManager = new TradingStatusManager();

        kisProperties = new KisProperties();
        kisProperties.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        kisProperties.setAppkey("k");
        kisProperties.setSecretkey("s");
        kisProperties.setAccountNo("50000000-01");

        when(positionManager.snapshotAccount())
                .thenReturn(new Account(10_000_000, 0.0, 0, List.of()));
        when(marketDataService.getRecentCandles(anyString())).thenReturn(List.of(
                new Candle(LocalDate.now().minusDays(1), 100, 110, 90, 100, 1000),
                new Candle(LocalDate.now(), 100, 105, 95, 100, 1000)));

        OrderEngine orderEngine = new OrderEngine(orderClient, statusManager,
                new OrderSizingService(marketDataService, positionManager, new AtrCalculator()),
                positionRepository);
        sut = new TradingScheduler(marketDataService,
                new SignalDispatcher(List.of(NO_SIGNAL_STRATEGY)),
                new RiskEngine(List.of()), orderEngine, positionManager,
                statusManager, kisProperties,
                new TradingUniverseService(universeRepository));
    }

    private void givenUniverse(String... codes) {
        List<TradingUniverseItem> items = java.util.Arrays.stream(codes)
                .map(c -> TradingUniverseItem.of(c, c)).toList();
        when(universeRepository.findAll()).thenReturn(items);
    }

    @Test
    @DisplayName("2종목 유니버스 → 틱마다 한 종목씩 번갈아 평가")
    void round_robins_one_stock_per_tick() {
        givenUniverse("005930", "000660");

        sut.run(); // tick 1 → 005930
        sut.run(); // tick 2 → 000660
        sut.run(); // tick 3 → 005930

        verify(marketDataService, org.mockito.Mockito.times(2)).getRecentCandles("005930");
        verify(marketDataService, org.mockito.Mockito.times(1)).getRecentCandles("000660");
    }

    @Test
    @DisplayName("유니버스 비어 있음 → 시세 조회 없음")
    void empty_universe_no_market_call() {
        givenUniverse();

        sut.run();

        verify(marketDataService, never()).getRecentCandles(anyString());
    }

    @Test
    @DisplayName("KIS 미설정 → 루프 진입 안 함")
    void not_configured_skips_loop() {
        givenUniverse("005930");
        kisProperties.setAppkey("");

        sut.run();

        verify(marketDataService, never()).getRecentCandles(anyString());
    }
}
