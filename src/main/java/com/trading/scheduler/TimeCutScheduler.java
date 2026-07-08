package com.trading.scheduler;

import com.trading.market.KisProperties;
import com.trading.order.OrderEngine;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskResult;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 15:15 타임컷 (F-4) — 변동성 돌파는 당일 청산이 전제인 단타 전략이므로
 * 장 마감 전 보유 포지션을 전량 시장가 매도로 정리한다.
 *
 * ADR-001 2.3: 평시 매도이므로 청산 경로(LiquidationService)가 아닌
 * Signal → RiskEngine → OrderEngine 경로를 사용한다.
 * FORCE_LIQUIDATING/EMERGENCY_STOPPED 상태에서는 양보한다 (포지션 소유권은 청산 상태머신).
 *
 * v1 제약: OrderEngine 매도는 1주 고정이며, PendingOrderRule이 보유 중 재매수를
 * 차단하므로 포지션 수량은 항상 1이다. 수량 > 1이 관측되면 경고만 남긴다 (v2 수량 로직).
 */
@Component
@Profile("paper")
public class TimeCutScheduler {

    private static final Logger log = LoggerFactory.getLogger(TimeCutScheduler.class);

    private static final String STRATEGY_NAME = "TimeCut-1515";

    private final PositionRepository positionRepository;
    private final OrderHistoryRepository orderHistoryRepository;
    private final PositionManager positionManager;
    private final RiskEngine riskEngine;
    private final OrderEngine orderEngine;
    private final TradingStatusManager statusManager;
    private final KisProperties kisProperties;

    public TimeCutScheduler(PositionRepository positionRepository,
                            OrderHistoryRepository orderHistoryRepository,
                            PositionManager positionManager,
                            RiskEngine riskEngine,
                            OrderEngine orderEngine,
                            TradingStatusManager statusManager,
                            KisProperties kisProperties) {
        this.positionRepository = positionRepository;
        this.orderHistoryRepository = orderHistoryRepository;
        this.positionManager = positionManager;
        this.riskEngine = riskEngine;
        this.orderEngine = orderEngine;
        this.statusManager = statusManager;
        this.kisProperties = kisProperties;
    }

    /** 평일 15:15 KST 1회 실행. 앱이 그 시각에 꺼져 있었으면 해당일 타임컷은 건너뛴다 (운영 문서화). */
    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Seoul")
    public void run() {
        executeTimeCut();
    }

    void executeTimeCut() {
        if (!kisProperties.isConfigured()) {
            return;
        }
        TradingMode mode = statusManager.getCurrentMode();
        if (mode != TradingMode.RUNNING) {
            log.warn("[타임컷] 건너뜀 — 현재 mode={} (청산 상태머신이 포지션 소유)", mode);
            return;
        }

        List<Position> holdings = positionRepository.findAll().stream()
                .filter(p -> p.getQuantity() > 0)
                .toList();
        if (holdings.isEmpty()) {
            log.info("[타임컷] 보유 포지션 없음 — 정리할 것 없음");
            return;
        }

        log.info("[타임컷] 15:15 보유분 정리 개시 — {}종목", holdings.size());
        for (Position pos : holdings) {
            try {
                sellPosition(pos);
            } catch (Exception e) {
                // 한 종목의 실패가 나머지 정리를 멈추지 않는다 (종목별 예외 격리 — ADR 2.3)
                log.error("[타임컷] 매도 실패 — 계속 진행: stockCode={}", pos.getStockCode(), e);
            }
        }
    }

    private void sellPosition(Position pos) {
        String stockCode = pos.getStockCode();

        if (hasPendingSell(stockCode)) {
            log.warn("[타임컷] 미체결 SELL 존재 — 중복 매도 방지: stockCode={}", stockCode);
            return;
        }
        if (pos.getQuantity() > 1) {
            log.warn("[타임컷] 수량 {}주 관측 — v1 매도는 1주 고정이라 잔여분이 남는다 (v2 수량 로직 필요): stockCode={}",
                    pos.getQuantity(), stockCode);
        }

        Signal signal = Signal.sell(stockCode, STRATEGY_NAME);
        RiskResult result = riskEngine.check(signal, positionManager.snapshotAccount());
        if (!result.isPass()) {
            log.warn("[타임컷] RiskEngine 거부: stockCode={} 사유={}", stockCode, result.getReason());
            return;
        }
        orderEngine.execute(signal);
        log.info("[타임컷] 매도 접수 완료: stockCode={}", stockCode);
    }

    private boolean hasPendingSell(String stockCode) {
        return orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                        stockCode, OrderSide.SELL, OrderStatus.ACCEPTED)
                || orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                        stockCode, OrderSide.SELL, OrderStatus.PARTIAL_FILLED);
    }
}
