package com.trading.risk;

public enum TradingMode {
    RUNNING,
    /** 크래시 후 재기동 대기 / 재가동 게이트 통과 직후 — 신규 매수만 금지, 손절·타임컷·강제청산 감시는 유지 */
    SAFE_MODE,
    FORCE_LIQUIDATING,
    EMERGENCY_STOPPED
}
