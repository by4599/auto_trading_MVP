package com.trading.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class TradingStatusManager {

    private static final Logger log = LoggerFactory.getLogger(TradingStatusManager.class);

    private final AtomicReference<TradingMode> mode = new AtomicReference<>(TradingMode.RUNNING);

    public TradingMode getCurrentMode() {
        return mode.get();
    }

    public void changeMode(TradingMode newMode) {
        TradingMode previous = mode.getAndSet(newMode);
        if (previous != newMode) {
            log.warn("[TradingStatusManager] 모드 전환: {} → {}", previous, newMode);
        }
    }
}
