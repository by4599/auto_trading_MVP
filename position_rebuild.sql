-- Position 복구 절차
-- 사용 시점: DB 불일치 감지 시 또는 장애 복구 후 검증용
-- 실행 환경: H2 콘솔(http://localhost:8080/h2-console) 또는 JDBC 툴
--
-- [검증된 H2 버전]
-- Spring Boot 3.3.x 기준 H2 2.2.x 에서 재귀 CTE(WITH RECURSIVE) 및
-- 윈도우 함수(ROW_NUMBER, COUNT OVER)를 지원한다.
-- 다른 버전 사용 시 Step 2(재귀 CTE) 실행 전 반드시 검증할 것.
--
-- [어떤 Step을 사용해야 하는가]
--
--   v1 MVP (1주 단위, 전량 매도 후 재진입) 패턴:
--     BUY 1주 → SELL 1주 → BUY 1주 순서로만 발생
--     → Step 2A (단순 집계) 와 Step 2B (재귀 CTE) 결과가 동일
--     → Step 2A 사용 권장 (단순하고 오류 가능성 낮음)
--
--   SELL 중간에 BUY가 섞이는 패턴 (v1에서는 PendingOrderRule이 차단):
--     BUY N → SELL 일부 → BUY M 순서
--     → Step 2A (단순 집계) 는 오답, Step 2B (재귀 CTE) 만 정확
--
-- [평균단가 계산 방식]
-- Position.applyBuy(): (기존_수량 × 기존_평균단가 + 신규_수량 × 신규_단가) / 합산_수량
-- Position.applySell(): 수량만 감소, 평균단가 유지
-- 예) BUY 100 @ 10,000 → SELL 50 → BUY 50 @ 12,000
--   applyBuy 방식: (50 × 10,000 + 50 × 12,000) / 100 = 11,000원  ← 정답
--   단순 BUY 집계: (100 × 10,000 + 50 × 12,000) / 150 = 10,666원 ← 오답 (v1에서는 미발생)

-- ─────────────────────────────────────────────────────────────────
-- Step 1. 현재 Position 테이블 조회
-- ─────────────────────────────────────────────────────────────────
SELECT stock_code, quantity, average_price, updated_at
FROM   position
ORDER  BY stock_code;

-- ─────────────────────────────────────────────────────────────────
-- Step 2A. v1 MVP 단순 집계 (권장: BUY→SELL→BUY 완전 사이클 패턴)
--
-- PendingOrderRule이 "보유 중 재매수"를 차단하므로 v1에서는
-- BUY N주 전량 매도 후 재진입 패턴만 발생한다.
-- 이 경우 단순 집계 결과가 applyBuy() 결과와 일치한다.
-- ─────────────────────────────────────────────────────────────────
SELECT
    stock_code,
    SUM(CASE WHEN side = 'BUY'  THEN  filled_quantity
             WHEN side = 'SELL' THEN -filled_quantity END)   AS expected_quantity,
    SUM(CASE WHEN side = 'BUY' THEN filled_quantity * filled_price ELSE 0 END)
    / NULLIF(SUM(CASE WHEN side = 'BUY' THEN filled_quantity ELSE 0 END), 0) AS expected_avg_price
FROM   order_history
WHERE  status = 'FILLED'
GROUP  BY stock_code
HAVING SUM(CASE WHEN side = 'BUY'  THEN  filled_quantity
                WHEN side = 'SELL' THEN -filled_quantity END) > 0
ORDER  BY stock_code;

-- ─────────────────────────────────────────────────────────────────
-- Step 2B. 재귀 CTE 재현 — SELL 중간 BUY가 섞이는 패턴 (v1 이후)
--
-- applyBuy/applySell 순서를 시간 순으로 재생한다.
-- H2 2.2.x(Spring Boot 3.3.x 내장) 에서 검증 필요.
-- ─────────────────────────────────────────────────────────────────
WITH fills AS (
    SELECT
        stock_code,
        side,
        filled_quantity,
        filled_price,
        filled_at,
        ROW_NUMBER() OVER (PARTITION BY stock_code ORDER BY filled_at, id) AS rn,
        COUNT(*) OVER (PARTITION BY stock_code)                             AS total_rows
    FROM   order_history
    WHERE  status = 'FILLED'
),
-- 재귀 CTE로 Position.applyBuy/applySell 순서 재현
simulation(stock_code, rn, total_rows, qty, avg_price) AS (
    -- 초기값: 각 종목의 첫 번째 체결
    SELECT
        f.stock_code,
        f.rn,
        f.total_rows,
        CASE WHEN f.side = 'BUY'  THEN f.filled_quantity
             WHEN f.side = 'SELL' THEN -f.filled_quantity
             ELSE 0 END,
        CASE WHEN f.side = 'BUY' THEN f.filled_price ELSE 0.0 END
    FROM fills f
    WHERE f.rn = 1
    UNION ALL
    -- 다음 체결 적용: applyBuy(가중평균 재계산) / applySell(수량만 감소)
    SELECT
        f.stock_code,
        f.rn,
        f.total_rows,
        CASE
            WHEN f.side = 'BUY'  THEN s.qty + f.filled_quantity
            WHEN f.side = 'SELL' THEN s.qty - f.filled_quantity
            ELSE s.qty
        END,
        CASE
            WHEN f.side = 'BUY' THEN
                -- applyBuy: (기존_비용 + 신규_비용) / 신규_합산수량
                (s.qty * s.avg_price + f.filled_quantity * f.filled_price)
                / (s.qty + f.filled_quantity)
            ELSE
                s.avg_price     -- applySell: 평균단가 유지
        END
    FROM simulation s
    JOIN fills f ON f.stock_code = s.stock_code AND f.rn = s.rn + 1
)
-- 각 종목의 최종 상태 (마지막 체결 후)
SELECT stock_code, qty AS expected_quantity, avg_price AS expected_avg_price
FROM   simulation
WHERE  rn = total_rows
  AND  qty > 0
ORDER  BY stock_code;

-- ─────────────────────────────────────────────────────────────────
-- Step 3. 불일치 항목 식별 (Step 1 vs Step 2 비교)
-- ─────────────────────────────────────────────────────────────────
WITH fills AS (
    SELECT stock_code, side, filled_quantity, filled_price, filled_at,
           ROW_NUMBER() OVER (PARTITION BY stock_code ORDER BY filled_at, id) AS rn,
           COUNT(*) OVER (PARTITION BY stock_code) AS total_rows
    FROM   order_history WHERE status = 'FILLED'
),
simulation(stock_code, rn, total_rows, qty, avg_price) AS (
    SELECT f.stock_code, f.rn, f.total_rows,
           CASE WHEN f.side='BUY' THEN f.filled_quantity ELSE -f.filled_quantity END,
           CASE WHEN f.side='BUY' THEN f.filled_price ELSE 0.0 END
    FROM fills f WHERE f.rn = 1
    UNION ALL
    SELECT f.stock_code, f.rn, f.total_rows,
           CASE WHEN f.side='BUY'  THEN s.qty + f.filled_quantity
                WHEN f.side='SELL' THEN s.qty - f.filled_quantity ELSE s.qty END,
           CASE WHEN f.side='BUY'
                THEN (s.qty * s.avg_price + f.filled_quantity * f.filled_price)
                     / (s.qty + f.filled_quantity)
                ELSE s.avg_price END
    FROM simulation s
    JOIN fills f ON f.stock_code = s.stock_code AND f.rn = s.rn + 1
),
expected AS (
    SELECT stock_code, qty AS expected_qty, avg_price AS expected_avg
    FROM   simulation WHERE rn = total_rows AND qty > 0
)
SELECT
    p.stock_code,
    p.quantity            AS current_qty,
    e.expected_qty,
    p.quantity - e.expected_qty           AS qty_diff,
    ROUND(p.average_price, 2)             AS current_avg,
    ROUND(e.expected_avg, 2)              AS expected_avg,
    ROUND(p.average_price - e.expected_avg, 2) AS avg_diff
FROM   position p
JOIN   expected e ON p.stock_code = e.stock_code
WHERE  p.quantity <> e.expected_qty
    OR ABS(p.average_price - e.expected_avg) > 1.0;

-- ─────────────────────────────────────────────────────────────────
-- Step 4. 불일치 수정 (Step 3 결과 확인 후 실행, 주석 해제 필요)
-- ─────────────────────────────────────────────────────────────────
-- UPDATE position
--    SET quantity      = <expected_qty>,
--        average_price = <expected_avg>,
--        updated_at    = NOW()
--  WHERE stock_code = '<종목코드>';

-- ─────────────────────────────────────────────────────────────────
-- Step 5. 청산 완료 종목 정리 (주석 해제 후 실행)
-- ─────────────────────────────────────────────────────────────────
-- DELETE FROM position
--  WHERE stock_code NOT IN (
--      SELECT DISTINCT stock_code FROM position p2
--      WHERE  quantity > 0
--  );
