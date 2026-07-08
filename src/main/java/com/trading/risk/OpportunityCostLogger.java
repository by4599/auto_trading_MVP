package com.trading.risk;

import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OpportunityCostLogger {

    private static final Logger log = LoggerFactory.getLogger(OpportunityCostLogger.class);

    // ADR 2.6: 드롭된 신호는 침묵 처리하지 않고 무조건 기록한다.
    public void logDropped(Signal signal, String reason) {
        log.warn("[기회비용] 신호 드롭 — stockCode={}, strategy={}, reason={}",
                signal.getStockCode(), signal.getStrategyName(), reason);
    }
}
