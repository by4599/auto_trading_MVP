package com.trading.backtest;

import com.trading.risk.RiskLimitsProperties;
import com.trading.market.AtrCalculator;
import com.trading.market.Candle;
import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.Timeframe;
import com.trading.order.OrderEngine;
import com.trading.order.OrderHistory;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSizingService;
import com.trading.position.DailyEquity;
import com.trading.position.DailyEquityRepository;
import com.trading.position.PortfolioState;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResultTracker;
import com.trading.risk.RiskEngine;
import com.trading.risk.TradingStatusManager;
import com.trading.risk.TrailingStopTracker;
import com.trading.signal.SignalDispatcher;
import com.trading.strategy.FilterProperties;
import com.trading.strategy.StrategyParameters;
import com.trading.strategy.VolatilityBreakoutStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 일봉 근사 하루 시퀀스 검증 — Spring 없이 실객체 배선 (Java 25 Mockito 제약:
 * 구체 클래스는 실객체, 인터페이스만 목).
 *
 * 시나리오 데이터: 워밍업 16일 (o=100 h=110 l=90 c=100, TR=20 → ATR=20, 손절폭=30)
 * 전일 range=20, K=0.5 → 돌파가 = 당일시가 + 10.
 */
@DisplayName("DailyBarSimulator — 이분탐색 진입가·비관적 손절 시퀀스")
class DailyBarSimulatorTest {

    private static final String CODE = "005930";
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15); // 월요일

    private final Map<String, Position> positionStore = new HashMap<>();
    private final Map<LocalDate, DailyEquity> equityStore = new HashMap<>();
    private final Map<String, PortfolioState> stateStore = new HashMap<>();

    private BacktestMarketDataService market;
    private BacktestPositionManager positionManager;
    private BacktestOrderClient orderClient;
    private DailyBarSimulator sut;
    private TradeRecorder tradeRecorder;
    private MutableClock clock;

    /** 시나리오별 당일 봉을 갈아끼우는 가변 시리즈 (candle repo 목이 이 리스트를 반환) */
    private final List<CandleHistory> seriesRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.now());
        tradeRecorder = new TradeRecorder();

        PositionRepository positionRepository = inMemoryPositionRepository();
        DailyEquityRepository dailyEquityRepository = inMemoryEquityRepository();
        TradeResultTracker tracker = new TradeResultTracker(inMemoryStateRepository());
        OrderHistoryRepository orderHistoryRepository = mock(OrderHistoryRepository.class);
        when(orderHistoryRepository.save(any(OrderHistory.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        seriesRows.addAll(defaultSeries());
        market = new BacktestMarketDataService(candleRepositoryWith(seriesRows));
        market.loadSeries(List.of(CODE), TODAY.minusDays(30), TODAY);

        BacktestDataProperties properties = new BacktestDataProperties();
        positionManager = new BacktestPositionManager(positionRepository,
                dailyEquityRepository, tracker, market, properties, clock);
        orderClient = new BacktestOrderClient(orderHistoryRepository, positionRepository,
                tracker, mock(ApplicationEventPublisher.class), market, positionManager,
                tradeRecorder, clock);

        OrderEngine orderEngine = new OrderEngine(orderClient, new TradingStatusManager(),
                new OrderSizingService(market, positionManager, new AtrCalculator(), new RiskLimitsProperties()),
                positionRepository);
        FilterProperties filters = new FilterProperties();
        SignalDispatcher dispatcher = new SignalDispatcher(
                List.of(new VolatilityBreakoutStrategy(new StrategyParameters(), filters)));

        sut = new DailyBarSimulator(market, dispatcher, new RiskEngine(List.of()),
                orderEngine, positionRepository, positionManager, orderClient,
                new TrailingStopTracker(filters), clock);

        market.setSimDate(TODAY);
    }

    // ── 진입 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("장중 돌파: 진입가 = 이분탐색으로 복원한 돌파가 + 슬리피지 (고가 아님)")
    void entry_atBreakoutCrossing_notAtHigh() {
        // 시가 100 + range 20 × 0.5 = 돌파가 110, 고가 115
        setTodayBar(new Candle(TODAY, 100, 115, 95, 112, 2000));

        sut.simulateDay(CODE, TODAY);

        Position pos = positionStore.get(CODE);
        assertThat(pos).isNotNull();
        assertThat(pos.getQuantity()).isGreaterThan(0);
        // 체결가 ≈ 110 × (1+0.001) = 110.11 — 고가 115 기준이면 115.115라 확연히 구분된다
        assertThat(pos.getAveragePrice()).isCloseTo(110 * 1.001, within(0.05));
    }

    @Test
    @DisplayName("고가가 돌파가에 못 미치면 진입하지 않는다")
    void noEntry_whenHighBelowBreakout() {
        setTodayBar(new Candle(TODAY, 100, 109, 95, 105, 2000)); // 돌파가 110 > 고가 109

        sut.simulateDay(CODE, TODAY);

        assertThat(positionStore).doesNotContainKey(CODE);
    }

    @Test
    @DisplayName("R 사이징: 수량 = floor(1R / 손절폭) — ATR 20 × 1.5 = 30원, 1R = 10만원 → 3333주")
    void entry_quantityFollowsRSizing() {
        setTodayBar(new Candle(TODAY, 100, 115, 95, 112, 2000));

        sut.simulateDay(CODE, TODAY);

        assertThat(positionStore.get(CODE).getQuantity()).isEqualTo(3333);
    }

    // ── 이월 보유분 손절 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("갭다운: 시가 ≤ 손절가면 시가 − 슬리피지로 청산된다")
    void carriedStop_gapDown_exitsAtOpen() {
        givenHeldPosition(3, 120, 100); // 손절가 100
        setTodayBar(new Candle(TODAY, 95, 105, 93, 104, 2000)); // 시가 95 ≤ 100

        double cashBefore = positionManager.getCash();
        sut.simulateDay(CODE, TODAY);

        assertThat(positionStore).doesNotContainKey(CODE);
        double expectedIn = BacktestCosts.sellCashIn(BacktestCosts.sellFillPrice(95), 3);
        assertThat(positionManager.getCash() - cashBefore).isCloseTo(expectedIn, within(1e-6));
    }

    @Test
    @DisplayName("장중 손절: 저가 ≤ 손절가면 손절가 − 슬리피지로 청산된다 (시가는 위)")
    void carriedStop_intraday_exitsAtStopPrice() {
        givenHeldPosition(3, 120, 100);
        setTodayBar(new Candle(TODAY, 110, 112, 98, 111, 2000)); // 시가 110 > 100, 저가 98 ≤ 100

        double cashBefore = positionManager.getCash();
        sut.simulateDay(CODE, TODAY);

        assertThat(positionStore).doesNotContainKey(CODE);
        double expectedIn = BacktestCosts.sellCashIn(BacktestCosts.sellFillPrice(100), 3);
        assertThat(positionManager.getCash() - cashBefore).isCloseTo(expectedIn, within(1e-6));
    }

    @Test
    @DisplayName("손절선 미장착 보유분은 손절 판정을 하지 않는다")
    void carriedPosition_withoutStop_isNotExited() {
        Position pos = Position.empty(CODE);
        pos.applyBuy(3, 120);
        positionStore.put(CODE, pos); // stopPrice = null
        setTodayBar(new Candle(TODAY, 95, 105, 93, 104, 2000));

        sut.simulateDay(CODE, TODAY);

        assertThat(positionStore.get(CODE).getQuantity()).isEqualTo(3);
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private void givenHeldPosition(int qty, double avgPrice, double stopPrice) {
        Position pos = Position.empty(CODE);
        pos.applyBuy(qty, avgPrice);
        pos.armStopLoss(stopPrice);
        positionStore.put(CODE, pos);
    }

    /** 워밍업 16일 + 전일 — 전일 range = 110-90 = 20 */
    private List<CandleHistory> defaultSeries() {
        List<CandleHistory> rows = new ArrayList<>();
        LocalDate d = TODAY.minusDays(25);
        for (int i = 0; i < 17 && d.isBefore(TODAY); i++) {
            rows.add(CandleHistory.ofDaily(CODE, new Candle(d, 100, 110, 90, 100, 1000)));
            d = d.plusDays(1);
        }
        return rows;
    }

    /** 당일 봉을 시리즈에 넣고 재적재한다 — 목이 seriesRows를 참조하므로 반영된다 */
    private void setTodayBar(Candle bar) {
        seriesRows.add(CandleHistory.ofDaily(CODE, bar));
        market.loadSeries(List.of(CODE), TODAY.minusDays(30), TODAY);
        market.setSimDate(TODAY);
    }

    private CandleHistoryRepository candleRepositoryWith(List<CandleHistory> rows) {
        CandleHistoryRepository repo = mock(CandleHistoryRepository.class);
        when(repo.findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                eq(CODE), eq(Timeframe.DAILY), any(), any()))
                .thenReturn(rows);
        return repo;
    }

    private PositionRepository inMemoryPositionRepository() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findByStockCode(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(positionStore.get(inv.getArgument(0, String.class))));
        when(repo.save(any(Position.class))).thenAnswer(inv -> {
            Position p = inv.getArgument(0);
            positionStore.put(p.getStockCode(), p);
            return p;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            positionStore.remove(inv.getArgument(0, Position.class).getStockCode());
            return null;
        }).when(repo).delete(any(Position.class));
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(positionStore.values()));
        return repo;
    }

    private DailyEquityRepository inMemoryEquityRepository() {
        DailyEquityRepository repo = mock(DailyEquityRepository.class);
        when(repo.findById(any(LocalDate.class)))
                .thenAnswer(inv -> Optional.ofNullable(equityStore.get(inv.getArgument(0, LocalDate.class))));
        when(repo.save(any(DailyEquity.class))).thenAnswer(inv -> {
            DailyEquity e = inv.getArgument(0);
            equityStore.put(e.getTradeDate(), e);
            return e;
        });
        return repo;
    }

    private PortfolioStateRepository inMemoryStateRepository() {
        PortfolioStateRepository repo = mock(PortfolioStateRepository.class);
        when(repo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(stateStore.get(inv.getArgument(0, String.class))));
        when(repo.save(any(PortfolioState.class))).thenAnswer(inv -> {
            PortfolioState s = inv.getArgument(0);
            stateStore.put(s.getStateKey(), s);
            return s;
        });
        return repo;
    }
}
