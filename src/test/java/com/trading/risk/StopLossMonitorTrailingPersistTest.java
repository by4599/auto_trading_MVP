package com.trading.risk;

import com.trading.bucket.BucketParameterResolver;
import com.trading.bucket.BucketParameters;
import com.trading.bucket.BucketTestSupport;
import com.trading.bucket.StrategyBucket;
import com.trading.market.AtrCalculator;
import com.trading.market.KisProperties;
import com.trading.market.MarketDataService;
import com.trading.order.KisOrderClient;
import com.trading.order.OrderEngine;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSizingService;
import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.strategy.FilterProperties;
import com.trading.strategy.ScalpingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 트레일링 최고점 영속화 (2026-10-01) — 며칠 들고 가는 A동은 앱이 재시작돼도(평일 08:30 자동 기동,
 * PC 수면·재부팅 — 09-23 실제 발생) 진입 이후 최고점을 잊으면 안 된다. 잊으면 현재가로 다시 잡혀
 * 트레일 손절선이 느슨해진다. 백테스트 §14가 "트레일링이 핵심"이라고 결론낸 바로 그 부품이다.
 *
 * <p>A동 설정 그대로: 트레일 arm 1% / trail 3% (BACKTEST-DESIGN §15.7 D0, P3 출구).
 */
@DisplayName("StopLossMonitor — 트레일링 최고점 영속화 (재시작 복원)")
class StopLossMonitorTrailingPersistTest {

    private static final String CODE = "005930";

    private PositionRepository positionRepository;
    private PositionManager positionManager;
    private KisOrderClient orderClient;
    private BucketParameterResolver resolver;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        positionManager = mock(PositionManager.class);
        orderClient = mock(KisOrderClient.class);

        BucketParameters params = new BucketParameters();
        BucketParameters.Overrides trend = new BucketParameters.Overrides();
        trend.setTrailingEnabled(true);
        trend.setTrailingArmProfitPct(0.01);
        trend.setTrailingTrailPct(0.03);
        params.getOverrides().put(StrategyBucket.TREND, trend);
        resolver = new BucketParameterResolver(params, new RiskLimitsProperties(), new FilterProperties());
    }

    /** 앱을 새로 띄운 것과 같다 — 트래커 메모리가 비어 있는 새 감시기 */
    private StopLossMonitor freshMonitor(TrailingStopTracker tracker) {
        KisProperties kis = new KisProperties();
        kis.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        kis.setAppkey("k");
        kis.setSecretkey("s");
        kis.setAccountNo("50000000-01");
        TradingStatusManager status = new TradingStatusManager();
        OrderEngine orderEngine = new OrderEngine(orderClient, status,
                new OrderSizingService(mock(MarketDataService.class), positionManager, new AtrCalculator(),
                        new RiskLimitsProperties(), BucketTestSupport.disabledProps(),
                        BucketTestSupport.disabledAccounts(), resolver),
                positionRepository);
        return new StopLossMonitor(positionRepository, mock(OrderHistoryRepository.class), positionManager,
                new RiskEngine(List.of()), orderEngine, status, kis, tracker, new ScalpingProperties(),
                StopLossMonitorTest.inHours());
    }

    /** A동 10주 @100, ATR 손절선 90 — 손절선은 이 테스트들에서 걸리지 않게 멀리 둔다 */
    private Position trendPosition() {
        Position pos = Position.empty(CODE);
        pos.applyBuy(10, 100.0);
        pos.assignBucketIfAbsent(StrategyBucket.TREND);
        pos.armStopLoss(90.0);
        when(positionRepository.findByStockCode(CODE)).thenReturn(Optional.of(pos));
        return pos;
    }

    private void priceIs(double current) {
        when(positionManager.snapshotAccount()).thenReturn(new Account(10_000_000, 0.0, 0,
                List.of(new Account.PositionSnapshot(CODE, 10, 100.0, current))));
    }

    @Test
    @DisplayName("재시작 후에도 저장된 최고점(110)으로 트레일 — 106 ≤ 110×0.97=106.7 이므로 매도")
    void restores_persisted_high_after_restart() {
        Position pos = trendPosition();
        pos.raiseTrailingHigh(110.0);            // 재시작 전 장중에 기록된 고점
        priceIs(106.0);                           // 새 메모리로는 106이 고점이라 손절선 102.8 → 안 판다

        freshMonitor(new TrailingStopTracker(resolver)).checkStops();

        verify(orderClient).sell(CODE, 10);
    }

    @Test
    @DisplayName("최고점은 오를 때만 저장한다 (쓰기 최소화) — 내려가면 그대로")
    void persists_only_when_high_rises() {
        Position pos = trendPosition();
        StopLossMonitor monitor = freshMonitor(new TrailingStopTracker(resolver));

        priceIs(103.0);
        monitor.checkStops();
        priceIs(102.0);
        monitor.checkStops();
        assertThat(pos.getTrailingHigh()).isEqualTo(103.0);
        verify(positionRepository, times(1)).save(pos);

        priceIs(105.0);
        monitor.checkStops();
        assertThat(pos.getTrailingHigh()).isEqualTo(105.0);
        verify(positionRepository, times(2)).save(pos);
    }

    @Test
    @DisplayName("최고점을 모르면(브로커 보정·배포 전 보유분) 지금 관측가에서 시작 — 앞당겨 팔지 않는다")
    void unknown_high_starts_from_first_observation() {
        Position pos = trendPosition();           // trailingHigh = null
        priceIs(120.0);                           // 진입가 대비 +20%

        freshMonitor(new TrailingStopTracker(resolver)).checkStops();

        assertThat(pos.getTrailingHigh()).isEqualTo(120.0);   // 120 > 120×0.97 → 매도 없음
        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("앞 라운드트립의 메모리 고점(200)이 새 진입을 오염시키지 않는다 — DB 값이 정본")
    void stale_memory_high_does_not_leak_into_new_round_trip() {
        TrailingStopTracker tracker = new TrailingStopTracker(resolver);
        tracker.updateHigh(CODE, 200.0);          // 타임컷·대사로 끝난 옛 포지션 — clear가 안 불렸다
        trendPosition();                          // 새 진입 @100, 저장된 고점 없음
        priceIs(100.0);

        freshMonitor(tracker).checkStops();

        // 옛 방식: 고점 max(200,100)=200 → 무장 → 손절선 194 → 진입 직후 즉시 매도
        verify(orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("최고점 저장이 실패해도(@Version 충돌·DB 잠금) 그 틱의 ATR 손절은 판정된다 — 감사 M-3")
    void atr_stop_still_judged_when_high_save_fails() {
        Position pos = trendPosition();           // @100, ATR 손절선 90, 저장된 고점 없음
        when(positionRepository.save(any(Position.class)))
                .thenThrow(new OptimisticLockingFailureException("@Version 충돌"));
        priceIs(89.0);                            // 첫 관측 → 고점 89 저장 시도(실패) → 89 ≤ 90

        freshMonitor(new TrailingStopTracker(resolver)).checkStops();

        // 예전: 저장 예외가 종목 루프의 catch로 빠져 ATR 판정 자체가 없었다
        verify(orderClient).sell(CODE, 10);
        assertThat(pos.getTrailingHigh()).isEqualTo(89.0);   // 판정은 메모리 값으로 계속됐다
    }
}
