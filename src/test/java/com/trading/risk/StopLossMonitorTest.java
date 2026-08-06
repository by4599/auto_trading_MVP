package com.trading.risk;

import com.trading.market.AtrCalculator;
import com.trading.market.KisProperties;
import com.trading.market.MarketDataService;
import com.trading.order.KisOrderClient;
import com.trading.order.OrderEngine;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderSizingService;
import com.trading.order.OrderStatus;
import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.strategy.ScalpingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ATR 손절 감시 — 현재가 ≤ 손절선이면 평시 매도 경로(RiskEngine→OrderEngine)로 전량 매도.
 */
@DisplayName("StopLossMonitor — 손절선 하회 시 전량 매도")
class StopLossMonitorTest {

    private PositionRepository positionRepository;
    private OrderHistoryRepository orderHistoryRepository;
    private PositionManager positionManager;
    private KisOrderClient orderClient;
    private TradingStatusManager statusManager;
    private KisProperties kisProperties;
    private StopLossMonitor sut;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        orderHistoryRepository = mock(OrderHistoryRepository.class);
        positionManager = mock(PositionManager.class);
        orderClient = mock(KisOrderClient.class);
        statusManager = new TradingStatusManager();

        kisProperties = new KisProperties();
        kisProperties.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        kisProperties.setAppkey("k");
        kisProperties.setSecretkey("s");
        kisProperties.setAccountNo("50000000-01");

        OrderEngine orderEngine = new OrderEngine(orderClient, statusManager,
                new OrderSizingService(mock(MarketDataService.class), positionManager, new AtrCalculator(), new RiskLimitsProperties(),
                        com.trading.bucket.BucketTestSupport.disabledProps(),
                        com.trading.bucket.BucketTestSupport.disabledAccounts(), com.trading.bucket.BucketTestSupport.defaultParams()),
                positionRepository);
        sut = new StopLossMonitor(positionRepository, orderHistoryRepository, positionManager,
                new RiskEngine(List.of()), orderEngine, statusManager, kisProperties,
                new TrailingStopTracker(com.trading.bucket.BucketTestSupport.defaultParams()),
                new com.trading.strategy.ScalpingProperties());
    }

    /** 보유 33주 @72,500, 손절선 69,500, 현재가 currentPrice인 상태를 구성 */
    private Position givenArmedPosition(double stopPrice, double currentPrice) {
        Position pos = Position.empty("005930");
        pos.applyBuy(33, 72_500.0);
        pos.armStopLoss(stopPrice);
        when(positionRepository.findByStockCode("005930")).thenReturn(Optional.of(pos));
        when(positionManager.snapshotAccount()).thenReturn(new Account(
                10_000_000, 0.0, 0,
                List.of(new Account.PositionSnapshot("005930", 33, 72_500.0, currentPrice))));
        return pos;
    }

    @Test
    @DisplayName("현재가 69,000 ≤ 손절선 69,500 → 전량(33주) 매도")
    void sells_all_when_price_at_or_below_stop() {
        givenArmedPosition(69_500.0, 69_000.0);

        sut.checkStops();

        verify(orderClient).sell("005930", 33);
    }

    @Test
    @DisplayName("현재가 70,000 > 손절선 → 매도 없음")
    void holds_above_stop() {
        givenArmedPosition(69_500.0, 70_000.0);

        sut.checkStops();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("손절선 미장착(null) → 판정하지 않음")
    void ignores_unarmed_position() {
        Position pos = Position.empty("005930");
        pos.applyBuy(33, 72_500.0);
        when(positionRepository.findByStockCode("005930")).thenReturn(Optional.of(pos));
        when(positionManager.snapshotAccount()).thenReturn(new Account(
                10_000_000, 0.0, 0,
                List.of(new Account.PositionSnapshot("005930", 33, 72_500.0, 10.0))));

        sut.checkStops();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("시세 불명(currentPrice<=0) → 판정하지 않음 (오탐 방지)")
    void ignores_unknown_price() {
        givenArmedPosition(69_500.0, 0.0);

        sut.checkStops();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("낡은(폴백) 스냅샷 → 손절선 하회여도 매도 안 함 (옛 가격 헛매도 방지)")
    void skips_when_snapshot_stale() {
        Position pos = Position.empty("005930");
        pos.applyBuy(33, 72_500.0);
        pos.armStopLoss(69_500.0);
        when(positionRepository.findByStockCode("005930")).thenReturn(Optional.of(pos));
        // 현재가 69,000 ≤ 손절선 69,500 이지만 스냅샷이 낡음(asStale)
        when(positionManager.snapshotAccount()).thenReturn(new Account(
                10_000_000, 0.0, 0,
                List.of(new Account.PositionSnapshot("005930", 33, 72_500.0, 69_000.0))).asStale());

        sut.checkStops();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("미체결 SELL 존재 → 중복 매도 방지")
    void skips_when_pending_sell_exists() {
        givenArmedPosition(69_500.0, 69_000.0);
        when(orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                "005930", OrderSide.SELL, OrderStatus.ACCEPTED)).thenReturn(true);

        sut.checkStops();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("EMERGENCY_STOPPED → 감시 중단 (청산 상태머신에 양보)")
    void yields_when_not_running() {
        givenArmedPosition(69_500.0, 69_000.0);
        statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);

        sut.checkStops();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("SAFE_MODE → 손절 감시는 유지 (신규 매수만 금지되는 모드)")
    void still_monitors_in_safe_mode() {
        givenArmedPosition(69_500.0, 69_000.0);
        statusManager.changeMode(TradingMode.SAFE_MODE);

        sut.checkStops();

        verify(orderClient).sell("005930", 33);
    }

    // ── 스캘핑(방식3, MIX 버킷) 목표 익절 ────────────────────────────────────

    private ScalpingProperties scalpingProps(boolean enabled, double takeProfitPct) {
        ScalpingProperties props = new ScalpingProperties();
        props.setEnabled(enabled);
        props.setTakeProfitPct(takeProfitPct);
        return props;
    }

    private void rebuildWithScalping(ScalpingProperties scalpingProperties) {
        OrderEngine orderEngine = new OrderEngine(orderClient, statusManager,
                new OrderSizingService(mock(MarketDataService.class), positionManager, new AtrCalculator(), new RiskLimitsProperties(),
                        com.trading.bucket.BucketTestSupport.disabledProps(),
                        com.trading.bucket.BucketTestSupport.disabledAccounts(), com.trading.bucket.BucketTestSupport.defaultParams()),
                positionRepository);
        sut = new StopLossMonitor(positionRepository, orderHistoryRepository, positionManager,
                new RiskEngine(List.of()), orderEngine, statusManager, kisProperties,
                new TrailingStopTracker(com.trading.bucket.BucketTestSupport.defaultParams()),
                scalpingProperties);
    }

    private Position givenMixPosition(double avgPrice, double currentPrice) {
        Position pos = Position.empty("005930");
        pos.applyBuy(10, avgPrice);
        pos.assignBucketIfAbsent(com.trading.bucket.StrategyBucket.MIX);
        when(positionRepository.findByStockCode("005930")).thenReturn(Optional.of(pos));
        when(positionManager.snapshotAccount()).thenReturn(new Account(
                10_000_000, 0.0, 0,
                List.of(new Account.PositionSnapshot("005930", 10, avgPrice, currentPrice))));
        return pos;
    }

    @Test
    @DisplayName("스캘핑 활성 + MIX 버킷 + 목표수익(+0.8%) 도달 → 전량 익절 매도")
    void takes_profit_for_scalping_bucket_at_target() {
        rebuildWithScalping(scalpingProps(true, 0.008));
        givenMixPosition(10_000.0, 10_081.0); // +0.81% ≥ +0.8%

        sut.checkStops();

        verify(orderClient).sell("005930", 10);
    }

    @Test
    @DisplayName("MIX 버킷이지만 목표수익 미달 → 매도 없음")
    void holds_mix_bucket_below_target() {
        rebuildWithScalping(scalpingProps(true, 0.008));
        givenMixPosition(10_000.0, 10_050.0); // +0.5% < +0.8%

        sut.checkStops();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("스캘핑 기능 OFF면 MIX 버킷이 목표수익 도달해도 익절하지 않음")
    void does_not_take_profit_when_scalping_disabled() {
        rebuildWithScalping(scalpingProps(false, 0.008));
        givenMixPosition(10_000.0, 10_100.0); // +1.0%, 목표 초과

        sut.checkStops();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("VB 버킷(레거시 null)은 목표수익 도달해도 익절 대상이 아니다 — 손절선 없으면 매도 없음")
    void does_not_take_profit_for_non_mix_bucket() {
        rebuildWithScalping(scalpingProps(true, 0.008));
        // givenArmedPosition은 bucket을 지정하지 않음 → null(레거시=VB 취급), 손절선만 있음
        givenArmedPosition(1.0, 100_000.0); // 손절선 1원이라 ATR 손절도 안 걸림

        sut.checkStops();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }
}
