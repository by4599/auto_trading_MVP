package com.trading.position;

import com.trading.risk.LiquidationService;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// ADR 2.4: Reconciler는 EMERGENCY_STOPPED뿐 아니라 TRIMMING 중에도
// API 경합을 피하기 위해 isAnyLiquidationInProgress() 체크 필수.
@Component
@Profile("paper")
public class ShadowPortfolioReconciler {

    private static final Logger log = LoggerFactory.getLogger(ShadowPortfolioReconciler.class);

    private final TradingStatusManager statusManager;
    private final LiquidationService liquidationService;

    public ShadowPortfolioReconciler(TradingStatusManager statusManager,
                                      LiquidationService liquidationService) {
        this.statusManager = statusManager;
        this.liquidationService = liquidationService;
    }

    @Scheduled(fixedRate = 600_000) // 10분마다
    public void reconcile() {
        TradingMode mode = statusManager.getCurrentMode();
        if (mode == TradingMode.EMERGENCY_STOPPED
                || liquidationService.isAnyLiquidationInProgress()) {
            log.debug("[Reconciler] 스킵 — mode={}, liquidation={}",
                    mode, liquidationService.isAnyLiquidationInProgress());
            return;
        }
        // v1: 단순 로그. Sprint 3에서 실제 잔고-DB 보정 로직 추가 예정.
        log.info("[Reconciler] 포지션 보정 체크 완료 (v1: 로그 전용)");
    }
}
