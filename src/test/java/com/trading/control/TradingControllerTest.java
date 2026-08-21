package com.trading.control;

import com.trading.NotificationService;
import com.trading.bucket.BucketAccountService;
import com.trading.bucket.BucketProperties;
import com.trading.bucket.StrategyBucket;
import com.trading.market.AtrCalculator;
import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.order.KisOrderClient;
import com.trading.order.OrderEngine;
import com.trading.order.OrderSizingService;
import com.trading.position.Account;
import com.trading.position.BalanceClient;
import com.trading.position.NoOpPeakEquityCalibrator;
import com.trading.position.PortfolioState;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.position.ShadowPortfolio;
import com.trading.position.ShadowPortfolioReconciler;
import com.trading.position.TradeResultRepository;
import com.trading.risk.ActualAccountInfo;
import com.trading.risk.BrokerageApiClient;
import com.trading.risk.LiquidationService;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskLimitsProperties;
import com.trading.risk.RiskResult;
import com.trading.risk.RiskRule;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private PortfolioStateRepository portfolioStateRepository;
    private NotificationService notifier;
    private KisOrderClient orderClient;
    private PositionManager positionManager;
    private OrderEngine orderEngine;
    private MockEnvironment paperEnvironment;
    private ShadowPortfolio shadowPortfolio;
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
                positionRepository, reconcilerBrokerClient, mock(NotificationService.class),
                new MarketCalendarService(new MarketCalendarProperties(), java.time.Clock.systemDefaultZone()),
                new com.trading.risk.StopLossArmer(mock(com.trading.market.MarketDataService.class),
                        new com.trading.market.AtrCalculator(), positionRepository,
                        com.trading.bucket.BucketTestSupport.defaultParams()));

        portfolioStateRepository = mock(PortfolioStateRepository.class);

        orderClient = mock(KisOrderClient.class);
        positionManager = mock(PositionManager.class);
        when(positionManager.snapshotAccount()).thenReturn(new Account(10_000_000, 0.0, 0, List.of()));
        orderEngine = newOrderEngine();
        paperEnvironment = new MockEnvironment();
        paperEnvironment.setActiveProfiles("paper");
        // 전고점 소유자 — 미검증 표시 해제(peak-equity-ack) 경로에 필요. 실객체 + 인터페이스 목.
        shadowPortfolio = new ShadowPortfolio(positionManager, portfolioStateRepository,
                new NoOpPeakEquityCalibrator());

        sut = newController(passingRiskEngine(), paperEnvironment);
    }

    /** RiskEngine·OrderEngine은 구체 클래스 — 인터페이스 의존성만 목킹해 실객체로 조립한다 */
    private OrderEngine newOrderEngine() {
        BucketProperties bucketProps = new BucketProperties(
                false, "2026-07-20", 10_000_000, 10_000_000, 10_000_000, 10_000_000, false, false, false);
        OrderSizingService sizing = new OrderSizingService(
                mock(com.trading.market.MarketDataService.class), positionManager, new AtrCalculator(),
                new RiskLimitsProperties(), bucketProps,
                new BucketAccountService(bucketProps, positionRepository, mock(TradeResultRepository.class)),
                com.trading.bucket.BucketTestSupport.defaultParams());
        return new OrderEngine(orderClient, statusManager, sizing, positionRepository);
    }

    private static RiskEngine passingRiskEngine() {
        return new RiskEngine(List.of());
    }

    private static RiskEngine rejectingRiskEngine(String reason) {
        return new RiskEngine(List.<RiskRule>of((signal, account) -> RiskResult.reject(reason)));
    }

    private TradingController newController(RiskEngine riskEngine, Environment environment) {
        return newController(riskEngine, environment, kisProperties);
    }

    /** 리허설 판정은 DrillService가 한다 — 컨트롤러는 확인 문자열만 보고 넘긴다 */
    private TradingController newController(RiskEngine riskEngine, Environment environment,
                                            KisProperties props) {
        DrillOperations drill = new DrillService(props, statusManager, riskEngine, orderEngine,
                positionManager, positionRepository, liquidationService, environment);
        return new TradingController(statusManager, props, liquidationService, reconciler, notifier,
                portfolioStateRepository, drill, shadowPortfolio);
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
        TradingController unconfigured =
                newController(passingRiskEngine(), paperEnvironment, new KisProperties());

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

    // ── /manual-buy-drill (리허설용 고정 수량 매수) ─────────────────────────────

    @Test
    @DisplayName("확인 문자열 불일치 → 수동 매수 거부, 주문 없음")
    void manual_buy_drill_rejects_wrong_confirm() {
        Map<String, Object> res = sut.manualBuyDrill(Map.of("confirm", "WRONG"));

        assertThat(res.get("success")).isEqualTo(false);
        verify(orderClient, never()).buy(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("paper 프로필이 아니면 거부 (실전 오발동 차단)")
    void manual_buy_drill_rejected_outside_paper_profile() {
        MockEnvironment realEnv = new MockEnvironment();
        realEnv.setActiveProfiles("real");
        TradingController realController = newController(passingRiskEngine(), realEnv);

        Map<String, Object> res = realController.manualBuyDrill(Map.of("confirm", "CONFIRM_MANUAL_BUY"));

        assertThat(res.get("success")).isEqualTo(false);
        assertThat(String.valueOf(res.get("message"))).contains("paper");
        verify(orderClient, never()).buy(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("KIS 자격증명 미설정 → 거부")
    void manual_buy_drill_rejected_when_not_configured() {
        TradingController unconfigured =
                newController(passingRiskEngine(), paperEnvironment, new KisProperties());

        Map<String, Object> res = unconfigured.manualBuyDrill(Map.of("confirm", "CONFIRM_MANUAL_BUY"));

        assertThat(res.get("success")).isEqualTo(false);
        verify(orderClient, never()).buy(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("RUNNING이 아니면 거부 (SAFE_MODE는 신규 매수 금지)")
    void manual_buy_drill_rejected_when_not_running() {
        statusManager.changeMode(TradingMode.SAFE_MODE);

        Map<String, Object> res = sut.manualBuyDrill(Map.of("confirm", "CONFIRM_MANUAL_BUY"));

        assertThat(res.get("success")).isEqualTo(false);
        verify(orderClient, never()).buy(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("리스크 룰이 막으면 그 사유를 그대로 돌려주고 주문하지 않는다")
    void manual_buy_drill_reports_risk_rejection_reason() {
        TradingController blocked = newController(rejectingRiskEngine("칸 예산 소진"), paperEnvironment);

        Map<String, Object> res = blocked.manualBuyDrill(Map.of("confirm", "CONFIRM_MANUAL_BUY"));

        assertThat(res.get("success")).isEqualTo(false);
        assertThat(String.valueOf(res.get("message"))).contains("칸 예산 소진");
        verify(orderClient, never()).buy(anyString(), anyInt(), any());
    }

    // ── /peak-equity-ack (전고점 미검증 표시 해제) ─────────────────────────────

    @Test
    @DisplayName("확인 문자열 불일치 → 미검증 표시 해제 거부")
    void peak_equity_ack_rejects_wrong_confirm() {
        Map<String, Object> res = sut.acknowledgePeakEquity(Map.of("confirm", "WRONG"));

        assertThat(res.get("success")).isEqualTo(false);
        verify(notifier, never()).sendCritical(anyString());
    }

    @Test
    @DisplayName("보류 중인 표시가 없으면 해제할 것도 없다고 답한다 (오해 방지)")
    void peak_equity_ack_reports_nothing_to_release() {
        // 미검증 표시가 저장돼 있지 않은 상태 (portfolioStateRepository 목 기본값 = 빈 Optional)
        Map<String, Object> res = sut.acknowledgePeakEquity(Map.of("confirm", "CONFIRM_PEAK_EQUITY"));

        assertThat(res.get("success")).isEqualTo(false);
        assertThat(String.valueOf(res.get("message"))).contains("보류 중인");
        verify(portfolioStateRepository, never())
                .save(argThat(s -> PortfolioState.KEY_PEAK_EQUITY_UNVERIFIED.equals(s.getStateKey())));
    }

    @Test
    @DisplayName("정상: 005930 정확히 10주 시장가 매수 접수")
    void manual_buy_drill_orders_exactly_ten_shares() {
        Map<String, Object> res = sut.manualBuyDrill(Map.of("confirm", "CONFIRM_MANUAL_BUY"));

        assertThat(res.get("success")).isEqualTo(true);
        verify(orderClient).buy("005930", 10, StrategyBucket.VB);
    }
}
