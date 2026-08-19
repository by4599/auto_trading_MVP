package com.trading.risk;

import com.trading.NotificationService;
import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.Account;
import com.trading.position.NoOpPeakEquityCalibrator;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.PositionManager;
import com.trading.position.ShadowPortfolio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이 JVM(Java 25)의 Mockito는 구체 클래스를 목킹하지 못하므로,
 * LiquidationService·ShadowPortfolio는 실객체 + 인터페이스 의존성 목킹으로 구성한다.
 * 청산 트리거 여부는 LiquidationService의 phase(패키지 접근)로 검증한다.
 */
class RiskMonitorTest {

    private PositionManager monitorPm;          // RiskMonitor가 읽는 계좌 스냅샷
    private PositionManager shadowPm;           // ShadowPortfolio가 읽는 계좌 스냅샷 (peak 설정용)
    private ShadowPortfolio shadowPortfolio;
    private LiquidationService liquidationService;
    private TradingStatusManager statusManager;
    private NotificationService notifier;
    private RiskMonitor sut;

    @BeforeEach
    void setUp() {
        monitorPm = mock(PositionManager.class);
        shadowPm  = mock(PositionManager.class);
        // 전고점 검증은 이 테스트의 관심사가 아니므로 무검증 구현체로 조립한다 (기존 동작 그대로).
        shadowPortfolio = new ShadowPortfolio(shadowPm, mock(PortfolioStateRepository.class),
                new NoOpPeakEquityCalibrator());

        BrokerageApiClient brokerageClient = mock(BrokerageApiClient.class);
        when(brokerageClient.getActualAccountAsset()).thenReturn(new ActualAccountInfo(List.of()));
        statusManager = new TradingStatusManager(); // 기본 RUNNING
        notifier = mock(NotificationService.class);
        liquidationService = new LiquidationService(brokerageClient, statusManager, notifier);

        sut = new RiskMonitor(monitorPm, shadowPortfolio, liquidationService,
                statusManager, configuredProps(), notifier, new RiskLimitsProperties(), inHours());
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 2026-07-15(수) 평일 + 지정 시각으로 고정된 캘린더 */
    private static MarketCalendarService cal(LocalTime t) {
        Clock fixed = Clock.fixed(
                LocalDateTime.of(LocalDate.of(2026, 7, 15), t).atZone(KST).toInstant(), KST);
        return new MarketCalendarService(new MarketCalendarProperties(), fixed);
    }
    private static MarketCalendarService inHours()  { return cal(LocalTime.NOON); }        // 12:00 장중
    private static MarketCalendarService offHours() { return cal(LocalTime.of(17, 0)); }   // 17:00 장외

    private static KisProperties configuredProps() {
        KisProperties p = new KisProperties();
        p.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        p.setAppkey("test-key");
        p.setSecretkey("test-secret");
        p.setAccountNo("50000000-01");
        return p;
    }

    private static Account account(double totalAsset, double dailyPnl) {
        return new Account(totalAsset, dailyPnl, 0, List.of());
    }

    /** ShadowPortfolio의 peakEquity를 원하는 값으로 세팅한다 (실객체 tick 경유) */
    private void setPeakEquity(double peak) {
        when(shadowPm.snapshotAccount()).thenReturn(account(peak, 0.0));
        shadowPortfolio.tick();
        assertThat(shadowPortfolio.getPeakEquity()).isEqualTo(peak);
    }

    // ── 일일 손실 ─────────────────────────────────────────────────────────────

    @Test
    void daily_loss_minus_4_percent_does_not_trigger() {
        when(monitorPm.snapshotAccount()).thenReturn(account(48_000_000, -0.04));

        sut.monitor();

        assertThat(liquidationService.currentPhase()).isEqualTo(LiquidationPhase.IDLE);
    }

    @Test
    void daily_loss_minus_5_percent_triggers_liquidation() {
        when(monitorPm.snapshotAccount()).thenReturn(account(47_500_000, -0.05));

        sut.monitor();

        assertThat(liquidationService.currentPhase()).isEqualTo(LiquidationPhase.FULL_LIQUIDATING);
        verify(notifier, atLeastOnce()).sendCritical(anyString());
    }

    // ── MDD ───────────────────────────────────────────────────────────────────

    @Test
    void mdd_over_10_percent_triggers_liquidation() {
        setPeakEquity(50_000_000);
        // peak 5,000만 → 현재 4,490만 = MDD 10.2%
        when(monitorPm.snapshotAccount()).thenReturn(account(44_900_000, -0.01));

        sut.monitor();

        assertThat(liquidationService.currentPhase()).isEqualTo(LiquidationPhase.FULL_LIQUIDATING);
    }

    @Test
    void mdd_under_10_percent_does_not_trigger() {
        setPeakEquity(50_000_000);
        // peak 5,000만 → 현재 4,550만 = MDD 9.0%
        when(monitorPm.snapshotAccount()).thenReturn(account(45_500_000, -0.01));

        sut.monitor();

        assertThat(liquidationService.currentPhase()).isEqualTo(LiquidationPhase.IDLE);
    }

    // ── 가드 조건 ─────────────────────────────────────────────────────────────

    @Test
    void skips_when_liquidation_already_in_progress() {
        liquidationService.triggerForceLiquidation(); // phase → FULL_LIQUIDATING

        sut.monitor();

        verify(monitorPm, never()).snapshotAccount();
    }

    @Test
    void skips_when_not_running() {
        statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);

        sut.monitor();

        verify(monitorPm, never()).snapshotAccount();
    }

    @Test
    void safe_mode_still_monitors_and_can_trigger_liquidation() {
        statusManager.changeMode(TradingMode.SAFE_MODE);
        when(monitorPm.snapshotAccount()).thenReturn(account(47_500_000, -0.05));

        sut.monitor();

        assertThat(liquidationService.currentPhase()).isEqualTo(LiquidationPhase.FULL_LIQUIDATING);
    }

    @Test
    void skips_when_credentials_not_configured() {
        RiskMonitor unconfigured = new RiskMonitor(monitorPm, shadowPortfolio,
                liquidationService, statusManager, new KisProperties(), notifier,
                new RiskLimitsProperties(), inHours());

        unconfigured.monitor();

        verify(monitorPm, never()).snapshotAccount();
    }

    @Test
    void off_market_hours_skips_monitoring() {
        RiskMonitor offHoursMonitor = new RiskMonitor(monitorPm, shadowPortfolio,
                liquidationService, statusManager, configuredProps(), notifier,
                new RiskLimitsProperties(), offHours());

        offHoursMonitor.monitor();

        // 장외에는 스냅샷 조회도, 청산 판정도 하지 않는다
        verify(monitorPm, never()).snapshotAccount();
        assertThat(liquidationService.currentPhase()).isEqualTo(LiquidationPhase.IDLE);
    }

    @Test
    void stale_snapshot_does_not_trigger_liquidation() {
        // -5% 손실이지만 잔고 API 실패로 낡은(폴백) 스냅샷 → 청산 트리거 금지 (2026-07-30 오판 방지)
        when(monitorPm.snapshotAccount()).thenReturn(account(47_500_000, -0.05).asStale());

        sut.monitor();

        assertThat(liquidationService.currentPhase()).isEqualTo(LiquidationPhase.IDLE);
        verify(notifier, never()).sendCritical(anyString());
    }

    @Test
    void zero_total_asset_is_not_judged_as_mdd() {
        setPeakEquity(50_000_000);
        // 폴백 스냅샷(총자산 0) — 기존 F-1 오탐 시나리오. 판정 자체를 건너뛰어야 한다.
        when(monitorPm.snapshotAccount()).thenReturn(account(0, 0.0));

        sut.monitor();

        assertThat(liquidationService.currentPhase()).isEqualTo(LiquidationPhase.IDLE);
    }

    @Test
    void snapshot_failure_skips_tick_without_exception() {
        when(monitorPm.snapshotAccount()).thenThrow(new IllegalStateException("KIS 장애"));

        sut.monitor(); // 예외가 밖으로 새면 테스트 실패

        assertThat(liquidationService.currentPhase()).isEqualTo(LiquidationPhase.IDLE);
    }
}
