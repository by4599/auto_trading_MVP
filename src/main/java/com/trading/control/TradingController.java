package com.trading.control;

import com.trading.market.KisProperties;
import com.trading.risk.LiquidationService;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 거래 수동 제어 API.
 * UI에서 거래 시작/중지 버튼이 이 엔드포인트를 호출한다.
 * 자동 전략 루프(TradingScheduler)는 TradingStatusManager.getCurrentMode()를
 * 매 틱마다 읽어 EMERGENCY_STOPPED면 진입 자체를 건너뛴다.
 */
@RestController
@RequestMapping("/api/trading")
public class TradingController {

    private static final Logger log = LoggerFactory.getLogger(TradingController.class);

    private final TradingStatusManager statusManager;
    private final KisProperties        kisProperties;
    private final LiquidationService   liquidationService;

    public TradingController(TradingStatusManager statusManager,
                              KisProperties        kisProperties,
                              LiquidationService   liquidationService) {
        this.statusManager = statusManager;
        this.kisProperties = kisProperties;
        this.liquidationService = liquidationService;
    }

    /** 자동 거래 시작 */
    @PostMapping("/start")
    public Map<String, Object> start() {
        if (!kisProperties.isConfigured()) {
            return result(false, "KIS 자격증명 미설정 — 설정 페이지에서 입력 후 다시 시도하세요",
                          statusManager.getCurrentMode());
        }
        statusManager.changeMode(TradingMode.RUNNING);
        log.info("[TradingController] 거래 시작 요청 → RUNNING");
        return result(true, "거래를 시작했습니다", TradingMode.RUNNING);
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

    private static Map<String, Object> result(boolean success, String message, TradingMode mode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", success);
        m.put("message", message);
        m.put("tradingMode", mode.name());
        return m;
    }
}
