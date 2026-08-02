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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기동 재동기화(OPERATIONS §3)와 평시 감시(§5.3)는 서로 다른 보정 정책을 쓴다:
 * 기동 시엔 브로커 기준 자동 보정 후 곧바로 RUNNING(자동 가동), 평시 10분 주기는
 * 감지+알림만 (코퍼레이트 액션 가능성).
 */
@DisplayName("ShadowPortfolioReconciler — 기동 재동기화 / 평시 감시")
class ShadowPortfolioReconcilerTest {

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

    private ShadowPortfolioReconciler sut() {
        return sut(inHours());
    }

    private ShadowPortfolioReconciler sut(MarketCalendarService cal) {
        return new ShadowPortfolioReconciler(statusManager, liquidationService,
                balanceClient, positionRepository, brokerageClient, notifier, cal);
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-07-15(수) 평일 + 지정 시각 고정 캘린더 */
    private static MarketCalendarService cal(LocalTime t) {
        Clock fixed = Clock.fixed(
                LocalDateTime.of(LocalDate.of(2026, 7, 15), t).atZone(KST).toInstant(), KST);
        return new MarketCalendarService(new MarketCalendarProperties(), fixed);
    }
    private static MarketCalendarService inHours()  { return cal(LocalTime.NOON); }
    private static MarketCalendarService offHours() { return cal(LocalTime.of(17, 0)); }

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

        sut().reconcile();

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

        sut().reconcile();

        verify(notifier).sendCritical(anyString());
        verify(positionRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(positionRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("EMERGENCY_STOPPED → 평시 감시 스킵")
    void reconcile_skips_when_emergency_stopped() {
        statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);

        sut().reconcile();

        verify(balanceClient, never()).fetchBalance();
    }

    @Test
    @DisplayName("청산 진행 중 → 평시 감시 스킵")
    void reconcile_skips_during_liquidation() {
        liquidationService.triggerForceLiquidation();

        sut().reconcile();

        verify(balanceClient, never()).fetchBalance();
    }

    @Test
    @DisplayName("불일치가 2주기 연속 지속 → 브로커 기준 자동 보정")
    void reconcile_corrects_after_mismatch_persists_two_cycles() {
        Position drifted = dbHolding("005930", 10, 70_000);
        when(positionRepository.findAll()).thenReturn(List.of(drifted));
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(1_000_000,
                List.of(new BalanceClient.Holding("005930", 5, 70_000, 71_000))));

        ShadowPortfolioReconciler r = sut();
        r.reconcile();  // 1차 — 알림만, 보정 없음
        verify(positionRepository, never()).save(org.mockito.ArgumentMatchers.any());

        r.reconcile();  // 2차 — 2주기 지속 → 자동 보정
        assertThat(drifted.getQuantity()).isEqualTo(5);
        verify(positionRepository).save(drifted);
    }

    @Test
    @DisplayName("불일치가 다음 주기에 사라지면 → 자동 보정 안 함 (일시 오류 보호)")
    void reconcile_transient_mismatch_not_corrected() {
        Position pos = dbHolding("005930", 10, 70_000);
        when(positionRepository.findAll()).thenReturn(List.of(pos));
        when(balanceClient.fetchBalance()).thenReturn(
                new BalanceClient.BalanceSnapshot(1_000_000,
                        List.of(new BalanceClient.Holding("005930", 5, 70_000, 71_000))),   // 1차: 불일치
                new BalanceClient.BalanceSnapshot(1_000_000,
                        List.of(new BalanceClient.Holding("005930", 10, 70_000, 71_000))));  // 2차: 일치

        ShadowPortfolioReconciler r = sut();
        r.reconcile();  // 불일치 감지 — 알림만
        r.reconcile();  // 일치 회복 — previousMismatchStocks 클리어, 보정 없음

        verify(positionRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(positionRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("장 시간 외 → 재동기화 자체를 건너뜀 (낡은 데이터 배제)")
    void reconcile_skips_outside_market_hours() {
        sut(offHours()).reconcile();

        verify(balanceClient, never()).fetchBalance();
    }

    // ── 기동 재동기화 — 브로커 기준 자동 보정 후 자동 가동(RUNNING) ────────────────

    @Test
    @DisplayName("기동 시 미체결 주문 전량 취소를 항상 호출한다")
    void onStartup_always_cancels_pending_orders() {
        when(positionRepository.findAll()).thenReturn(List.of());
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(0, List.of()));

        sut().onStartup();

        verify(brokerageClient).cancelAllPendingOrders();
    }

    @Test
    @DisplayName("DB에는 있는데 브로커엔 없음 → 삭제 + 보정 알림")
    void onStartup_deletes_position_absent_from_broker() {
        Position stale = dbHolding("005930", 10, 70_000);
        when(positionRepository.findAll()).thenReturn(List.of(stale));
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(0, List.of()));

        sut().onStartup();

        verify(positionRepository).delete(stale);
        verify(notifier).sendCritical(anyString());
    }

    @Test
    @DisplayName("브로커에는 있는데 DB엔 없음 → 신규 Position 생성 + 보정 알림")
    void onStartup_creates_position_missing_from_db() {
        when(positionRepository.findAll()).thenReturn(List.of());
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(1_000_000,
                List.of(new BalanceClient.Holding("005930", 7, 68_000, 69_000))));

        sut().onStartup();

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

        sut().onStartup();

        assertThat(drifted.getQuantity()).isEqualTo(6);
        verify(positionRepository).save(drifted);
        verify(notifier).sendCritical(anyString());
    }

    @Test
    @DisplayName("기동 후 자동 가동 — 항상 RUNNING으로 시작 (사용자 정책 2026-07-16)")
    void onStartup_always_ends_running() {
        when(positionRepository.findAll()).thenReturn(List.of());
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(0, List.of()));

        sut().onStartup();

        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.RUNNING);
    }

    // ── 재가동 게이트 재사용 대상 메서드 ─────────────────────────────────────────

    @Test
    @DisplayName("correctFromBroker()는 재가동 게이트에서 재사용 가능한 public 메서드다")
    void correctFromBroker_is_reusable_standalone() {
        when(positionRepository.findAll()).thenReturn(List.of());
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(1_000_000,
                List.of(new BalanceClient.Holding("005930", 3, 60_000, 61_000))));

        sut().correctFromBroker();

        verify(positionRepository).save(org.mockito.ArgumentMatchers.any());
        verify(brokerageClient, never()).cancelAllPendingOrders(); // onStartup 경로가 아니므로 취소는 호출 안 됨
    }
}
