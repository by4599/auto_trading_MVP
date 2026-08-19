package com.trading.control;

import com.trading.NotificationService;
import com.trading.market.KisProperties;
import com.trading.order.OrderEngine;
import com.trading.position.Account;
import com.trading.position.PortfolioState;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.PositionManager;
import com.trading.position.ShadowPortfolioReconciler;
import com.trading.risk.LiquidationService;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskResult;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import com.trading.scheduler.RunStreakRecorder;
import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
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
    /** 리허설 전용 고정값 — 범용 수동 매수 도구가 아니다 (수량·종목을 파라미터로 열지 않는다) */
    private static final String MANUAL_BUY_STOCK_CODE = "005930";
    private static final int    MANUAL_BUY_QUANTITY   = 10;

    private final TradingStatusManager statusManager;
    private final KisProperties        kisProperties;
    private final LiquidationService   liquidationService;
    private final ShadowPortfolioReconciler reconciler;
    private final NotificationService  notifier;
    private final PortfolioStateRepository portfolioStateRepository;
    private final RiskEngine           riskEngine;
    private final OrderEngine          orderEngine;
    private final PositionManager      positionManager;
    private final Environment          environment;

    public TradingController(TradingStatusManager statusManager,
                              KisProperties        kisProperties,
                              LiquidationService   liquidationService,
                              ShadowPortfolioReconciler reconciler,
                              NotificationService  notifier,
                              PortfolioStateRepository portfolioStateRepository,
                              RiskEngine           riskEngine,
                              OrderEngine          orderEngine,
                              PositionManager      positionManager,
                              Environment          environment) {
        this.statusManager = statusManager;
        this.kisProperties = kisProperties;
        this.liquidationService = liquidationService;
        this.reconciler = reconciler;
        this.notifier = notifier;
        this.portfolioStateRepository = portfolioStateRepository;
        this.riskEngine = riskEngine;
        this.orderEngine = orderEngine;
        this.positionManager = positionManager;
        this.environment = environment;
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
        if (!kisProperties.isConfigured()) {
            return result(false, "KIS 자격증명 미설정", statusManager.getCurrentMode());
        }
        log.warn("[TradingController] 강제청산 리허설 개시 — 미체결 취소 후 보유 전량 시장가 매도");
        liquidationService.triggerForceLiquidation();
        return result(true, "강제청산 개시 — 진행 상황은 텔레그램/로그 확인, 종료 후 EMERGENCY_STOPPED 유지",
                statusManager.getCurrentMode());
    }

    /**
     * 강제청산 리허설용 포지션 확보 — 005930 정확히 10주 시장가 매수 (모의계좌 전용).
     * 전략 신호를 기다리지 않고 청산 리허설을 돌리기 위한 테스트 장치이며,
     * 리스크 룰은 그대로 통과시킨다 (막히면 사유를 그대로 돌려주고 강제로 뚫지 않는다).
     * 수량 사이징(R 역산)만 우회한다 — OrderEngine.executeManualBuy.
     */
    @PostMapping("/manual-buy-drill")
    public Map<String, Object> manualBuyDrill(@RequestBody Map<String, String> body) {
        TradingMode mode = statusManager.getCurrentMode();
        if (!isPaperProfile()) {
            return result(false, "모의투자(paper) 프로필에서만 사용할 수 있는 리허설 기능입니다", mode);
        }
        if (!MANUAL_BUY_CONFIRM.equals(body.get("confirm"))) {
            return result(false,
                    "확인 문자열 불일치 — body에 {\"confirm\":\"" + MANUAL_BUY_CONFIRM + "\"}를 보내야 합니다",
                    mode);
        }
        if (!kisProperties.isConfigured()) {
            return result(false, "KIS 자격증명 미설정", mode);
        }
        if (mode != TradingMode.RUNNING) {
            return result(false,
                    "현재 모드(" + mode + ")에서는 신규 매수가 차단됩니다 — /api/trading/start로 RUNNING 전환 후 다시 시도하세요",
                    mode);
        }

        Signal signal = Signal.buy(MANUAL_BUY_STOCK_CODE, "MANUAL_DRILL");
        Account account = positionManager.snapshotAccount();
        RiskResult risk = riskEngine.check(signal, account);
        if (!risk.isPass()) {
            log.warn("[TradingController] 수동 매수 리허설 차단 — {}", risk.getReason());
            return result(false, "리스크 룰이 매수를 막았습니다 — " + risk.getReason(), mode);
        }

        log.warn("[TradingController] 수동 매수 리허설 개시 — {} {}주 시장가",
                MANUAL_BUY_STOCK_CODE, MANUAL_BUY_QUANTITY);
        orderEngine.executeManualBuy(signal, MANUAL_BUY_QUANTITY);
        return result(true, MANUAL_BUY_STOCK_CODE + " " + MANUAL_BUY_QUANTITY
                + "주 시장가 매수를 접수했습니다 — 체결은 3초 주기 체결확인이 반영합니다", mode);
    }

    private boolean isPaperProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains("paper");
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
