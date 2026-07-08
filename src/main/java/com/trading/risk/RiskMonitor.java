package com.trading.risk;

import com.trading.NotificationService;
import com.trading.market.KisProperties;
import com.trading.position.Account;
import com.trading.position.PositionManager;
import com.trading.position.ShadowPortfolio;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 계좌 단위 상시 감시 (감사 F-2 해소).
 *
 * 리스크 룰(RiskEngine)은 매수 신호가 있을 때만 평가되는 "진입 게이트"라서,
 * 신호가 없는 하락장에서는 일일손실·MDD 한도가 검사되지 않는 역설이 있었다.
 * 이 컴포넌트는 신호와 무관하게 1초마다 계좌를 평가해 강제청산을 트리거한다.
 * (ADR-001 2.2 "매 1초 틱마다 실시간 자산 평가"의 실체)
 *
 * 역할 분리: 룰(DailyLossRule/GlobalEquityStopRule) = 매수 거부,
 * RiskMonitor = 청산 트리거. 임계값은 RiskLimits 공유.
 * 중복 트리거는 LiquidationService의 phase 상태머신이 멱등 처리한다.
 */
@Component
@Profile("paper")
public class RiskMonitor {

    private static final Logger log = LoggerFactory.getLogger(RiskMonitor.class);

    private final PositionManager positionManager;
    private final ShadowPortfolio shadowPortfolio;
    private final LiquidationService liquidationService;
    private final TradingStatusManager statusManager;
    private final KisProperties kisProperties;
    private final NotificationService notifier;

    public RiskMonitor(PositionManager positionManager,
                       ShadowPortfolio shadowPortfolio,
                       LiquidationService liquidationService,
                       TradingStatusManager statusManager,
                       KisProperties kisProperties,
                       NotificationService notifier) {
        this.positionManager = positionManager;
        this.shadowPortfolio = shadowPortfolio;
        this.liquidationService = liquidationService;
        this.statusManager = statusManager;
        this.kisProperties = kisProperties;
        this.notifier = notifier;
    }

    @PostConstruct
    void logStart() {
        log.info("[RiskMonitor] 계좌 감시 시작 — 일일손실 청산 {}%, MDD 한도 {}%",
                RiskLimits.DAILY_LOSS_LIQUIDATE * 100, RiskLimits.MDD_LIMIT * 100);
    }

    @Scheduled(fixedDelay = 1000)
    public void monitor() {
        if (!kisProperties.isConfigured()) return;
        if (statusManager.getCurrentMode() != TradingMode.RUNNING) return;
        if (liquidationService.isAnyLiquidationInProgress()) return;

        Account account;
        try {
            account = positionManager.snapshotAccount();
        } catch (Exception e) {
            log.warn("[RiskMonitor] 계좌 스냅샷 실패 — 이번 틱 건너뜀: {}", e.getMessage());
            return;
        }

        // 잔고 폴백 등으로 총자산을 신뢰할 수 없으면 판정하지 않는다 (F-1 오탐 재발 방지)
        double current = account.getTotalAssetValue();
        if (current <= 0) return;

        // 1) 일일 손실 -5% → 강제청산
        double pnl = account.getDailyPnlPercent();
        if (pnl <= RiskLimits.DAILY_LOSS_LIQUIDATE) {
            log.error("[RiskMonitor] 일일 손실 {}% — 강제청산 트리거", String.format("%.2f", pnl * 100));
            notifier.sendCritical(String.format(
                    "🚨 [RiskMonitor] 일일 손실 %.2f%% (한도 %.0f%%) — 강제청산을 개시합니다",
                    pnl * 100, RiskLimits.DAILY_LOSS_LIQUIDATE * 100));
            liquidationService.triggerForceLiquidation();
            return;
        }

        // 2) 전고점 대비 MDD 10% 초과 → 강제청산
        double peak = shadowPortfolio.getPeakEquity();
        if (peak > 0) {
            double drawdown = (peak - current) / peak;
            if (drawdown > RiskLimits.MDD_LIMIT) {
                log.error("[RiskMonitor] MDD {}% 초과 (peak={}, current={}) — 강제청산 트리거",
                        String.format("%.2f", drawdown * 100), peak, current);
                notifier.sendCritical(String.format(
                        "🚨 [RiskMonitor] 전고점 대비 MDD %.2f%% (한도 %.0f%%) — 강제청산을 개시합니다",
                        drawdown * 100, RiskLimits.MDD_LIMIT * 100));
                liquidationService.triggerForceLiquidation();
            }
        }
    }
}
