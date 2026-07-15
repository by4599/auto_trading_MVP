package com.trading.risk;

import com.trading.market.KisProperties;
import com.trading.order.OrderEngine;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ATR 손절 감시 (방법론 §4.1) — 1초마다 보유 포지션의 현재가를 손절선과 비교한다.
 *
 * 손절은 평시 매도이므로 청산 경로(LiquidationService)가 아닌
 * Signal → RiskEngine → OrderEngine 경로를 쓴다 (ADR 규칙 5).
 * 현재가는 KisPositionManager의 잔고 스냅샷(3초 캐시)을 재사용하므로
 * 추가 API 호출이 없다. 폴백 스냅샷(currentPrice=평단가)은 손절선보다 위라서
 * 오탐이 나지 않는다.
 */
@Component
@Profile("paper")
public class StopLossMonitor {

    private static final Logger log = LoggerFactory.getLogger(StopLossMonitor.class);

    private static final String STRATEGY_NAME = "StopLoss-ATR";
    private static final String TRAILING_NAME = "TrailingStop";

    private final PositionRepository positionRepository;
    private final OrderHistoryRepository orderHistoryRepository;
    private final PositionManager positionManager;
    private final RiskEngine riskEngine;
    private final OrderEngine orderEngine;
    private final TradingStatusManager statusManager;
    private final KisProperties kisProperties;
    private final TrailingStopTracker trailingStopTracker;

    public StopLossMonitor(PositionRepository positionRepository,
                           OrderHistoryRepository orderHistoryRepository,
                           PositionManager positionManager,
                           RiskEngine riskEngine,
                           OrderEngine orderEngine,
                           TradingStatusManager statusManager,
                           KisProperties kisProperties,
                           TrailingStopTracker trailingStopTracker) {
        this.positionRepository = positionRepository;
        this.orderHistoryRepository = orderHistoryRepository;
        this.positionManager = positionManager;
        this.riskEngine = riskEngine;
        this.orderEngine = orderEngine;
        this.statusManager = statusManager;
        this.kisProperties = kisProperties;
        this.trailingStopTracker = trailingStopTracker;
    }

    @Scheduled(fixedDelay = 1000)
    public void monitor() {
        checkStops();
    }

    void checkStops() {
        if (!kisProperties.isConfigured()) return;
        TradingMode mode = statusManager.getCurrentMode();
        if (mode != TradingMode.RUNNING && mode != TradingMode.SAFE_MODE) return;

        Account account;
        try {
            account = positionManager.snapshotAccount();
        } catch (Exception e) {
            log.warn("[StopLoss] 계좌 스냅샷 실패 — 이번 틱 건너뜀: {}", e.getMessage());
            return;
        }

        for (Account.PositionSnapshot snapshot : account.getPositions()) {
            try {
                checkOne(snapshot, account);
            } catch (Exception e) {
                // 한 종목의 오류가 나머지 감시를 멈추지 않는다
                log.error("[StopLoss] 감시 오류 — 계속 진행: {}", snapshot.stockCode(), e);
            }
        }
    }

    private void checkOne(Account.PositionSnapshot snapshot, Account account) {
        Position pos = positionRepository.findByStockCode(snapshot.stockCode()).orElse(null);
        if (pos == null || pos.getQuantity() <= 0) return;

        double current = snapshot.currentPrice();
        if (current <= 0) return;                       // 시세 불명 — 판정하지 않는다

        // 트레일링 스톱 필터 (§3.3, 기본 OFF) — ATR 손절과 별개의 수익 보존 훅
        trailingStopTracker.updateHigh(snapshot.stockCode(), current);
        if (trailingStopTracker.exitPrice(
                snapshot.stockCode(), current, pos.getAveragePrice()).isPresent()) {
            sellVia(TRAILING_NAME, snapshot.stockCode(), account, current, pos);
            return;
        }

        if (pos.getStopPrice() == null || current > pos.getStopPrice()) return;
        sellVia(STRATEGY_NAME, snapshot.stockCode(), account, current, pos);
    }

    private void sellVia(String reason, String stockCode, Account account,
                         double current, Position pos) {
        if (hasPendingSell(stockCode)) return; // 중복 매도 방지

        Signal signal = Signal.sell(stockCode, reason);
        RiskResult result = riskEngine.check(signal, account);
        if (!result.isPass()) {
            log.warn("[StopLoss] RiskEngine 거부: {} 사유={}", stockCode, result.getReason());
            return;
        }

        log.warn("[StopLoss] {} 트리거: {} 현재가={} 손절가={}",
                reason, stockCode, String.format("%.0f", current),
                pos.getStopPrice() == null ? "-" : String.format("%.0f", pos.getStopPrice()));
        orderEngine.execute(signal);
        trailingStopTracker.clear(stockCode);
    }

    private boolean hasPendingSell(String stockCode) {
        return orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                        stockCode, OrderSide.SELL, OrderStatus.ACCEPTED)
                || orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                        stockCode, OrderSide.SELL, OrderStatus.PARTIAL_FILLED);
    }
}
