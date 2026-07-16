package com.trading.control;

import com.trading.NotificationService;
import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.BalanceClient;
import com.trading.position.PositionRepository;
import com.trading.position.ShadowPortfolioReconciler;
import com.trading.risk.ActualAccountInfo;
import com.trading.risk.BrokerageApiClient;
import com.trading.risk.LiquidationService;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 재가동 게이트 (OPERATIONS §6) — EMERGENCY_STOPPED → RUNNING 직접 전환 금지,
 * /resume(confirm+reason) → SAFE_MODE → /start(RUNNING) 순서만 허용.
 * 이 JVM(Java 25) Mockito 제약으로 LiquidationService/ShadowPortfolioReconciler는
 * 실객체 + 인터페이스 의존성 목킹으로 구성한다.
 */
@DisplayName("TradingController — 시작/중지/재가동 게이트")
class TradingControllerTest {

    private TradingStatusManager statusManager;
    private KisProperties kisProperties;
    private LiquidationService liquidationService;
    private BrokerageApiClient liquidationBrokerClient;
    private ShadowPortfolioReconciler reconciler;
    private BalanceClient balanceClient;
    private PositionRepository positionRepository;
    private NotificationService notifier;
    private TradingController sut;

    @BeforeEach
    void setUp() {
        statusManager = new TradingStatusManager();
        kisProperties = configuredProps();
        notifier = mock(NotificationService.class);

        liquidationBrokerClient = mock(BrokerageApiClient.class);
        when(liquidationBrokerClient.getActualAccountAsset()).thenReturn(new ActualAccountInfo(List.of()));
        liquidationService = new LiquidationService(liquidationBrokerClient, statusManager, notifier);

        balanceClient = mock(BalanceClient.class);
        when(balanceClient.fetchBalance()).thenReturn(new BalanceClient.BalanceSnapshot(0, List.of()));
        positionRepository = mock(PositionRepository.class);
        when(positionRepository.findAll()).thenReturn(List.of());
        BrokerageApiClient reconcilerBrokerClient = mock(BrokerageApiClient.class);
        reconciler = new ShadowPortfolioReconciler(statusManager, liquidationService, balanceClient,
                positionRepository, reconcilerBrokerClient,
                mock(NotificationService.class),
                new MarketCalendarService(new MarketCalendarProperties(), Clock.system(ZoneId.of("Asia/Seoul"))));

        sut = new TradingController(statusManager, kisProperties, liquidationService, reconciler, notifier);
    }

    private static KisProperties configuredProps() {
        KisProperties p = new KisProperties();
        p.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        p.setAppkey("test-key");
        p.setSecretkey("test-secret");
        p.setAccountNo("50000000-01");
        return p;
    }

    // ── /start ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RUNNING에서 /start → 그대로 RUNNING, 성공")
    void start_from_running_succeeds() {
        Map<String, Object> res = sut.start();

        assertThat(res.get("success")).isEqualTo(true);
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.RUNNING);
    }

    @Test
    @DisplayName("SAFE_MODE에서 /start → RUNNING 전환 성공 (재가동 게이트의 마지막 단계)")
    void start_from_safe_mode_succeeds() {
        statusManager.changeMode(TradingMode.SAFE_MODE);

        Map<String, Object> res = sut.start();

        assertThat(res.get("success")).isEqualTo(true);
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.RUNNING);
    }

    @Test
    @DisplayName("EMERGENCY_STOPPED에서 /start 직접 호출 → 거부 (재가동 게이트 우회 금지)")
    void start_from_emergency_stopped_is_rejected() {
        statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);

        Map<String, Object> res = sut.start();

        assertThat(res.get("success")).isEqualTo(false);
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.EMERGENCY_STOPPED);
    }

    @Test
    @DisplayName("FORCE_LIQUIDATING에서 /start 직접 호출 → 거부")
    void start_from_force_liquidating_is_rejected() {
        statusManager.changeMode(TradingMode.FORCE_LIQUIDATING);

        Map<String, Object> res = sut.start();

        assertThat(res.get("success")).isEqualTo(false);
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.FORCE_LIQUIDATING);
    }

    @Test
    @DisplayName("KIS 자격증명 미설정 → /start 거부")
    void start_rejected_when_not_configured() {
        TradingController unconfigured = new TradingController(
                statusManager, new KisProperties(), liquidationService, reconciler, notifier);

        Map<String, Object> res = unconfigured.start();

        assertThat(res.get("success")).isEqualTo(false);
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.RUNNING); // 변화 없음
    }

    // ── /stop ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("/stop → EMERGENCY_STOPPED")
    void stop_sets_emergency_stopped() {
        Map<String, Object> res = sut.stop();

        assertThat(res.get("success")).isEqualTo(true);
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.EMERGENCY_STOPPED);
    }

    // ── /resume (재가동 게이트) ──────────────────────────────────────────────────

    @Test
    @DisplayName("confirm 문자열 불일치 → 거부, 모드 변화 없음")
    void resume_rejects_wrong_confirm_string() {
        statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);

        Map<String, Object> res = sut.resume(Map.of("confirm", "WRONG", "reason", "테스트"));

        assertThat(res.get("success")).isEqualTo(false);
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.EMERGENCY_STOPPED);
    }

    @Test
    @DisplayName("reason 누락 → 거부")
    void resume_rejects_missing_reason() {
        statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);

        Map<String, Object> res = sut.resume(Map.of("confirm", "CONFIRM_RESUME"));

        assertThat(res.get("success")).isEqualTo(false);
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.EMERGENCY_STOPPED);
    }

    @Test
    @DisplayName("EMERGENCY_STOPPED가 아닌 상태에서 /resume → 거부")
    void resume_rejected_when_not_emergency_stopped() {
        // 기본 RUNNING 상태

        Map<String, Object> res = sut.resume(Map.of("confirm", "CONFIRM_RESUME", "reason", "테스트"));

        assertThat(res.get("success")).isEqualTo(false);
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.RUNNING);
    }

    @Test
    @DisplayName("정상 재가동: phase IDLE 리셋 + 재동기화 + SAFE_MODE 전환 + 알림")
    void resume_success_resets_phase_and_enters_safe_mode() throws InterruptedException {
        liquidationService.triggerForceLiquidation(); // 비동기로 EMERGENCY_STOPPED + phase=FULL_LIQUIDATING 재현
        Thread.sleep(200); // executeForceLiquidation 비동기 완료 대기 (LiquidationServiceTest와 동일 패턴)
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.EMERGENCY_STOPPED);

        Map<String, Object> res = sut.resume(Map.of("confirm", "CONFIRM_RESUME", "reason", "일일 손실 한도 도달 — 원인 확인 완료"));

        assertThat(res.get("success")).isEqualTo(true);
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.SAFE_MODE);
        assertThat(liquidationService.isAnyLiquidationInProgress()).isFalse();
        verify(notifier, atLeastOnce()).sendCritical(anyString());
    }

    @Test
    @DisplayName("재가동 후 /start를 호출해야 최종 RUNNING이 된다 (게이트 전체 시퀀스)")
    void full_gate_sequence_resume_then_start() throws InterruptedException {
        liquidationService.triggerForceLiquidation();
        Thread.sleep(200);

        sut.resume(Map.of("confirm", "CONFIRM_RESUME", "reason", "확인 완료"));
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.SAFE_MODE);

        Map<String, Object> res = sut.start();

        assertThat(res.get("success")).isEqualTo(true);
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.RUNNING);
    }

    // ── /liquidation-drill ───────────────────────────────────────────────────

    @Test
    @DisplayName("확인 문자열 불일치 → 청산 리허설 거부")
    void liquidation_drill_rejects_wrong_confirm() {
        Map<String, Object> res = sut.liquidationDrill(Map.of("confirm", "WRONG"));

        assertThat(res.get("success")).isEqualTo(false);
        assertThat(liquidationService.isAnyLiquidationInProgress()).isFalse();
    }

    @Test
    @DisplayName("확인 문자열 일치 + 자격증명 설정됨 → 청산 개시")
    void liquidation_drill_triggers_when_confirmed() {
        Map<String, Object> res = sut.liquidationDrill(Map.of("confirm", "CONFIRM_LIQUIDATE"));

        assertThat(res.get("success")).isEqualTo(true);
        assertThat(liquidationService.isAnyLiquidationInProgress()).isTrue();
    }
}
