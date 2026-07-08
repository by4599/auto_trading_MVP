package com.trading.order;

import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 전략은 절대 여기로 직접 들어오지 않는다.
 * Strategy -> Signal -> RiskEngine 통과 -> OrderEngine 순서가 항상 강제된다.
 */
@Component
public class OrderEngine {

    private static final Logger log = LoggerFactory.getLogger(OrderEngine.class);

    private final KisOrderClient orderClient;
    private final TradingStatusManager statusManager;

    public OrderEngine(KisOrderClient orderClient, TradingStatusManager statusManager) {
        this.orderClient = orderClient;
        this.statusManager = statusManager;
    }

    public void execute(Signal signal) {
        TradingMode mode = statusManager.getCurrentMode();
        if (mode != TradingMode.RUNNING) {
            log.warn("[OrderEngine] 주문 차단 — 현재 mode={}, signal={}", mode, signal.getStockCode());
            return;
        }
        if (signal.isBuy()) {
            orderClient.buy(signal.getStockCode());
        } else if (signal.isSell()) {
            orderClient.sell(signal.getStockCode());
        }
        // 주문 접수 후 흐름: KisOrderClientImpl → OrderHistory(ACCEPTED) 저장
        //   → FillPoller(3초 주기) → FillProcessor → FillStateUpdater → Position 반영
        //   → OrderFilledEvent AFTER_COMMIT → TradingEventListener → TelegramNotifier
    }
}
