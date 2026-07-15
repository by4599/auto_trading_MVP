package com.trading.risk;

import com.trading.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

// ADR 2.3: 두 청산 경로(전량/부분)는 단일 AtomicReference<LiquidationPhase>를 공유하여
// 동시 실행을 물리적으로 차단한다. AtomicBoolean 두 개로 분리하면 ADR 위반이다.
@Component
public class LiquidationService {

    private static final Logger log = LoggerFactory.getLogger(LiquidationService.class);

    private final BrokerageApiClient brokerageClient;
    private final TradingStatusManager statusManager;
    private final NotificationService notifier;

    private final AtomicReference<LiquidationPhase> phase =
            new AtomicReference<>(LiquidationPhase.IDLE);

    private final ExecutorService liquidationExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "Emergency-Liquidation-Thread"));
    private final ExecutorService trimExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "Position-Trim-Thread"));

    public LiquidationService(BrokerageApiClient brokerageClient,
                               TradingStatusManager statusManager,
                               NotificationService notifier) {
        this.brokerageClient = brokerageClient;
        this.statusManager = statusManager;
        this.notifier = notifier;
    }

    // ── 전량 강제 청산 (Global Equity Stop / DailyLossRule -5% 전용) ────────
    public void triggerForceLiquidation() {
        LiquidationPhase previous = phase.getAndSet(LiquidationPhase.FULL_LIQUIDATING);
        if (previous == LiquidationPhase.FULL_LIQUIDATING) {
            log.info("[중복 청산 패스] 이미 전량 청산이 진행 중입니다.");
            return;
        }
        if (previous == LiquidationPhase.TRIMMING) {
            log.warn("[승격] Trim 진행 중 전량 청산 요청 발생. Trim 스레드가 자체 종료 후 전량 청산으로 전환합니다.");
        }
        liquidationExecutor.submit(this::executeForceLiquidation);
    }

    private void executeForceLiquidation() {
        List<String> success = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            statusManager.changeMode(TradingMode.FORCE_LIQUIDATING);
            notifier.sendCritical("🚨 [강제청산 개시] 실제 잔고 기반 전량 청산을 시작합니다.");

            brokerageClient.cancelAllPendingOrders();
            ActualAccountInfo actual = brokerageClient.getActualAccountAsset();

            for (ActualPosition pos : actual.holdings()) {
                if (pos.quantity() <= 0) continue;
                try {
                    executeWithRetry(pos.ticker(), pos.quantity());
                    success.add(pos.ticker());
                } catch (IndeterminateLiquidationException e) {
                    failed.add(pos.ticker() + " [🔴 수동확인필수: " + e.getMessage() + "]");
                } catch (Exception e) {
                    failed.add(pos.ticker() + " [⚫ 거부확인됨: " + e.getMessage() + "]");
                }
            }
            sendLiquidationReport(success, failed);
        } catch (Exception e) {
            log.error("청산 인프라 장애", e);
            notifier.sendCritical("🆘 청산 인프라 마비! 수동 개입 필요: " + e.getMessage());
        } finally {
            // 성공/실패 무관하게 종단 상태 수렴 — 재가동 전까지 잠금 유지
            statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);
            phase.set(LiquidationPhase.FULL_LIQUIDATING);
        }
    }

    // ── 부분 축소 (CapacityScalingEngine 전용) ─────────────────────────────
    public void triggerPartialTrim(List<TrimTarget> targets) {
        // FULL_LIQUIDATING 진행/완료 상태라면 Trim은 무의미하므로 즉시 거부
        if (!phase.compareAndSet(LiquidationPhase.IDLE, LiquidationPhase.TRIMMING)) {
            log.info("[Trim 패스] 현재 phase={} 상태이므로 Trim을 건너뜁니다.", phase.get());
            return;
        }
        trimExecutor.submit(() -> executePartialTrim(targets, 0));
    }

    private void executePartialTrim(List<TrimTarget> targets, int consecutiveFailures) {
        List<String> success = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            for (TrimTarget t : targets) {
                // 매 종목 처리 전, 전량 청산으로 승격됐는지 확인
                if (phase.get() == LiquidationPhase.FULL_LIQUIDATING) {
                    log.warn("[Trim 중단] 전량 청산으로 승격되어 Trim 절차를 양보합니다.");
                    return;
                }
                try {
                    executeWithRetry(t.ticker(), t.quantity());
                    success.add(t.ticker());
                } catch (Exception e) {
                    failed.add(t.ticker());
                }
            }

            if (!failed.isEmpty()) {
                int totalFailures = consecutiveFailures + 1;
                notifier.sendCritical(String.format(
                        "[Trim 부분 실패 %d회차] 성공: %s / 실패: %s", totalFailures, success, failed));

                if (totalFailures >= 3) {
                    notifier.sendCritical("🆘 [Trim 3회 연속 실패] 위험 노출이 해소되지 않아 전량 청산으로 자동 승격합니다.");
                    phase.set(LiquidationPhase.IDLE); // 상태 초기화 후 정식 절차로 승격
                    triggerForceLiquidation();
                    return;
                }
            } else {
                notifier.sendCritical("✅ [Trim 완료] 초과 노출 정리 완료: " + success);
            }
        } finally {
            // 실패 3회 미만이거나 성공이면 RUNNING 유지 — EMERGENCY_STOPPED 전환 없음
            phase.compareAndSet(LiquidationPhase.TRIMMING, LiquidationPhase.IDLE);
        }
    }

    public boolean isAnyLiquidationInProgress() {
        return phase.get() != LiquidationPhase.IDLE;
    }

    // ── 재가동 게이트 (OPERATIONS §6) ────────────────────────────────────────
    /**
     * 재가동 전까지 걸려있던 잠금을 해제한다. 관리자 액션(TradingController의
     * 확인 문자열 요구 엔드포인트)에서만 호출해야 한다 — 청산 진행 여부와
     * 무관하게 즉시 IDLE로 되돌리므로, 청산이 실제로 끝났는지(브로커 실잔고
     * 재확인)는 호출 측 책임이다.
     */
    public void resetAfterManualReview() {
        LiquidationPhase previous = phase.getAndSet(LiquidationPhase.IDLE);
        if (previous != LiquidationPhase.IDLE) {
            log.warn("[LiquidationService] 관리자 액션으로 phase 리셋: {} → IDLE", previous);
        }
    }

    // 테스트 전용: 현재 phase 조회
    LiquidationPhase currentPhase() {
        return phase.get();
    }

    private void executeWithRetry(String ticker, int quantity) {
        int remaining = quantity;
        int attempts = 0;
        boolean balanceCheckFailed = false;

        while (remaining > 0 && attempts < 3) {
            attempts++;
            try {
                brokerageClient.sendMarketOrder(ticker, "SELL", remaining);
            } catch (Exception e) {
                log.error("[{}] 주문 전송 실패 ({}회차)", ticker, attempts, e);
            }
            sleep(500);
            try {
                remaining = brokerageClient.getActualHoldingQuantity(ticker);
                balanceCheckFailed = false;
            } catch (Exception e) {
                balanceCheckFailed = true;
                if (attempts >= 3) {
                    throw new IndeterminateLiquidationException("잔고 조회 연속 실패로 확인 불가");
                }
            }
        }
        if (remaining > 0 && !balanceCheckFailed) {
            throw new ExplicitRejectLiquidationException("거부/미체결로 잔여 " + remaining + "주");
        }
    }

    private void sendLiquidationReport(List<String> success, List<String> failed) {
        if (failed.isEmpty()) {
            notifier.sendCritical("✅ [강제청산 완수] 대상: " + success);
        } else {
            notifier.sendCritical(String.format(
                    "⚠️ [강제청산 부분 실패]\n• 성공: %s\n• 실패: %s", success, failed));
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
