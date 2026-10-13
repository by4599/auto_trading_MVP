package com.trading.risk;

import com.trading.backtest.MutableClock;
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
import com.trading.strategy.FilterProperties;
import com.trading.strategy.ScalpingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 장외 매도 판정 건너뛰기 (감사 H-1(c), 2026-10-01).
 *
 * <p>다일 보유분이 15:30을 넘기면 장외에도 신선한 잔고 스냅샷이 생긴다. 종가가 손절선·트레일선 아래면
 * 예전에는 매 초 시장가 매도 → KIS 거부 → FAILED → 다음 초 재시도가 다음 날 아침까지 반복됐다
 * (약 17시간 · 최대 약 6만 건 — 취소 5,983회 폭주(8266c0e)와 같은 모양).
 *
 * <p>건너뛰기는 "잊기"가 아니다: 손절선·최고점은 Position(DB)에 남아 <b>다음 개장 첫 틱</b>에 그대로
 * 판정된다(갭 하락 위험 — 백테스트도 갭 관통을 시가 체결로 모델링).
 */
@DisplayName("StopLossMonitor — 장외에는 매도 판정을 건너뛰고 다음 개장 첫 틱에 판정한다")
class StopLossMonitorAfterHoursTest {

    private static final String CODE = "035420";                  // NAVER — 배포일 실제 보유 종목
    private static final LocalDate THU = LocalDate.of(2026, 10, 1);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 2);
    private static final LocalDate SAT = LocalDate.of(2026, 10, 3);

    private MutableClock clock;
    private PositionRepository positionRepository;
    private PositionManager positionManager;
    private KisOrderClient orderClient;
    private StopLossMonitor sut;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.EPOCH);
        positionRepository = mock(PositionRepository.class);
        positionManager = mock(PositionManager.class);
        orderClient = mock(KisOrderClient.class);

        BucketParameterResolver resolver = trendTrailingParams();
        TradingStatusManager status = new TradingStatusManager();
        OrderEngine orderEngine = new OrderEngine(orderClient, status,
                new OrderSizingService(mock(MarketDataService.class), positionManager, new AtrCalculator(),
                        new RiskLimitsProperties(), BucketTestSupport.disabledProps(),
                        BucketTestSupport.disabledAccounts(), resolver),
                positionRepository);
        sut = new StopLossMonitor(positionRepository, mock(OrderHistoryRepository.class), positionManager,
                new RiskEngine(List.of()), orderEngine, status, configuredKis(), new TrailingStopTracker(resolver),
                new ScalpingProperties(), new MarketCalendarService(new MarketCalendarProperties(), clock));
    }

    /** A동 설정 그대로 — 트레일 arm 1% / trail 3% (BACKTEST-DESIGN §15.7 D0) */
    private static BucketParameterResolver trendTrailingParams() {
        BucketParameters params = new BucketParameters();
        BucketParameters.Overrides trend = new BucketParameters.Overrides();
        trend.setTrailingEnabled(true);
        trend.setTrailingArmProfitPct(0.01);
        trend.setTrailingTrailPct(0.03);
        params.getOverrides().put(StrategyBucket.TREND, trend);
        return new BucketParameterResolver(params, new RiskLimitsProperties(), new FilterProperties());
    }

    private static KisProperties configuredKis() {
        KisProperties kis = new KisProperties();
        kis.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        kis.setAppkey("k");
        kis.setSecretkey("s");
        kis.setAccountNo("50000000-01");
        return kis;
    }

    /** A동 6주 @200,000 — 손절선·저장된 최고점·관측가를 정한다 */
    private void holding(double stopPrice, Double persistedHigh, double current) {
        Position pos = Position.empty(CODE);
        pos.applyBuy(6, 200_000.0);
        pos.assignBucketIfAbsent(StrategyBucket.TREND);
        pos.armStopLoss(stopPrice);
        if (persistedHigh != null) pos.raiseTrailingHigh(persistedHigh);
        when(positionRepository.findByStockCode(CODE)).thenReturn(Optional.of(pos));
        when(positionManager.snapshotAccount()).thenReturn(new Account(10_000_000, 0.0, 0,
                List.of(new Account.PositionSnapshot(CODE, 6, 200_000.0, current))));
    }

    /** 마감 직후 → 관측된 오염 시각(17:17) → 자정 전 → 평일 자동 기동(08:30) → 개장 1초 전, 매 시각 한 틱씩 */
    private void tickThroughTheNight() {
        Map<LocalDate, List<LocalTime>> night = Map.of(
                THU, List.of(LocalTime.of(15, 30, 1), LocalTime.of(17, 17), LocalTime.of(23, 59, 59)),
                FRI, List.of(LocalTime.of(8, 30), LocalTime.of(8, 59, 59)));
        for (LocalDate day : List.of(THU, FRI)) {
            for (LocalTime t : night.get(day)) {
                clock.setTo(day, t);
                sut.checkStops();
            }
        }
    }

    @Test
    @DisplayName("장외 + 손절선 아래로 마감 → 밤새 주문 0건 · DB 쓰기 0건")
    void after_hours_below_stop_places_no_order() {
        holding(190_000, null, 185_000);              // 종가가 ATR 손절선 아래로 확정

        tickThroughTheNight();

        verify(orderClient, never()).sell(anyString(), anyInt());
        verify(positionRepository, never()).save(any());
    }

    @Test
    @DisplayName("장외에 손절선 아래로 마감 → 다음 날 09:00 첫 틱에 매도 1건 (잊지 않는다)")
    void closed_below_stop_sells_on_first_tick_next_open() {
        holding(190_000, null, 185_000);
        tickThroughTheNight();
        verify(orderClient, never()).sell(anyString(), anyInt());

        clock.setTo(FRI, LocalTime.of(9, 0));          // 개장 첫 틱
        sut.checkStops();

        verify(orderClient, times(1)).sell(CODE, 6);
    }

    @Test
    @DisplayName("트레일선 아래로 마감해도 같다 — 밤새 0건, 다음 날 09:00 저장된 최고점으로 매도 1건")
    void closed_below_trail_sells_on_first_tick_next_open() {
        holding(150_000, 230_000.0, 220_000);          // 220,000 ≤ 230,000×0.97 = 223,100
        tickThroughTheNight();
        verify(orderClient, never()).sell(anyString(), anyInt());

        clock.setTo(FRI, LocalTime.of(9, 0));
        sut.checkStops();

        verify(orderClient, times(1)).sell(CODE, 6);
    }

    @Test
    @DisplayName("장중에는 기존과 동일하게 매도한다")
    void in_hours_sells_as_before() {
        holding(190_000, null, 185_000);
        clock.setTo(THU, LocalTime.of(10, 0));

        sut.checkStops();

        verify(orderClient, times(1)).sell(CODE, 6);
    }

    @Test
    @DisplayName("휴장일에는 낮이어도 판정하지 않는다")
    void holiday_places_no_order() {
        holding(190_000, null, 185_000);
        clock.setTo(SAT, LocalTime.of(10, 0));

        sut.checkStops();

        verify(orderClient, never()).sell(anyString(), anyInt());
    }
}
