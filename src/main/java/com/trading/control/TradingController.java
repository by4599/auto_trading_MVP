package com.trading.control;

import com.trading.NotificationService;
import com.trading.market.KisProperties;
import com.trading.position.PortfolioState;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.ShadowPortfolio;
import com.trading.position.ShadowPortfolioReconciler;
import com.trading.risk.LiquidationService;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import com.trading.scheduler.RunStreakRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 거래 수동 제어 API.
 * UI에서 거래 시작/중지 버튼이 이 엔드포인트를 호출한다.
 * 자동 전략 루프(TradingScheduler)는 TradingStatusManager.getCurrentMode()를
 * 매 틱마다 읽어 EMERGENCY_STOPPED면 진입 자체를 건너뛴다.
 */
// ShadowPortfolioReconciler(@Profile("paper"))에 의존 — backtest 프로필(웹 서버 없음,
// web-application-type: none)에는 애초에 필요 없는 빈이라 아예 제외한다.
@RestController
@RequestMapping("/api/trading")
@Profile("!backtest")
public class TradingController {

    private static final Logger log = LoggerFactory.getLogger(TradingController.class);
    private static final String RESUME_CONFIRM = "CONFIRM_RESUME";
    private static final String MANUAL_BUY_CONFIRM = "CONFIRM_MANUAL_BUY";
    private static final String PEAK_ACK_CONFIRM = "CONFIRM_PEAK_EQUITY";

    private final TradingStatusManager statusManager;
    private final KisProperties        kisProperties;
    private final LiquidationService   liquidationService;
    private final ShadowPortfolioReconciler reconciler;
    private final NotificationService  notifier;
    private final PortfolioStateRepository portfolioStateRepository;
    private final DrillOperations      drill;
    private final ShadowPortfolio      shadowPortfolio;

    public TradingController(TradingStatusManager statusManager,
                              KisProperties        kisProperties,
                              LiquidationService   liquidationService,
                              ShadowPortfolioReconciler reconciler,
                              NotificationService  notifier,
                              PortfolioStateRepository portfolioStateRepository,
                              DrillOperations      drill,
                              ShadowPortfolio      shadowPortfolio) {
        this.statusManager = statusManager;
        this.kisProperties = kisProperties;
        this.liquidationService = liquidationService;
        this.reconciler = reconciler;
        this.notifier = notifier;
        this.portfolioStateRepository = portfolioStateRepository;
        this.drill = drill;
        this.shadowPortfolio = shadowPortfolio;
    }

    /**
     * 자동 거래 시작.
     * 재가동 게이트 (OPERATIONS §6): EMERGENCY_STOPPED/FORCE_LIQUIDATING에서는
     * 이 엔드포인트로 직접 RUNNING 전환할 수 없다 — /resume을 먼저 거쳐야 한다.
     */
    @PostMapping("/start")
    public Map<String, Object> start() {
        if (!kisProperties.isConfigured()) {
            return result(false, "KIS 자격증명 미설정 — 설정 페이지에서 입력 후 다시 시도하세요",
                          statusManager.getCurrentMode());
        }
        TradingMode current = statusManager.getCurrentMode();
        if (current == TradingMode.EMERGENCY_STOPPED || current == TradingMode.FORCE_LIQUIDATING) {
            return result(false,
                    "현재 모드(" + current + ")에서는 직접 재개할 수 없습니다 — /api/trading/resume을 먼저 호출하세요",
                    current);
        }
        statusManager.changeMode(TradingMode.RUNNING);
        log.info("[TradingController] 거래 시작 요청 → RUNNING");
        return result(true, "거래를 시작했습니다", TradingMode.RUNNING);
    }

    /**
     * 재가동 게이트 (OPERATIONS §6) — EMERGENCY_STOPPED에서 돌아오는 유일한 길.
     * EMERGENCY_STOPPED → RUNNING 직접 전환을 막기 위해 /start와 분리된 엔드포인트로 두고,
     * 확인 문자열 + reason(원인 진단 기록, PERFORMANCE-GOVERNANCE §6 대체 최소 장치)을 요구한다.
     * LiquidationService.phase를 IDLE로 리셋하고 브로커 기준 재동기화를 수행한 뒤 SAFE_MODE로
     * 전환한다 — RUNNING 전환은 사람이 이어서 /start를 호출해 명시적으로 수행해야 한다.
     */
    @PostMapping("/resume")
    public Map<String, Object> resume(@RequestBody Map<String, String> body) {
        if (!RESUME_CONFIRM.equals(body.get("confirm"))) {
            return result(false,
                    "확인 문자열 불일치 — body에 {\"confirm\":\"" + RESUME_CONFIRM + "\",\"reason\":\"...\"}를 보내야 합니다",
                    statusManager.getCurrentMode());
        }
        String reason = body.get("reason");
        if (reason == null || reason.isBlank()) {
            return result(false,
                    "reason이 비어있습니다 — 재가동 전 원인 진단(PERFORMANCE-GOVERNANCE §6)을 기록하세요",
                    statusManager.getCurrentMode());
        }
        TradingMode current = statusManager.getCurrentMode();
        if (current != TradingMode.EMERGENCY_STOPPED) {
            return result(false,
                    "재가동 게이트는 EMERGENCY_STOPPED 상태에서만 사용합니다 (현재 " + current + ")",
                    current);
        }

        log.warn("[TradingController] 재가동 게이트 통과 — phase 리셋 + 재동기화. reason={}", reason);
        liquidationService.resetAfterManualReview();
        reconciler.correctFromBroker();
        statusManager.changeMode(TradingMode.SAFE_MODE);
        notifier.sendCritical("🔓 [재가동 게이트] SAFE_MODE로 전환 — reason: " + reason
                + "\n확인 후 대시보드에서 거래 시작을 눌러주세요.");

        return result(true, "재동기화 완료 — SAFE_MODE로 전환했습니다. 확인 후 /start로 거래를 재개하세요",
                TradingMode.SAFE_MODE);
    }

    /** 자동 거래 중지 (신규 주문 차단, 기존 미체결은 유지) */
    @PostMapping("/stop")
    public Map<String, Object> stop() {
        statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);
        log.info("[TradingController] 거래 중지 요청 → EMERGENCY_STOPPED");
        return result(true, "거래를 중지했습니다 (미체결 주문은 유지됩니다)", TradingMode.EMERGENCY_STOPPED);
    }

    /**
     * 강제청산 리허설 (OPERATIONS §7 모의 훈련 / Gate 2 완료 조건).
     * 오발동 방지를 위해 확인 문자열을 요구한다. 실행 후 EMERGENCY_STOPPED로 종결되며
     * 재가동은 OPERATIONS §6 재가동 게이트 절차를 따른다.
     */
    @PostMapping("/liquidation-drill")
    public Map<String, Object> liquidationDrill(@RequestBody Map<String, String> body) {
        if (!"CONFIRM_LIQUIDATE".equals(body.get("confirm"))) {
            return result(false,
                    "확인 문자열 불일치 — body에 {\"confirm\":\"CONFIRM_LIQUIDATE\"}를 보내야 합니다",
                    statusManager.getCurrentMode());
        }
        DrillOperations.Outcome outcome = drill.liquidate();
        return result(outcome.success(), outcome.message(), statusManager.getCurrentMode());
    }

    /**
     * 강제청산 리허설용 포지션 확보 — 고정 종목·고정 수량 시장가 매수 (모의계좌 전용).
     * 전략 신호를 기다리지 않고 청산 리허설을 돌리기 위한 장치다.
     * 실제 판정(프로필·자격증명·모드·리스크 룰)은 DrillService가 한다 — 예약 실행과 같은 관문이다.
     */
    @PostMapping("/manual-buy-drill")
    public Map<String, Object> manualBuyDrill(@RequestBody Map<String, String> body) {
        TradingMode mode = statusManager.getCurrentMode();
        if (!MANUAL_BUY_CONFIRM.equals(body.get("confirm"))) {
            return result(false,
                    "확인 문자열 불일치 — body에 {\"confirm\":\"" + MANUAL_BUY_CONFIRM + "\"}를 보내야 합니다",
                    mode);
        }
        DrillOperations.Outcome outcome = drill.manualBuy();
        return result(outcome.success(), outcome.message(), mode);
    }

    /**
     * 전고점 '미검증' 표시 해제 — MDD 자동 강제청산을 되살리는 <b>사람 확인</b> 신호.
     *
     * <p>기동 시 저장된 전고점이 실측 근거상 불가능해 상한으로 낮춰지면(클램프), 낮춘 값도
     * 여전히 실제보다 높을 수 있어 그 값으로 계산한 MDD는 과대평가다. 그 상태에서 자동 강제청산이
     * 돌면 헛청산이므로 청산만 보류하고 매수 차단은 유지한다. 사람이 실제 잔고를 확인한 뒤
     * (필요하면 전고점 값 자체를 바로잡은 뒤) 이 엔드포인트로 보류를 푼다.
     */
    @PostMapping("/peak-equity-ack")
    public Map<String, Object> acknowledgePeakEquity(@RequestBody Map<String, String> body) {
        TradingMode mode = statusManager.getCurrentMode();
        if (!PEAK_ACK_CONFIRM.equals(body.get("confirm"))) {
            return result(false,
                    "확인 문자열 불일치 — body에 {\"confirm\":\"" + PEAK_ACK_CONFIRM + "\"}를 보내야 합니다",
                    mode);
        }
        if (!shadowPortfolio.acknowledgePeakEquity()) {
            return result(false,
                    "보류 중인 전고점 미검증 표시가 없습니다 (MDD 자동 강제청산은 이미 정상 동작 중)", mode);
        }
        log.warn("[TradingController] 전고점 미검증 표시 해제 — MDD 자동 강제청산 재개");
        notifier.sendCritical("✅ [전고점 확인] 사람이 전고점을 확인했습니다 — MDD 자동 강제청산 보류를 해제합니다");
        return result(true, "전고점 미검증 표시를 해제했습니다 — MDD 자동 강제청산이 다시 동작합니다", mode);
    }

    /**
     * 연속 무중단 가동일수 조회 (릴리즈 체크리스트 "모의투자 5거래일 연속 실행" 검증).
     * 기록은 RunStreakRecorder가 거래일마다 15:25 KST에 남긴다.
     */
    @GetMapping("/run-streak")
    public Map<String, Object> runStreak() {
        int streak = portfolioStateRepository.findById(PortfolioState.KEY_RUN_STREAK_DAYS)
                .map(s -> (int) s.getStateValue()).orElse(0);
        String lastDate = portfolioStateRepository.findById(PortfolioState.KEY_RUN_STREAK_LAST_DATE)
                .map(s -> LocalDate.parse(String.valueOf((long) s.getStateValue()),
                        DateTimeFormatter.BASIC_ISO_DATE).toString())
                .orElse(null);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("streakDays", streak);
        m.put("goalDays", RunStreakRecorder.GOAL_DAYS);
        m.put("achieved", streak >= RunStreakRecorder.GOAL_DAYS);
        m.put("lastRecordedDate", lastDate);
        return m;
    }

    private static Map<String, Object> result(boolean success, String message, TradingMode mode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", success);
        m.put("message", message);
        m.put("tradingMode", mode.name());
        return m;
    }
}
