package com.trading.order;

import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 전략은 절대 여기로 직접 들어오지 않는다.
 * Strategy -> Signal -> RiskEngine 통과 -> OrderEngine 순서가 항상 강제된다.
 *
 * 수량 결정 (P2-A):
 *   매수 = OrderSizingService의 R 역산 수량 (사이징 불성립 시 주문 스킵)
 *   매도 = 보유 전량 (타임컷·손절 모두 전량 청산이 v1 규칙)
 */
@Component
public class OrderEngine {

    private static final Logger log = LoggerFactory.getLogger(OrderEngine.class);

    private final KisOrderClient orderClient;
    private final TradingStatusManager statusManager;
    private final OrderSizingService sizingService;
    private final PositionRepository positionRepository;

    public OrderEngine(KisOrderClient orderClient,
                       TradingStatusManager statusManager,
                       OrderSizingService sizingService,
                       PositionRepository positionRepository) {
        this.orderClient = orderClient;
        this.statusManager = statusManager;
        this.sizingService = sizingService;
        this.positionRepository = positionRepository;
    }

    public void execute(Signal signal) {
        TradingMode mode = statusManager.getCurrentMode();
        // SAFE_MODE: 신규 매수만 금지, 손절·타임컷 등 평시 매도 경로는 유지
        if (mode == TradingMode.SAFE_MODE && signal.isBuy()) {
            log.warn("[OrderEngine] 매수 차단 — SAFE_MODE: {}", signal.getStockCode());
            return;
        }
        if (mode != TradingMode.RUNNING && mode != TradingMode.SAFE_MODE) {
            log.warn("[OrderEngine] 주문 차단 — 현재 mode={}, signal={}", mode, signal.getStockCode());
            return;
        }
        if (signal.isBuy()) {
            executeBuy(signal);
        } else if (signal.isSell()) {
            executeSell(signal);
        }
        // 주문 접수 후 흐름: KisOrderClientImpl → OrderHistory(ACCEPTED) 저장
        //   → FillPoller(3초 주기) → FillProcessor → FillStateUpdater → Position 반영
        //   → OrderFilledEvent AFTER_COMMIT → TradingEventListener → TelegramNotifier
    }

    private void executeBuy(Signal signal) {
        OrderSizingService.SizingResult sizing = sizingService.sizeBuy(signal.getStockCode());
        if (!sizing.executable()) {
            log.warn("[OrderEngine] 매수 스킵 — {}: {}", signal.getStockCode(), sizing.skipReason());
            return;
        }
        orderClient.buy(signal.getStockCode(), sizing.quantity());
    }

    private void executeSell(Signal signal) {
        int held = positionRepository.findByStockCode(signal.getStockCode())
                .map(Position::getQuantity)
                .orElse(0);
        if (held <= 0) {
            log.warn("[OrderEngine] 매도 스킵 — 보유 없음: {}", signal.getStockCode());
            return;
        }
        orderClient.sell(signal.getStockCode(), held);
    }
}
