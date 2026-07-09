-- auto_trading v1 DDL
-- H2 / MySQL 호환. Spring Boot 설정: spring.sql.init.schema-locations=classpath:schema.sql

CREATE TABLE IF NOT EXISTS order_history (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    version          BIGINT        NOT NULL DEFAULT 0,
    stock_code       VARCHAR(20)   NOT NULL,
    side             VARCHAR(10)   NOT NULL,   -- BUY / SELL
    quantity         INT           NOT NULL,
    order_no         VARCHAR(50),
    status               VARCHAR(20)    NOT NULL,   -- ACCEPTED / PARTIAL_FILLED / CANCEL_REQUESTED / FILLED / CANCELLED / CANCEL_FAILED / FAILED
    filled_quantity      INT            NOT NULL DEFAULT 0,
    filled_price         DECIMAL(12, 2),
    requested_at         TIMESTAMP      NOT NULL,
    filled_at            TIMESTAMP,
    cancel_requested_at  TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_order_history_status   ON order_history (status);
CREATE INDEX IF NOT EXISTS idx_order_history_order_no ON order_history (order_no);

-- ──────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS position (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    version       BIGINT        NOT NULL DEFAULT 0,
    stock_code    VARCHAR(20)   NOT NULL UNIQUE,
    quantity      INT           NOT NULL DEFAULT 0,
    average_price DECIMAL(12, 2) NOT NULL DEFAULT 0,
    stop_price    DECIMAL(12, 2),          -- ATR 손절선 (StopLossArmer가 체결 후 기록)
    updated_at    TIMESTAMP     NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_position_stock_code ON position (stock_code);

-- ──────────────────────────────────────────────────────────────────────────────

-- 당일 시작 자산 (dailyPnlPercent 기준값). 날짜 키 교체가 곧 일일 리셋.
CREATE TABLE IF NOT EXISTS daily_equity (
    trade_date   DATE           NOT NULL,
    start_equity DECIMAL(16, 2) NOT NULL,
    PRIMARY KEY (trade_date)
);

-- 포트폴리오 영속 상태 (peakEquity 등). ADR-001 2.2: peakEquity는 영구 보존.
CREATE TABLE IF NOT EXISTS portfolio_state (
    state_key   VARCHAR(50)    NOT NULL,
    state_value DECIMAL(16, 2) NOT NULL,
    PRIMARY KEY (state_key)
);
