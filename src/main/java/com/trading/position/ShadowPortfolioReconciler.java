package com.trading.position;

import com.trading.NotificationService;
import com.trading.market.MarketCalendarService;
import com.trading.risk.BrokerageApiClient;
import com.trading.risk.LiquidationService;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// ADR 2.4: Reconciler는 EMERGENCY_STOPPED뿐 아니라 TRIMMING 중에도
// API 경합을 피하기 위해 isAnyLiquidationInProgress() 체크 필수.
//
// OPERATIONS.md §3(기동 시퀀스)과 §5.3(코퍼레이트 액션)은 서로 다른 보정 정책을
// 요구한다 — 기동 직후는 "죽어있던 동안 놓친 체결"이 원인일 가능성이 높으므로
// 브로커 기준 자동 보정, 평시 10분 주기는 배당락/액면분할 등 코퍼레이트 액션일
// 수 있어 자동 보정하지 않고 알림만 한다. 이 클래스가 두 경로를 모두 갖는 이유.
@Component
@Profile("paper")
public class ShadowPortfolioReconciler {

    private static final Logger log = LoggerFactory.getLogger(ShadowPortfolioReconciler.class);

    private final TradingStatusManager statusManager;
    private final LiquidationService liquidationService;
    private final BalanceClient balanceClient;
    private final PositionRepository positionRepository;
    private final BrokerageApiClient brokerageClient;
    private final NotificationService notifier;
    private final MarketCalendarService marketCalendarService;

    public ShadowPortfolioReconciler(TradingStatusManager statusManager,
                                      LiquidationService liquidationService,
                                      BalanceClient balanceClient,
                                      PositionRepository positionRepository,
                                      BrokerageApiClient brokerageClient,
                                      NotificationService notifier,
                                      MarketCalendarService marketCalendarService) {
        this.statusManager = statusManager;
        this.liquidationService = liquidationService;
        this.balanceClient = balanceClient;
        this.positionRepository = positionRepository;
        this.brokerageClient = brokerageClient;
        this.notifier = notifier;
        this.marketCalendarService = marketCalendarService;
    }

    /** 평시 10분 주기 감시 — 불일치를 발견해도 자동 보정하지 않는다 (§5.3: 코퍼레이트 액션 가능성). */
    @Scheduled(fixedRate = 600_000)
    public void reconcile() {
        if (!canReconcile()) return;

        List<Mismatch> mismatches = detectMismatches();
        if (mismatches.isEmpty()) {
            log.debug("[Reconciler] 포지션 보정 체크 완료 — 불일치 없음");
            return;
        }
        log.warn("[Reconciler] 브로커-DB 불일치 {}건 감지 (자동 보정 안 함): {}", mismatches.size(), mismatches);
        notifier.sendCritical("⚠️ [Reconciler] 브로커-DB 불일치 감지 — 코퍼레이트 액션 가능성, 수동 확인 필요:\n"
                + describeAll(mismatches));
    }

    /**
     * 기동 재동기화 (OPERATIONS §3 ①~⑤).
     * 앱이 죽어있던 동안 미체결로 남았던 주문을 전량 취소하고, 브로커 잔고를
     * 유일한 진실로 삼아 DB를 즉시 보정한 뒤, 장중 재기동이면 SAFE_MODE로 대기시킨다.
     * EMERGENCY_STOPPED로 죽어있던 경우는 재가동 게이트(§6)를 거쳐야 하므로
     * SAFE_MODE 승격은 건너뛴다 — 재동기화 자체는 그대로 수행한다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        boolean wasEmergencyStopped = statusManager.getCurrentMode() == TradingMode.EMERGENCY_STOPPED;

        brokerageClient.cancelAllPendingOrders();
        correctFromBroker();

        if (wasEmergencyStopped) {
            log.info("[Reconciler] 기동 시 EMERGENCY_STOPPED — 재동기화만 수행, 재가동 게이트 대기");
            return;
        }
        if (marketCalendarService.isDuringMarketHoursNow()) {
            statusManager.changeMode(TradingMode.SAFE_MODE);
            notifier.sendCritical("⚠️ [기동] 장중 재기동 감지 — SAFE_MODE로 대기합니다. "
                    + "잔고 확인 후 대시보드에서 거래 시작을 눌러주세요.");
            log.warn("[Reconciler] 장중 재기동 — SAFE_MODE 진입");
        } else {
            log.info("[Reconciler] 장외 재기동 — RUNNING 유지");
        }
    }

    /** 재가동 게이트(TradingController /resume)에서도 재사용 — 브로커 기준 즉시 보정 */
    public void correctFromBroker() {
        Map<String, Position> dbPositions = currentDbPositions();
        Map<String, BalanceClient.Holding> brokerHoldings = fetchBrokerHoldings();
        if (brokerHoldings == null) return;

        List<String> corrections = new ArrayList<>();

        for (Position dbPos : dbPositions.values()) {
            if (!brokerHoldings.containsKey(dbPos.getStockCode())) {
                corrections.add(String.format("%s: DB %d주 → 브로커 없음 (삭제)",
                        dbPos.getStockCode(), dbPos.getQuantity()));
                positionRepository.delete(dbPos);
            }
        }

        for (BalanceClient.Holding h : brokerHoldings.values()) {
            Position dbPos = dbPositions.get(h.stockCode());
            if (dbPos == null) {
                Position created = Position.empty(h.stockCode());
                created.reconcileTo(h.quantity(), h.averagePrice());
                positionRepository.save(created);
                corrections.add(String.format("%s: DB 없음 → 브로커 %d주@%.0f (신규 생성)",
                        h.stockCode(), h.quantity(), h.averagePrice()));
            } else if (dbPos.getQuantity() != h.quantity()) {
                corrections.add(String.format("%s: DB %d주 → 브로커 %d주 (보정)",
                        h.stockCode(), dbPos.getQuantity(), h.quantity()));
                dbPos.reconcileTo(h.quantity(), h.averagePrice());
                positionRepository.save(dbPos);
            }
        }

        if (corrections.isEmpty()) {
            log.info("[Reconciler] 기동 재동기화 완료 — 불일치 없음");
        } else {
            log.warn("[Reconciler] 기동 재동기화 보정 {}건: {}", corrections.size(), corrections);
            notifier.sendCritical("🔧 [Reconciler] 기동 재동기화 — 브로커 기준 보정:\n"
                    + String.join("\n", corrections));
        }
    }

    private boolean canReconcile() {
        TradingMode mode = statusManager.getCurrentMode();
        if (mode == TradingMode.EMERGENCY_STOPPED || liquidationService.isAnyLiquidationInProgress()) {
            log.debug("[Reconciler] 스킵 — mode={}, liquidation={}",
                    mode, liquidationService.isAnyLiquidationInProgress());
            return false;
        }
        return true;
    }

    private List<Mismatch> detectMismatches() {
        Map<String, Position> dbPositions = currentDbPositions();
        Map<String, BalanceClient.Holding> brokerHoldings = fetchBrokerHoldings();
        if (brokerHoldings == null) return List.of();

        List<Mismatch> mismatches = new ArrayList<>();
        for (Position dbPos : dbPositions.values()) {
            BalanceClient.Holding h = brokerHoldings.get(dbPos.getStockCode());
            if (h == null) {
                mismatches.add(new Mismatch(dbPos.getStockCode(), dbPos.getQuantity(), null));
            } else if (h.quantity() != dbPos.getQuantity()) {
                mismatches.add(new Mismatch(dbPos.getStockCode(), dbPos.getQuantity(), h.quantity()));
            }
        }
        for (BalanceClient.Holding h : brokerHoldings.values()) {
            if (!dbPositions.containsKey(h.stockCode())) {
                mismatches.add(new Mismatch(h.stockCode(), 0, h.quantity()));
            }
        }
        return mismatches;
    }

    private Map<String, Position> currentDbPositions() {
        Map<String, Position> result = new HashMap<>();
        for (Position p : positionRepository.findAll()) {
            if (p.getQuantity() > 0) result.put(p.getStockCode(), p);
        }
        return result;
    }

    private Map<String, BalanceClient.Holding> fetchBrokerHoldings() {
        try {
            Map<String, BalanceClient.Holding> result = new HashMap<>();
            for (BalanceClient.Holding h : balanceClient.fetchBalance().holdings()) {
                if (h.quantity() > 0) result.put(h.stockCode(), h);
            }
            return result;
        } catch (Exception e) {
            log.warn("[Reconciler] 브로커 잔고 조회 실패 — 이번 회차 건너뜀: {}", e.getMessage());
            return null;
        }
    }

    private static String describeAll(List<Mismatch> mismatches) {
        StringBuilder sb = new StringBuilder();
        for (Mismatch m : mismatches) sb.append(m).append('\n');
        return sb.toString().stripTrailing();
    }

    private record Mismatch(String stockCode, int dbQuantity, Integer brokerQuantity) {
        @Override
        public String toString() {
            String broker = brokerQuantity == null ? "없음" : brokerQuantity + "주";
            return String.format("%s: DB %d주 vs 브로커 %s", stockCode, dbQuantity, broker);
        }
    }
}
