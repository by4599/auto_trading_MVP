package com.trading.backtest;

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
import com.trading.risk.RiskLimitsProperties;
import com.trading.risk.TradingStatusManager;
import com.trading.risk.TrailingStopTracker;
import com.trading.strategy.FilterProperties;
import com.trading.strategy.ScalpingProperties;
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
 * 고정% 브래킷 출구 (BACKTEST-DESIGN §17) — 이월 보유분의 손절·목표 판정 규칙.
 *
 * <p>못 박는 것: ① 갭은 레벨가가 아니라 더 나쁜 시가에 체결된다 ② 갭 상승은 목표가에 체결된다
 * (더 좋은 시가를 주지 않는다) ③ 같은 날 손절·목표를 둘 다 건드리면 손절이 이긴다
 * ④ <b>stopPct·targetPct가 0이면 기존 ATR 경로와 완전히 동일</b>하다.
 */
@DisplayName("DailyBarExitSimulator — 고정% 브래킷 손절·목표 (§17)")
class DailyBarBracketExitTest {

    private static final String CODE = "005930";
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15); // 월요일
    private static final int QTY = 3;

    private final Map<String, Position> positionStore = new HashMap<>();
    private final Map<LocalDate, DailyEquity> equityStore = new HashMap<>();
    private final Map<String, PortfolioState> stateStore = new HashMap<>();
    private final List<CandleHistory> seriesRows = new ArrayList<>();

    private BacktestMarketDataService market;
    private BacktestPositionManager positionManager;
    private TradeRecorder tradeRecorder;
    private ExitLabProperties exitLab;
    private DailyBarExitSimulator sut;

    @BeforeEach
    void setUp() {
        MutableClock clock = new MutableClock(Instant.now());
        tradeRecorder = new TradeRecorder();
        exitLab = new ExitLabProperties();

        PositionRepository positionRepository = inMemoryPositionRepository();
        TradeResultTracker tracker = new TradeResultTracker(inMemoryStateRepository());
        OrderHistoryRepository orderHistoryRepository = mock(OrderHistoryRepository.class);
        when(orderHistoryRepository.save(any(OrderHistory.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        seriesRows.addAll(warmupSeries());
        market = new BacktestMarketDataService(candleRepositoryWith(seriesRows));
        market.loadSeries(List.of(CODE), TODAY.minusDays(30), TODAY);

        positionManager = new BacktestPositionManager(positionRepository, inMemoryEquityRepository(),
                tracker, market, new BacktestDataProperties(), clock);
        BacktestOrderClient orderClient = new BacktestOrderClient(orderHistoryRepository,
                positionRepository, tracker, mock(ApplicationEventPublisher.class), market,
                positionManager, tradeRecorder, clock, new BacktestCostProperties());
        FilterProperties filters = new FilterProperties();
        OrderEngine orderEngine = new OrderEngine(orderClient, new TradingStatusManager(),
                new OrderSizingService(market, positionManager, new AtrCalculator(),
                        new RiskLimitsProperties(), com.trading.bucket.BucketTestSupport.disabledProps(),
                        com.trading.bucket.BucketTestSupport.disabledAccounts(),
                        com.trading.bucket.BucketTestSupport.defaultParams()),
                positionRepository);

        sut = new DailyBarExitSimulator(market, new RiskEngine(List.of()), orderEngine,
                positionRepository, positionManager, orderClient,
                new TrailingStopTracker(com.trading.bucket.BucketTestSupport.defaultParams(
                        new RiskLimitsProperties(), filters)),
                clock, new ScalpingProperties(), exitLab);
        market.setSimDate(TODAY);
    }

    // ── 손절 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("갭 관통: 시가 ≤ 손절레벨이면 레벨이 아니라 시가에 체결된다")
    void bracketStop_gapThrough_exitsAtOpen() {
        exitLab.setStopPct(0.05);                 // 진입 100 → 손절 95
        givenHeldPosition(100);
        Candle bar = new Candle(TODAY, 90, 99, 88, 98, 2000); // 시가 90 ≤ 95

        double cashBefore = positionManager.getCash();
        sut.checkCarriedStop(CODE, TODAY, bar);

        assertExited(DailyBarExitSimulator.EXIT_BRACKET_STOP_GAP, 90, cashBefore);
    }

    @Test
    @DisplayName("장중 관통: 시가 > 레벨이고 저가 ≤ 레벨이면 레벨에 체결된다")
    void bracketStop_intradayTouch_exitsAtLevel() {
        exitLab.setStopPct(0.05);                 // 손절 95
        givenHeldPosition(100);
        Candle bar = new Candle(TODAY, 99, 101, 94, 96, 2000);

        double cashBefore = positionManager.getCash();
        sut.checkCarriedStop(CODE, TODAY, bar);

        assertExited(DailyBarExitSimulator.EXIT_BRACKET_STOP, 95, cashBefore);
    }

    @Test
    @DisplayName("stopPct=0(기본값)이면 브래킷이 아니라 기존 ATR 손절선으로 판정한다")
    void bracketOff_usesArmedAtrStop() {
        givenHeldPosition(100);                   // ATR 손절선 80 (armStopLoss)
        Candle bar = new Candle(TODAY, 99, 101, 94, 96, 2000); // 저가 94 > 80 → 미발동

        sut.checkCarriedStop(CODE, TODAY, bar);

        assertThat(positionStore).containsKey(CODE);
        assertThat(tradeRecorder.getTrades()).isEmpty();
    }

    // ── 목표 익절 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("고가 ≥ 목표면 목표가에 익절된다")
    void bracketTarget_highReachesTarget_exitsAtTarget() {
        exitLab.setTargetPct(0.15);               // 진입 100 → 목표 115
        givenHeldPosition(100);
        Candle bar = new Candle(TODAY, 101, 118, 100, 117, 2000);

        double cashBefore = positionManager.getCash();
        sut.checkBracketTarget(CODE, TODAY, bar);

        assertExited(DailyBarExitSimulator.EXIT_BRACKET_TARGET, 115, cashBefore);
    }

    @Test
    @DisplayName("갭 상승으로 시가가 이미 목표 위여도 더 좋은 시가가 아니라 목표가에 체결된다")
    void bracketTarget_gapAboveTarget_stillFillsAtTarget() {
        exitLab.setTargetPct(0.15);               // 목표 115
        givenHeldPosition(100);
        Candle bar = new Candle(TODAY, 130, 135, 128, 132, 2000);

        double cashBefore = positionManager.getCash();
        sut.checkBracketTarget(CODE, TODAY, bar);

        assertExited(DailyBarExitSimulator.EXIT_BRACKET_TARGET, 115, cashBefore);
    }

    @Test
    @DisplayName("targetPct=0(기본값)이면 고가가 아무리 높아도 목표 익절은 없다")
    void bracketTarget_disabled_doesNothing() {
        givenHeldPosition(100);
        Candle bar = new Candle(TODAY, 130, 200, 128, 190, 2000);

        sut.checkBracketTarget(CODE, TODAY, bar);

        assertThat(positionStore).containsKey(CODE);
        assertThat(tradeRecorder.getTrades()).isEmpty();
    }

    // ── 같은 날 둘 다 건드리면 손절 우선 (애매하면 항상 불리하게) ─────────────────

    @Test
    @DisplayName("이월분이 손절·목표를 같은 날 둘 다 건드리면 손절이 이긴다")
    void bothTouched_stopWins() {
        exitLab.setStopPct(0.05);                 // 손절 95
        exitLab.setTargetPct(0.15);               // 목표 115
        givenHeldPosition(100);
        Candle bar = new Candle(TODAY, 99, 120, 90, 118, 2000); // 저가 90·고가 120 둘 다 관통

        double cashBefore = positionManager.getCash();
        sut.checkCarriedStop(CODE, TODAY, bar);   // 하루 시퀀스는 손절을 먼저 부른다
        sut.checkBracketTarget(CODE, TODAY, bar); // 이미 청산돼 no-op이어야 한다

        assertExited(DailyBarExitSimulator.EXIT_BRACKET_STOP, 95, cashBefore);
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private void assertExited(String expectedReason, double expectedFillBase, double cashBefore) {
        assertThat(positionStore).doesNotContainKey(CODE);
        assertThat(tradeRecorder.getTrades()).singleElement()
                .extracting(TradeRecorder.ClosedTrade::exitReason).isEqualTo(expectedReason);
        double expectedIn = BacktestCosts.sellCashIn(
                BacktestCosts.sellFillPrice(expectedFillBase), QTY);
        assertThat(positionManager.getCash() - cashBefore).isCloseTo(expectedIn, within(1e-6));
    }

    private void givenHeldPosition(double avgPrice) {
        Position pos = Position.empty(CODE);
        pos.applyBuy(QTY, avgPrice);
        pos.armStopLoss(avgPrice * 0.8);   // 기존 ATR 손절선 — 브래킷 켜지면 쓰이지 않아야 한다
        positionStore.put(CODE, pos);
        // 원장에도 매수 랏을 심는다 — 없으면 TradeRecorder가 매도를 무시해 사유를 못 센다
        tradeRecorder.onBuyFill(CODE, QTY, avgPrice * QTY, TODAY.minusDays(1));
    }

    private List<CandleHistory> warmupSeries() {
        List<CandleHistory> rows = new ArrayList<>();
        LocalDate d = TODAY.minusDays(25);
        for (int i = 0; i < 17 && d.isBefore(TODAY); i++) {
            rows.add(CandleHistory.ofDaily(CODE, new Candle(d, 100, 110, 90, 100, 1000)));
            d = d.plusDays(1);
        }
        return rows;
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
