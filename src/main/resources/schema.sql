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

-- DART 공시 (이벤트 백테스트 B-4 표본 — 자동 삭제하지 않음)
CREATE TABLE IF NOT EXISTS disclosure_item (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    stock_code   VARCHAR(20)  NOT NULL,
    corp_name    VARCHAR(100),
    receipt_no   VARCHAR(20)  NOT NULL UNIQUE,
    report_name  TEXT         NOT NULL,
    disclosed_at DATE         NOT NULL,
    fetched_at   TIMESTAMP    NOT NULL,
    sentiment    VARCHAR(10),
    event_type   VARCHAR(30),             -- 이벤트 택소노미 (B-4 통계 그룹 키)
    PRIMARY KEY (id)
);

-- 이벤트 유형 레지스트리 (B-4 통계 + 승격 상태 — PROMOTED는 사람만, 게이트 G2)
CREATE TABLE IF NOT EXISTS event_type_registry (
    event_type  VARCHAR(30)  NOT NULL,
    status      VARCHAR(12)  NOT NULL DEFAULT 'RECORDED',
    samples     INT          NOT NULL DEFAULT 0,
    win_rate_d5 DOUBLE       NOT NULL DEFAULT 0,
    median_d1   DOUBLE       NOT NULL DEFAULT 0,
    median_d5   DOUBLE       NOT NULL DEFAULT 0,
    median_d10  DOUBLE       NOT NULL DEFAULT 0,
    median_d20  DOUBLE       NOT NULL DEFAULT 0,
    p25_d5      DOUBLE       NOT NULL DEFAULT 0,
    p25_d20     DOUBLE       NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (event_type)
);

CREATE INDEX IF NOT EXISTS idx_disclosure_stock_code ON disclosure_item (stock_code);
CREATE INDEX IF NOT EXISTS idx_disclosure_date       ON disclosure_item (disclosed_at);

-- ──────────────────────────────────────────────────────────────────────────────

-- 매매 유니버스 (자동 매매 대상 — 편입/제외는 웹 UI에서 사람이 직접, 게이트 G1)
CREATE TABLE IF NOT EXISTS trading_universe (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    stock_code VARCHAR(20)  NOT NULL UNIQUE,
    stock_name VARCHAR(100),
    added_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_universe_stock_code ON trading_universe (stock_code);

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
