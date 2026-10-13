package com.trading.position;

import com.trading.NotificationService;
import com.trading.market.MarketCalendarService;
import com.trading.risk.BrokerageApiClient;
import com.trading.risk.LiquidationService;
import com.trading.risk.StopLossArmer;
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
import java.util.Set;
import java.util.stream.Collectors;

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
    private final MarketCalendarService marketCalendar;
    private final StopLossArmer stopLossArmer;

    /** 직전 주기에 관측된 불일치 종목 집합 — 2주기 연속(약 20분) 지속 시에만 자동 보정한다. */
    private volatile Set<String> previousMismatchStocks = Set.of();

    public ShadowPortfolioReconciler(TradingStatusManager statusManager,
                                      LiquidationService liquidationService,
                                      BalanceClient balanceClient,
                                      PositionRepository positionRepository,
                                      BrokerageApiClient brokerageClient,
                                      NotificationService notifier,
                                      MarketCalendarService marketCalendar,
                                      StopLossArmer stopLossArmer) {
        this.statusManager = statusManager;
        this.liquidationService = liquidationService;
        this.balanceClient = balanceClient;
        this.positionRepository = positionRepository;
        this.brokerageClient = brokerageClient;
        this.notifier = notifier;
        this.marketCalendar = marketCalendar;
        this.stopLossArmer = stopLossArmer;
    }

    /**
     * 평시 10분 주기 감시 — 조건부 자동 보정 (§5.3 개정).
     *
     * 첫 감지는 알림만(일시 오류·코퍼레이트 액션 가능성). **2주기(약 20분) 연속 확인된**
     * 불일치만 브로커 기준으로 자동 보정한다 — 잔고 API 엉터리 읽기 한 번으로 실보유를
     * 삭제하는 사고(2026-07-30류)를 막기 위함. 신선한 실값(fetchBalance 성공)일 때만 동작하고,
     * 장 시간 외에는 아예 판정하지 않는다(낡은 값 배제).
     */
    @Scheduled(fixedRate = 600_000)
    public void reconcile() {
        if (!canReconcile()) return;
        if (!marketCalendar.isDuringMarketHoursNow()) return;  // 장중에만 — 마감 후 낡은/불안정 데이터 배제

        List<Mismatch> mismatches = detectMismatches();  // fetchBalance 실패면 List.of() → 미보정
        if (mismatches.isEmpty()) {
            previousMismatchStocks = Set.of();
            log.debug("[Reconciler] 포지션 보정 체크 완료 — 불일치 없음");
            return;
        }

        Set<String> currentStocks = mismatches.stream()
                .map(Mismatch::stockCode).collect(Collectors.toSet());
        boolean persisted = currentStocks.stream().anyMatch(previousMismatchStocks::contains);
        previousMismatchStocks = currentStocks;

        if (persisted) {
            // 2주기 연속 확인 → 진짜 불일치로 판단, 브로커 기준 자동 보정 (correctFromBroker가 로그+텔레그램)
            log.warn("[Reconciler] 불일치 2주기 지속 — 브로커 기준 자동 보정 개시: {}", describeAll(mismatches));
            correctFromBroker();
        } else {
            // 첫 감지 — 일시 오류 가능성. 이번엔 보정하지 않고 알림만, 다음 주기 재확인.
            log.warn("[Reconciler] 브로커-DB 불일치 {}건 감지(1차) — 다음 주기 재확인 후 보정: {}",
                    mismatches.size(), mismatches);
            notifier.sendCritical("⚠️ [Reconciler] 브로커-DB 불일치 감지(재확인 대기) — 코퍼레이트 액션 가능성:\n"
                    + describeAll(mismatches));
        }
    }

    /**
     * 기동 재동기화 (OPERATIONS §3).
     * 앱이 죽어있던 동안 미체결로 남았던 주문을 전량 취소하고, 브로커 잔고를
     * 유일한 진실로 삼아 DB를 즉시 보정한 뒤, 곧바로 RUNNING(가동)으로 시작한다.
     *
     * 사용자 정책(2026-07-16): "앱이 실행되면 자동으로 가동 상태로 만든다."
     * → 예전의 장중 재기동 SAFE_MODE 대기(사람이 수동 /start)를 없앴다. 대신
     *   재동기화(잔고 대조·미체결 취소)를 먼저 끝내고 시작하므로 오래된 상태로
     *   매매하지는 않는다. 런타임 중 증권사 연결이 끊기면 KisApiClient가 SAFE_MODE로
     *   자동 정지하고 회복 시 자동 재개하는 안전장치는 그대로 유지된다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        brokerageClient.cancelAllPendingOrders();
        correctFromBroker();
        statusManager.changeMode(TradingMode.RUNNING);
        log.info("[Reconciler] 기동 재동기화 완료 — 자동 가동(RUNNING)");
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

        armMissingStops();

        if (corrections.isEmpty()) {
            log.info("[Reconciler] 브로커 대조 완료 — 불일치 없음");
        } else {
            log.warn("[Reconciler] 브로커 기준 보정 {}건: {}", corrections.size(), corrections);
            notifier.sendCritical("🔧 [Reconciler] 브로커 기준 포지션 보정:\n"
                    + String.join("\n", corrections));
        }
    }

    /**
     * 보유 중인데 손절선이 없는 포지션에 손절선을 장착한다 — 보정 경로의 구멍을 메운다.
     *
     * {@link #correctFromBroker()}는 브로커 잔고를 그대로 옮겨 담을 뿐이라 체결 이벤트가 없고,
     * 따라서 {@code StopLossArmer}의 이벤트 경로를 타지 않는다. 그 결과 보정으로 만들어진
     * 포지션은 손절선 없이 남았다(2026-08-04 실측: 034020 13주 무방비, 그날 "신규 생성" 3회).
     * 보정을 알림→자동으로 승격하면서 이 구멍이 상시 노출됐으므로, 대조할 때마다
     * "보유분은 반드시 손절선을 갖는다"를 불변식으로 강제한다.
     *
     * 기준가는 브로커 평균단가다 — 체결가를 알 수 없는 경로이므로 실제 취득원가에 가장 가깝다.
     * 이미 손절선이 있으면 건드리지 않는다(임의로 옮기면 기존 방어선이 느슨해질 수 있다).
     */
    private void armMissingStops() {
        List<Position> unprotected = positionRepository.findAll().stream()
                .filter(p -> p.getQuantity() > 0 && p.getStopPrice() == null)
                .toList();
        if (unprotected.isEmpty()) return;

        log.warn("[Reconciler] 손절선 없는 보유분 {}건 — 브로커 평균단가 기준으로 장착 시도: {}",
                unprotected.size(), unprotected.stream().map(Position::getStockCode).toList());
        for (Position pos : unprotected) {
            stopLossArmer.arm(pos.getStockCode(), pos.getAveragePrice());
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
