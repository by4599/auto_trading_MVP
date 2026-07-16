package com.trading.position;

import com.trading.NotificationService;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.risk.ActualAccountInfo;
import com.trading.risk.BrokerageApiClient;
import com.trading.risk.LiquidationService;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OPERATIONS §3(기동 재동기화) + §5.3(평시 감시)는 서로 다른 보정 정책을 쓴다:
 * 기동 시엔 브로커 기준 자동 보정, 평시 10분 주기는 감지+알림만 (코퍼레이트 액션 가능성).
 */
@DisplayName("ShadowPortfolioReconciler — 기동 재동기화 / 평시 감시")
class ShadowPortfolioReconcilerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private TradingStatusManager statusManager;
    private LiquidationService liquidationService;
    private BalanceClient balanceClient;
    private PositionRepository positionRepository;
    private BrokerageApiClient brokerageClient;
    private NotificationService notifier;

    @BeforeEach
    void setUp() {
        statusManager = new TradingStatusManager();
        BrokerageApiClient liquidationBrokerClient = mock(BrokerageApiClient.class);
        when(liquidationBrokerClient.getActualAccountAsset()).thenReturn(new ActualAccountInfo(List.of()));
        liquidationService = new LiquidationService(liquidationBrokerClient, statusManager, mock(NotificationService.class));

        balanceClient = mock(BalanceClient.class);
        positionRepository = mock(PositionRepository.class);
        brokerageClient = mock(BrokerageApiClient.class);
        notifier = mock(NotificationService.class);
    }

    private ShadowPortfolioReconciler sut(Clock clock) {
        MarketCalendarService marketCalendarService =
                new MarketCalendarService(new MarketCalendarProperties(), clock);
        return new ShadowPortfolioReconciler(statusManager, liquidationService,
                balanceClient, positionRepository, brokerageClient, notifier, marketCalendarService);
    }

    private ShadowPortfolioReconciler sutAtNoon() {
        // 2026-07-08(수) 12:00 KST — 장중
        return sut(Clock.fixed(LocalDateTime.of(2026, 7, 8, 12, 0).atZone(KST).toInstant(), KST));
    }

    private ShadowPortfolioReconciler sutAtNight() {
        // 2026-07-08(수) 22:00 KST — 장외
        return sut(Clock.fixed(LocalDateTime.of(2026, 7, 8, 22, 0).atZone(KST).toInstant(), KST));
    }

    private static Position dbHolding(String code, int qty, double avg) {
        Position p = Position.empty(code);
        p.applyBuy(qty, avg);
        return p;
    }

    // ── 평시 10분 주기 — 감지만, 자동 보정 없음 ─────────────────────────────────

    @Test
    @DisplayName("불일치 없음 → 알림 없음, DB 쓰기 없음")
    void reconcile_no_mismatch_no_alert() {
        when(positionRepository.findAll()).thenReturn(List.of(dbHolding("005930", 10, 70_000)));
        when(balanceClient.fetchBalance()).thenReturn(
                new BalanceClient.BalanceSnapshot(1_000_000,
                        List.of(new BalanceClient.Holding("005930", 10, 70_000, 71_000))));

        sutAtNoon().reconcile();

        verify(notifier, never()).sendCritical(anyString());
        verify(positionRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(positionRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("수량 불일치 감지 → 알림만 발송, DB는 그대로 (코퍼레이트 액션 가능성)")
    void reconcile_mismatch_alerts_without_correcting() {
        when(positionRepository.findAll()).thenReturn(List.of(dbHolding("005930", 10, 70_000)));
        when(balanceClient.fetchBalance()).thenReturn(
                new BalanceClient.BalanceSnapshot(1_000_000,
                        List.of(new BalanceClient.Holding("005930", 5, 70_000, 71_000))));

        sutAtNoon().reconcile();

        verify(notifier).sendCritical(anyString());
        verify(positionRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(positionRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("EMERGENCY_STOPPED → 평시 감시 스킵")
    void reconcile_skips_when_emergency_stopped() {
        statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);

        sutAtNoon().reconcile();

        verify(balanceClient, never()).fetchBalance();
    }

    @Test
    @DisplayName("청산 진행 중 → 평시 감시 스킵")
    void reconcile_skips_during_liquidation() {
        liquidationService.triggerForceLiquidation();

        sutAtNoon().reconcile();

        verify(balanceClient, never()).fetchBalance();
    }

    // ── 기동 재동기화 — 브로커 기준 자동 보정 ────────────────────────────────────

    @Test
    @DisplayName("기동 시 미체결 주문 전량 취소를 항상 호출한다")
    void onStartup_always_cancels_pending_orders() {
        when(positionRepository.findAll()).thenReturn(List.of());
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(0, List.of()));

        sutAtNoon().onStartup();

        verify(brokerageClient).cancelAllPendingOrders();
    }

    @Test
    @DisplayName("DB에는 있는데 브로커엔 없음 → 삭제 + 보정 알림")
    void onStartup_deletes_position_absent_from_broker() {
        Position stale = dbHolding("005930", 10, 70_000);
        when(positionRepository.findAll()).thenReturn(List.of(stale));
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(0, List.of()));

        sutAtNight().onStartup(); // 장외라 SAFE_MODE 알림과 섞이지 않게

        verify(positionRepository).delete(stale);
        verify(notifier).sendCritical(anyString());
    }

    @Test
    @DisplayName("브로커에는 있는데 DB엔 없음 → 신규 Position 생성 + 보정 알림")
    void onStartup_creates_position_missing_from_db() {
        when(positionRepository.findAll()).thenReturn(List.of());
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(1_000_000,
                List.of(new BalanceClient.Holding("005930", 7, 68_000, 69_000))));

        sutAtNight().onStartup();

        verify(positionRepository).save(org.mockito.ArgumentMatchers.argThat(
                p -> p.getStockCode().equals("005930") && p.getQuantity() == 7 && p.getAveragePrice() == 68_000));
        verify(notifier).sendCritical(anyString());
    }

    @Test
    @DisplayName("수량 불일치 → 브로커 값으로 덮어쓰기 + 보정 알림")
    void onStartup_corrects_quantity_mismatch() {
        Position drifted = dbHolding("005930", 10, 70_000);
        when(positionRepository.findAll()).thenReturn(List.of(drifted));
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(1_000_000,
                List.of(new BalanceClient.Holding("005930", 6, 70_000, 71_000))));

        sutAtNight().onStartup();

        assertThat(drifted.getQuantity()).isEqualTo(6);
        verify(positionRepository).save(drifted);
        verify(notifier).sendCritical(anyString());
    }

    @Test
    @DisplayName("장중 재기동 + RUNNING → SAFE_MODE로 전환하고 알림")
    void onStartup_during_market_hours_enters_safe_mode() {
        when(positionRepository.findAll()).thenReturn(List.of());
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(0, List.of()));

        sutAtNoon().onStartup();

        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.SAFE_MODE);
        verify(notifier, times(1)).sendCritical(anyString());
    }

    @Test
    @DisplayName("장외 재기동 → RUNNING 유지 (SAFE_MODE로 승격하지 않음)")
    void onStartup_outside_market_hours_stays_running() {
        when(positionRepository.findAll()).thenReturn(List.of());
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(0, List.of()));

        sutAtNight().onStartup();

        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.RUNNING);
    }

    @Test
    @DisplayName("EMERGENCY_STOPPED로 기동 → 재동기화는 수행하되 SAFE_MODE로 승격하지 않는다")
    void onStartup_from_emergency_stopped_does_not_promote_to_safe_mode() {
        statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);
        when(positionRepository.findAll()).thenReturn(List.of());
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(0, List.of()));

        sutAtNoon().onStartup();

        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.EMERGENCY_STOPPED);
        verify(brokerageClient).cancelAllPendingOrders();
        verify(balanceClient).fetchBalance();
    }

    // ── 재가동 게이트 재사용 대상 메서드 ─────────────────────────────────────────

    @Test
    @DisplayName("correctFromBroker()는 재가동 게이트에서 재사용 가능한 public 메서드다")
    void correctFromBroker_is_reusable_standalone() {
        when(positionRepository.findAll()).thenReturn(List.of());
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(1_000_000,
                List.of(new BalanceClient.Holding("005930", 3, 60_000, 61_000))));

        sutAtNoon().correctFromBroker();

        verify(positionRepository).save(org.mockito.ArgumentMatchers.any());
        verify(brokerageClient, never()).cancelAllPendingOrders(); // onStartup 경로가 아니므로 취소는 호출 안 됨
    }
}
