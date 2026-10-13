package com.trading.backtest;

import com.trading.bucket.BucketTestSupport;
import com.trading.bucket.StrategyBucket;
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
import com.trading.position.PortfolioStateRepository;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResultTracker;
import com.trading.risk.PostTimeCutBuyRule;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskLimitsProperties;
import com.trading.risk.RiskResult;
import com.trading.risk.RiskRule;
import com.trading.risk.TradingStatusManager;
import com.trading.risk.TrailingStopTracker;
import com.trading.signal.Signal;
import com.trading.signal.SignalDispatcher;
import com.trading.strategy.AccumulationProperties;
import com.trading.strategy.FilterProperties;
import com.trading.strategy.RsiProperties;
import com.trading.strategy.ScalpingProperties;
import com.trading.strategy.Strategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * 다음 날 시가 진입 경로 (흔적레이더 검증, BACKTEST-DESIGN §18) — 매집 흔적 전략이 켜졌을 때만 탄다.
 *
 * <p>못 박는 것: ① 진입가 = 당일 시가 + 슬리피지(돌파가·고가·종가 아님), 판정 시각 09:00
 * ② 판정 시점에 전략이 보는 당일 봉은 <b>시가 하나뿐</b>(고가·저가·종가 미노출)
 * ③ 장 시작 때 보유 중이면 사지 않는다(이월 손절로 판 날도 같은 시가로 되사지 않는다)
 * ④ 시가 진입분도 당일 손절 판정을 받는다 ⑤ 스위치가 꺼져 있으면 기존 10:00 돌파 경로 그대로.
 * Spring 없이 실객체 배선 — 인터페이스(Strategy·RiskRule·리포지토리)만 테스트 대역을 쓴다.
 */
@DisplayName("DailyBarSimulator — 다음 날 시가 진입 경로 (§18)")
class DailyBarNextOpenEntryTest {

    private static final String CODE = "005930";
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15); // 월요일

    private final Map<String, Position> positionStore = new HashMap<>();
    private final List<CandleHistory> seriesRows = new ArrayList<>();
    private final List<LocalTime> buyCheckTimes = new ArrayList<>();
    private final List<Candle> todaySeenByStrategy = new ArrayList<>();
    private boolean signalOn = true;

    private BacktestMarketDataService market;
    private BacktestPositionManager positionManager;
    private MutableClock clock;
    private TradeRecorder tradeRecorder;
    private ExitLabProperties exitLab;
    private AccumulationProperties accumulation;
    private DailyBarSimulator sut;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.now());
        tradeRecorder = new TradeRecorder();
        PositionRepository positions = inMemoryPositionRepository();
        TradeResultTracker tracker = new TradeResultTracker(mock(PortfolioStateRepository.class));
        OrderHistoryRepository orders = mock(OrderHistoryRepository.class);
        when(orders.save(any(OrderHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        for (int i = 0; i < 17; i++) {   // 워밍업 17일 (o=100 h=110 l=90 c=100 → ATR 20)
            seriesRows.add(CandleHistory.ofDaily(CODE, new Candle(TODAY.minusDays(25 - i), 100, 110, 90, 100, 1000)));
        }
        market = new BacktestMarketDataService(candleRepositoryWith(seriesRows));

        positionManager = new BacktestPositionManager(positions,
                inMemoryEquityRepository(), tracker, market, new BacktestDataProperties(), clock);
        BacktestOrderClient orderClient = new BacktestOrderClient(orders, positions, tracker,
                mock(ApplicationEventPublisher.class), market, positionManager, tradeRecorder, clock,
                new BacktestCostProperties());
        OrderEngine orderEngine = new OrderEngine(orderClient, new TradingStatusManager(),
                new OrderSizingService(market, positionManager, new AtrCalculator(), new RiskLimitsProperties(),
                        BucketTestSupport.disabledProps(), BucketTestSupport.disabledAccounts(),
                        BucketTestSupport.defaultParams()),
                positions);

        RiskRule recordBuyTime = (signal, account) -> {
            if (signal.isBuy()) buyCheckTimes.add(LocalTime.now(clock));
            return RiskResult.pass();
        };
        RiskEngine riskEngine = new RiskEngine(List.of(recordBuyTime));
        FilterProperties filters = new FilterProperties();
        TrailingStopTracker trailing = new TrailingStopTracker(
                BucketTestSupport.defaultParams(new RiskLimitsProperties(), filters));
        exitLab = new ExitLabProperties();
        DailyBarExitSimulator exits = new DailyBarExitSimulator(market, riskEngine, orderEngine, positions,
                positionManager, orderClient, trailing, clock, new ScalpingProperties(), exitLab);

        accumulation = new AccumulationProperties();
        accumulation.setEnabled(true);
        SignalDispatcher dispatcher = new SignalDispatcher(List.of(traceStrategy()));
        sut = new DailyBarSimulator(market, dispatcher, riskEngine, orderEngine, positions, positionManager,
                trailing, exits, clock, new RsiProperties(), accumulation);
    }

    /** 가격과 무관하게 발화하는 대역 전략 — 받은 당일 봉(마지막 캔들)을 기록한다 */
    private Strategy traceStrategy() {
        return new Strategy() {
            @Override public String getName() { return "TEST_TRACE"; }
            @Override public List<Signal> evaluate(String code, List<Candle> candles) {
                todaySeenByStrategy.add(candles.get(candles.size() - 1));
                return signalOn ? List.of(Signal.buy(code, "TEST_TRACE", StrategyBucket.TREND)) : List.of();
            }
        };
    }

    @Test
    @DisplayName("신호가 켜지면 시가 + 슬리피지에 산다 — 돌파가·고가·종가가 아니다, 판정 시각 09:00")
    void buysAtOpenAtNine() {
        setTodayBar(new Candle(TODAY, 100, 115, 95, 112, 2000));

        sut.simulateDay(CODE, TODAY);

        assertThat(positionStore.get(CODE)).isNotNull();
        assertThat(positionStore.get(CODE).getAveragePrice()).isCloseTo(100 * 1.001, within(1e-9));
        assertThat(buyCheckTimes).containsExactly(DailyBarSimulator.OPEN_ENTRY_AT);
        assertThat(DailyBarSimulator.OPEN_ENTRY_AT)
                .isBefore(DailyBarSimulator.BREAKOUT_ENTRY_AT)
                .isBefore(PostTimeCutBuyRule.TIME_CUT_AT);
    }

    @Test
    @DisplayName("판정 시점에 전략이 보는 당일 봉은 시가뿐 — 고가·저가·종가가 시가로 잘려 있다")
    void strategySeesOnlyTheOpen() {
        setTodayBar(new Candle(TODAY, 100, 115, 95, 112, 2000));

        sut.simulateDay(CODE, TODAY);

        assertThat(todaySeenByStrategy).isNotEmpty().allSatisfy(c -> {
            assertThat(c.getClose()).isEqualTo(100);
            assertThat(c.getHigh()).isEqualTo(100);
            assertThat(c.getLow()).isEqualTo(100);
        });
    }

    @Test
    @DisplayName("신호가 없으면 사지 않는다")
    void noSignalNoEntry() {
        signalOn = false;
        setTodayBar(new Candle(TODAY, 100, 115, 95, 112, 2000));

        sut.simulateDay(CODE, TODAY);

        assertThat(positionStore).doesNotContainKey(CODE);
    }

    @Test
    @DisplayName("장 시작 때 보유 중이면 사지 않는다 — 갭 손절로 판 날에도 같은 시가로 되사지 않는다")
    void noReentryWhenHeldAtOpen() {
        Position held = Position.empty(CODE);
        held.applyBuy(3, 120);
        held.armStopLoss(100);
        positionStore.put(CODE, held);
        setTodayBar(new Candle(TODAY, 95, 105, 93, 104, 2000)); // 시가 95 ≤ 손절 100 → 시가 청산

        double cashBefore = positionManager.getCash();
        sut.simulateDay(CODE, TODAY);

        assertThat(positionStore).doesNotContainKey(CODE);
        double gapExitCash = BacktestCosts.sellCashIn(BacktestCosts.sellFillPrice(95), 3);
        assertThat(positionManager.getCash() - cashBefore).isCloseTo(gapExitCash, within(1e-6));
        assertThat(buyCheckTimes).as("매수 판정 자체가 없어야 한다").isEmpty();
    }

    @Test
    @DisplayName("시가 진입분도 같은 날 손절 판정을 받는다 — 저가가 손절선 아래면 그날 손절(불리하게)")
    void sameDayStopAppliesAfterOpenEntry() {
        exitLab.setStopPct(0.05); // 손절선 = 진입가 × 0.95 (ATR 장착 이벤트 없이 레벨을 결정적으로 둔다)
        setTodayBar(new Candle(TODAY, 100, 104, 90, 101, 2000));

        sut.simulateDay(CODE, TODAY);

        assertThat(positionStore).doesNotContainKey(CODE);
        assertThat(tradeRecorder.getTrades()).singleElement()
                .extracting(TradeRecorder.ClosedTrade::exitReason)
                .isEqualTo(DailyBarExitSimulator.EXIT_BRACKET_STOP);
    }

    @Test
    @DisplayName("스위치가 꺼져 있으면 기존 돌파 경로(10:00) 그대로 — 새 경로가 끼어들지 않는다")
    void switchOffKeepsBreakoutPath() {
        accumulation.setEnabled(false);
        setTodayBar(new Candle(TODAY, 100, 115, 95, 112, 2000));

        sut.simulateDay(CODE, TODAY);

        assertThat(buyCheckTimes).containsExactly(DailyBarSimulator.BREAKOUT_ENTRY_AT);
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private void setTodayBar(Candle bar) {
        seriesRows.add(CandleHistory.ofDaily(CODE, bar));
        market.loadSeries(List.of(CODE), TODAY.minusDays(30), TODAY);
        market.setSimDate(TODAY);
    }

    private CandleHistoryRepository candleRepositoryWith(List<CandleHistory> rows) {
        CandleHistoryRepository repo = mock(CandleHistoryRepository.class);
        when(repo.findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                eq(CODE), eq(Timeframe.DAILY), any(), any())).thenReturn(rows);
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
        Map<LocalDate, DailyEquity> store = new HashMap<>();
        DailyEquityRepository repo = mock(DailyEquityRepository.class);
        when(repo.findById(any(LocalDate.class)))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, LocalDate.class))));
        when(repo.save(any(DailyEquity.class))).thenAnswer(inv -> {
            DailyEquity e = inv.getArgument(0);
            store.put(e.getTradeDate(), e);
            return e;
        });
        return repo;
    }
}
