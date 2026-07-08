package com.trading.order;

import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

/**
 * 전략은 절대 여기로 직접 들어오지 않는다.
 * Strategy -> Signal -> RiskEngine 통과 -> OrderEngine 순서가 항상 강제된다.
 */
@Component
public class OrderEngine {

    private final KisOrderClient orderClient;

    public OrderEngine(KisOrderClient orderClient) {
        this.orderClient = orderClient;
    }

    public void execute(Signal signal) {
        if (signal.isBuy()) {
            orderClient.buy(signal.getStockCode());
        } else if (signal.isSell()) {
            orderClient.sell(signal.getStockCode());
        }
        // TODO: 체결 결과를 order_history에 기록하고 PositionManager 갱신,
        //       텔레그램으로 체결 알림 전송
    }
}
