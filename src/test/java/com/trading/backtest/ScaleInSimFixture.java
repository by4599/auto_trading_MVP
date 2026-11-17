package com.trading.backtest;

import com.trading.bucket.BucketTestSupport;
import com.trading.market.AtrCalculator;
import com.trading.market.Candle;
import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.Timeframe;
import com.trading.order.OrderEngine;
import com.trading.order.OrderFilledEvent;
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
import com.trading.risk.IndexRegimeSource;
import com.trading.risk.PendingOrderRule;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskLimitsProperties;
import com.trading.risk.RiskRule;
import com.trading.risk.StopLossArmer;
import com.trading.risk.TradingStatusManager;
import com.trading.risk.TrailingStopTracker;
import com.trading.signal.SignalDispatcher;
import com.trading.strategy.AccumulationProperties;
import com.trading.strategy.FilterProperties;
import com.trading.strategy.RsiProperties;
import com.trading.strategy.ScalpingProperties;
import com.trading.strategy.StrategyParameters;
import com.trading.strategy.VolatilityBreakoutStrategy;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 분할 매수 시뮬레이터 배선 — Spring 없이 실객체(인터페이스만 목). {@link DailyBarSimulatorTest}와 같은 시나리오:
 * 워밍업 o=100 h=110 l=90 c=100 → ATR 20 · 손절폭 ATR×1.5=30 · 돌파가 = 시가 + 10(VB K=0.5).
 * 체결 이벤트는 실제 {@link StopLossArmer}로 흘려 손절선이 라이브와 같은 경로로 장착된다.
 */
final class ScaleInSimFixture {

    static final String CODE = "005930";
    static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    final Map<String, Position> positionStore = new HashMap<>();
    final ScaleInProperties props = new ScaleInProperties();
    final ScaleInRunStats stats = new ScaleInRunStats();
    final TradeRecorder tradeRecorder = new TradeRecorder();
    final BacktestMarketDataService market;
    final BacktestPositionManager positionManager;
    final DailyBarSimulator sut;
    final BacktestStopRules stopRules;
    private final List<CandleHistory> seriesRows = new ArrayList<>();
    private final Map<LocalDate, DailyEquity> equityStore = new HashMap<>();
    private final Map<String, PortfolioState> stateStore = new HashMap<>();
    /** 지수 하락 추세 판정 — null이면 판정 없음 */
    Boolean indexBelow = null;

    ScaleInSimFixture(List<RiskRule> addOnRules) {
        MutableClock clock = new MutableClock(Instant.now());
        PositionRepository positions = inMemoryPositions();
        TradeResultTracker tracker = new TradeResultTracker(inMemoryState());
        OrderHistoryRepository orders = mock(OrderHistoryRepository.class);
        when(orders.save(any(OrderHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        seriesRows.addAll(warmup());
        market = new BacktestMarketDataService(candleRepo());
        market.loadSeries(List.of(CODE), TODAY.minusDays(30), TODAY);
        positionManager = new BacktestPositionManager(positions, inMemoryEquity(), tracker, market,
                new BacktestDataProperties(), clock);

        FilterProperties filters = new FilterProperties();
        RiskLimitsProperties limits = new RiskLimitsProperties();
        StopLossArmer armer = new StopLossArmer(market, new AtrCalculator(), positions,
                BucketTestSupport.defaultParams(limits, filters));
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof OrderFilledEvent e) armer.onOrderFilled(e);
        };
        BacktestOrderClient orderClient = new BacktestOrderClient(orders, positions, tracker, publisher,
                market, positionManager, tradeRecorder, clock, new BacktestCostProperties());
        OrderEngine orderEngine = new OrderEngine(orderClient, new TradingStatusManager(),
                new OrderSizingService(market, positionManager, new AtrCalculator(), limits,
                        BucketTestSupport.disabledProps(), BucketTestSupport.disabledAccounts(),
                        BucketTestSupport.defaultParams(limits, filters)),
                positions);

        // 1차 진입 경로는 라이브처럼 중복 매수 차단(PendingOrderRule)을 탄다 — 보유 종목 재진입 방지
        RiskEngine riskEngine = new RiskEngine(List.of(new PendingOrderRule(positions, orders)));
        TrailingStopTracker trailing = new TrailingStopTracker(BucketTestSupport.defaultParams(limits, filters));
        DailyBarExitSimulator exits = new DailyBarExitSimulator(market, riskEngine, orderEngine, positions,
                positionManager, orderClient, trailing, clock, new ScalpingProperties(), new ExitLabProperties());
        stopRules = new BacktestStopRules(positions, market, new AtrCalculator());
        ScaleInExecutor scaleIn = new ScaleInExecutor(props, market, positions, positionManager, orderEngine,
                addOnRules, indexSource(), new AtrCalculator(), clock, new BacktestCostProperties(), stats, stopRules);
        SignalDispatcher dispatcher = new SignalDispatcher(
                List.of(new VolatilityBreakoutStrategy(new StrategyParameters(), filters)));
        sut = new DailyBarSimulator(market, dispatcher, riskEngine, orderEngine, positions, positionManager,
                trailing, exits, clock, new RsiProperties(), new AccumulationProperties(), scaleIn, stopRules,
                BearRsGates.off());
        stats.beginRun("WF00-validate-K0.5");
        market.setSimDate(TODAY);
    }

    /** 그날 봉을 넣고 그날로 시뮬 날짜를 옮긴다 */
    void setBar(Candle bar) {
        seriesRows.add(CandleHistory.ofDaily(CODE, bar));
        market.loadSeries(List.of(CODE), TODAY.minusDays(30), bar.date());
        market.clearSimPrices();
        market.setSimDate(bar.date());
    }

    void simulate(LocalDate date) {
        sut.simulateDay(CODE, date);
    }

    Position position() {
        return positionStore.get(CODE);
    }

    private IndexRegimeSource indexSource() {
        return new IndexRegimeSource() {
            @Override public Optional<Boolean> isBearishRegime() { return Optional.empty(); }
            @Override public Optional<Boolean> isBelowTrend(int maPeriod) { return Optional.ofNullable(indexBelow); }
        };
    }

    private List<CandleHistory> warmup() {
        List<CandleHistory> rows = new ArrayList<>();
        LocalDate d = TODAY.minusDays(25);
        for (int i = 0; i < 17 && d.isBefore(TODAY); i++) {
            rows.add(CandleHistory.ofDaily(CODE, new Candle(d, 100, 110, 90, 100, 1000)));
            d = d.plusDays(1);
        }
        return rows;
    }

    private CandleHistoryRepository candleRepo() {
        CandleHistoryRepository repo = mock(CandleHistoryRepository.class);
        when(repo.findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                eq(CODE), eq(Timeframe.DAILY), any(), any())).thenReturn(seriesRows);
        return repo;
    }

    private PositionRepository inMemoryPositions() {
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

    private DailyEquityRepository inMemoryEquity() {
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

    private PortfolioStateRepository inMemoryState() {
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
