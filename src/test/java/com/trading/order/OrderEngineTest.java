package com.trading.order;

import com.trading.bucket.BucketAccountService;
import com.trading.bucket.BucketProperties;
import com.trading.bucket.StrategyBucket;
import com.trading.position.TradeResultRepository;
import com.trading.risk.RiskLimitsProperties;
import com.trading.market.AtrCalculator;
import com.trading.market.Candle;
import com.trading.market.MarketDataService;
import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import com.trading.signal.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderEngine 수량 결정 (P2-A): 매수 = R 사이징 수량, 매도 = 보유 전량.
 * OrderSizingService는 실객체(인터페이스 의존성 목킹) — Java 25 Mockito 제약.
 */
@DisplayName("OrderEngine — 매수 R 사이징 / 매도 전량")
class OrderEngineTest {

    private KisOrderClient orderClient;
    private TradingStatusManager statusManager;
    private MarketDataService marketDataService;
    private PositionManager positionManager;
    private PositionRepository positionRepository;
    private OrderEngine sut;

    @BeforeEach
    void setUp() {
        orderClient = mock(KisOrderClient.class);
        statusManager = new TradingStatusManager();
        marketDataService = mock(MarketDataService.class);
        positionManager = mock(PositionManager.class);
        positionRepository = mock(PositionRepository.class);
        // 칸 나누기 OFF — 기존 사이징 동작 유지 (칸 동작은 OrderSizingServiceTest에서 검증)
        BucketProperties bucketProps = new BucketProperties(
                false, "2026-07-20", 10_000_000, 10_000_000, 10_000_000, 10_000_000, false, false, false);
        BucketAccountService bucketAccounts = new BucketAccountService(
                bucketProps, positionRepository, mock(TradeResultRepository.class));
        sut = new OrderEngine(orderClient, statusManager,
                new OrderSizingService(marketDataService, positionManager, new AtrCalculator(),
                        new RiskLimitsProperties(), bucketProps, bucketAccounts, com.trading.bucket.BucketTestSupport.defaultParams()),
                positionRepository);
    }

    /** ATR = range가 되는 캔들 15개 (갭 없음) */
    private void givenAtr(double range) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            candles.add(new Candle(LocalDate.now().minusDays(15 - i),
                    100_000, 100_000 + range / 2, 100_000 - range / 2, 100_000, 1000));
        }
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(candles);
    }

    @Test
    @DisplayName("매수: 1R=10만·손절폭=3천 → 33주 주문")
    void buy_uses_sized_quantity() {
        givenAtr(2_000); // 손절폭 3,000
        when(positionManager.snapshotAccount())
                .thenReturn(new Account(10_000_000, 0.0, 0, List.of()));

        sut.execute(Signal.buy("005930", "test"));

        verify(orderClient).buy("005930", 33, StrategyBucket.VB);
    }

    @Test
    @DisplayName("매수: 사이징 스킵(ATR 불가) → 주문 없음")
    void buy_skipped_when_sizing_fails() {
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(List.of());
        when(positionManager.snapshotAccount())
                .thenReturn(new Account(10_000_000, 0.0, 0, List.of()));

        sut.execute(Signal.buy("005930", "test"));

        verify(orderClient, never()).buy(anyString(), anyInt(), any());
        verify(orderClient, never()).buy(anyString(), anyInt());
        verify(orderClient, never()).buy(anyString());
    }

    @Test
    @DisplayName("매도: 보유 33주 → 전량 매도")
    void sell_uses_full_held_quantity() {
        Position pos = Position.empty("005930");
        pos.applyBuy(33, 72_500.0);
        when(positionRepository.findByStockCode("005930")).thenReturn(Optional.of(pos));

        sut.execute(Signal.sell("005930", "test"));

        verify(orderClient).sell("005930", 33);
    }

    @Test
    @DisplayName("매도: 보유 없음 → 주문 없음")
    void sell_skipped_without_position() {
        when(positionRepository.findByStockCode("005930")).thenReturn(Optional.empty());

        sut.execute(Signal.sell("005930", "test"));

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("EMERGENCY_STOPPED → 매수/매도 모두 차단")
    void blocked_when_not_running() {
        statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);

        sut.execute(Signal.buy("005930", "test"));
        sut.execute(Signal.sell("005930", "test"));

        verify(orderClient, never()).buy(anyString(), anyInt(), any());
        verify(orderClient, never()).buy(anyString(), anyInt());
        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    // ── executeManualBuy (리허설용 고정 수량 매수) ─────────────────────────────

    @Test
    @DisplayName("수동 매수: 사이징을 거치지 않고 지정 수량 그대로 주문")
    void manual_buy_uses_fixed_quantity_without_sizing() {
        // ATR 산출 불가 → 자동 경로였다면 사이징 스킵될 상황
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(List.of());

        sut.executeManualBuy(Signal.buy("005930", "MANUAL_DRILL"), 10);

        verify(orderClient).buy("005930", 10, StrategyBucket.VB);
    }

    @Test
    @DisplayName("수동 매수: SAFE_MODE면 차단 (자동 매수와 동일 게이팅)")
    void manual_buy_blocked_in_safe_mode() {
        statusManager.changeMode(TradingMode.SAFE_MODE);

        sut.executeManualBuy(Signal.buy("005930", "MANUAL_DRILL"), 10);

        verify(orderClient, never()).buy(anyString(), anyInt(), any());
        verify(orderClient, never()).buy(anyString(), anyInt());
    }

    @Test
    @DisplayName("수동 매수: EMERGENCY_STOPPED면 차단")
    void manual_buy_blocked_when_stopped() {
        statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);

        sut.executeManualBuy(Signal.buy("005930", "MANUAL_DRILL"), 10);

        verify(orderClient, never()).buy(anyString(), anyInt(), any());
        verify(orderClient, never()).buy(anyString(), anyInt());
    }

    @Test
    @DisplayName("수동 매수: 매도 신호·수량 0 이하는 무시")
    void manual_buy_ignores_sell_signal_and_non_positive_quantity() {
        sut.executeManualBuy(Signal.sell("005930", "MANUAL_DRILL"), 10);
        sut.executeManualBuy(Signal.buy("005930", "MANUAL_DRILL"), 0);

        verify(orderClient, never()).buy(anyString(), anyInt(), any());
        verify(orderClient, never()).buy(anyString(), anyInt());
        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("SAFE_MODE → 매수는 차단, 매도(방어 로직)는 통과")
    void safe_mode_blocks_buy_but_allows_sell() {
        statusManager.changeMode(TradingMode.SAFE_MODE);
        Position pos = Position.empty("005930");
        pos.applyBuy(33, 72_500.0);
        when(positionRepository.findByStockCode("005930")).thenReturn(Optional.of(pos));

        sut.execute(Signal.buy("005930", "test"));
        sut.execute(Signal.sell("005930", "test"));

        verify(orderClient, never()).buy(anyString(), anyInt(), any());
        verify(orderClient, never()).buy(anyString(), anyInt());
        verify(orderClient).sell("005930", 33);
    }
}
