# 16_quant — 종가 매수 → 다음 날 시가 매도 (오버나잇·"종가베팅") 사전 선별

> 작성 2026-09-15 · 리더가 SQL로 직접 수행한 **사전 선별**이다. strategy-quant 엔진 백테스트 판정이 아니며
> 워크포워드가 없다(전 구간 표본 내). **§4 판정의 대용으로 인용하지 말 것.**
> 운영 DB는 건드리지 않았다 — `backtest-db.mv.db`(2026-09-15 00:54 상태)와
> `backup/trading-db-20260915_005422.zip`을 세션 임시 폴더에 복사해 H2 2.2.224 Shell로 조회했다.

## 0. 결론

| 변형 | 판정 | 한 줄 근거 |
|---|---|---|
| 매일 전 종목 오버나잇 | ❌ 기각 | 밤사이 평균 +0.157% < 가장 싼 현실 왕복 비용 0.23%. PF 0.88, 7.4년 복리 −77.4% |
| 지수 오버나잇 (ETF 대용) | ⚠ 보류 | 지수 산술로 KOSPI 연 21.4%지만 실제 ETF 가격 미검증 · MDD 20.1% · 2022 손실 |
| 선별 S1 (당일 +3% · 고가 1% 이내 마감 · 거래량 20일 평균 2배) | ⚠ 보류 (백로그) | 1,264건 PF 1.64(비용 0.23%)/1.28(0.41%)이나 선견편향·생존편향·표본 내 3중 편향 |

## 1. 데이터·방법

- 원천: `candle_history` 일봉, **원주가**(`KisCandleHistoryClient.java:101` `FID_ORG_ADJ_PRC=1`). 55종목 + KOSPI·KOSDAQ 지수,
  2019-04-01~2026-09-04, 종목-밤 94,859개.
- 한 밤 수익 `gap = 다음 거래일 시가 ÷ 오늘 종가 − 1`. 액면분할·분할재상장 등 |gap| ≥ 30% 17건 제외(§7 Q-J),
  다음 봉까지 7일 초과 공백 제외.
- 비용 A (경매 체결 이상치, 2026 세율): 수수료 0.015%×2 + 매도세 0.20%, 슬리피지 0 → 왕복 ≈ 0.23%
- 비용 B (프로젝트 표준 `BacktestCosts`): 슬리피지 0.1%×2 + 수수료 0.015%×2 + 매도 제세 0.18% → 왕복 ≈ 0.41%
- ETF 비용: 수수료만 0.03% (국내 주식형 ETF 매도는 거래세 면제)
- PF = 비용 차감 후 건별 이익 합 ÷ 손실 합. 같은 밤의 여러 종목은 시장 갭을 공유하므로 건수가 독립 표본 수보다 크다
  (S1은 1,264건이지만 신호가 난 밤은 720일).
- **검산**: KOSPI 밤 복리(+629.9%) × 낮 복리(−58.5%) = 3.029배 vs 보유 3.084배 — 경계 1일 차이로 일치.

## 2. 매일 전 종목 — 기각

전체: 평균 gap +0.157% · 중앙값 +0.057% · 절사(|gap|<10%) +0.139% · 낮(시가→종가) −0.014% · 상승 50.2%/보합 11.4%

| 비용 | 건당 평균 | 승률 | PF | 복리(1,824밤, 전 종목 균등) | MDD |
|---|---|---|---|---|---|
| A 0.23% | −0.073% | 44.0% | 0.88 | −77.4% (연 −18.2%) | −85.9% |
| B 0.41% | −0.253% | — | 0.64 | −99.2% | −99.2% |

연도별 (평균 gap / 비용 A 건당 / PF A): 2019 0.086/−0.144/0.69 · 2020 0.201/−0.029/0.95 · 2021 0.181/−0.049/0.89 ·
**2022 −0.036/−0.265/0.60** · 2023 0.141/−0.090/0.81 · 2024 0.092/−0.138/0.77 · 2025 0.217/−0.014/0.98 · 2026 0.445/+0.214/1.21
→ 8개 연도 중 7개 손실. 2026만 플러스(상승장).

- 지수 레짐(전일 KOSPI vs MA120): 위 55,746건 gap 0.211 · A −0.019 · PF 0.97 / 아래 33,620건 0.089 · −0.141 · 0.78
- 밤 길이: 평일 밤 +0.193% (A −0.038) · 주말 −0.010% (A −0.240) · 연휴 +0.243% (A +0.013)
- 당일 등락 구간별 (건수 / 평균 gap / A 건당 / PF A / B 건당):

| 당일 등락 | 건수 | 평균 gap | A 건당 | PF A | B 건당 |
|---|---|---|---|---|---|
| < −5% | 4,129 | 0.378 | +0.147 | 1.14 | −0.033 |
| −5~−2% | 15,064 | 0.096 | −0.134 | 0.80 | −0.314 |
| −2~0% | 26,861 | 0.111 | −0.119 | 0.78 | −0.299 |
| 0~2% | 29,327 | 0.116 | −0.114 | 0.78 | −0.294 |
| 2~5% | 13,248 | 0.235 | +0.004 | 1.01 | −0.175 |
| 5~10% | 4,911 | 0.314 | +0.084 | 1.11 | −0.096 |
| 10~25% | 1,153 | 0.447 | +0.216 | 1.20 | +0.036 |
| ≥ 25% | 111 | 2.255 | +2.020 | 2.28 | +1.836 |

U자형 — 크게 빠진 날(반등)과 크게 오른 날(연속)에만 갭이 크다. "고가 근처 마감"만으로는 오히려 나쁘다
(S3 고가 0.5% 이내 마감, 등락 무관: 15,503건 gap 0.047 · A −0.183 · PF 0.68).

## 3. 지수 오버나잇 (ETF 대용) — 보류

| 계열 | 밤 복리(비용 전) | ETF 비용 차감 | 연환산 | MDD |
|---|---|---|---|---|
| KOSPI 밤만 | +629.9% | +322.3% | 21.41% | −20.1% |
| KOSDAQ 밤만 | +877.2% | +465.4% | 26.28% | −14.3% |
| KOSPI 낮만 (비용 없음) | −58.5% | — | — | −65.5% |
| KOSPI 보유 | +208.4% | — | 16.38% | — |
| KOSDAQ 보유 | +10.4% | — | — | — |

ETF 비용 차감 연도별 PF — KOSPI 1.04~1.54 (2022 0.88) · KOSDAQ 1.23~2.03 (2022 0.97).
보류 이유: ① 지수 시가는 거래 가능한 가격이 아니다 — ETF 시가 경매가는 LP 호가 의무 밖이라 NAV와 어긋날 수 있다.
실제 코스닥150 ETF 공개 백테스트(멘탈헷징, 2024-01)는 연 7%·MDD −21%로 지수 산술보다 훨씬 약했다.
② KOSDAQ 종합지수는 추종 상품이 사실상 코스닥150뿐이라 대용이 성립 안 한다. ③ KOSPI MDD 20.1% > §4 15%.
④ 현 유니버스(개별 종목) 설계 밖. 검증하려면 KODEX 200(069500) 등 실제 ETF 일봉 적재가 선행.

## 4. 선별 S1 — 편향 점검

정의: `dayret ≥ 3%` AND `종가 ≥ 고가 × 0.99` AND `거래량 ≥ 직전 20봉 평균 × 2` AND `직전 20봉 평균 > 0`.
⚠ 최초 집계(1,266건)에는 20봉 평균 거래량 0(거래정지 후 재개) 2건이 섞여 건당 +18.6%로 결과를 부풀렸다 — 제외 후 1,264건 사용.

### 4.1 기본 성적

| 구간 | 건수 | 평균 gap | 중앙값 | A 건당 | 승률 A | PF A | B 건당 | PF B |
|---|---|---|---|---|---|---|---|---|
| 전체 | 1,264 | 0.599 | 0.267 | +0.368 | 51.6% | 1.64 | +0.187 | 1.28 |
| 2019~2025 | 1,164 | 0.506 | 0.246 | +0.275 | 50.9% | 1.51 | +0.094 | 1.15 |
| 2026 | 100 | 1.686 | 1.155 | +1.452 | 60.0% | 2.45 | +1.270 | 2.19 |

연도별 (건수 / A 건당 / PF A / B 건당): 2019 103/0.318/1.89/0.138 · 2020 193/0.381/1.65/0.201 · 2021 177/0.222/1.48/0.042 ·
**2022 132/−0.157/0.75/−0.336** · 2023 218/0.216/1.52/0.036 · 2024 163/0.478/1.85/0.298 · 2025 178/0.391/1.54/0.210 · 2026 100/1.452/2.45/1.270

### 4.2 민감도 (±20% 한 축씩)

| 변형 | 건수 | PF A | PF B |
|---|---|---|---|
| 기준 (+3% · 고가 1% · 거래량 2배) | 1,264 | 1.64 | 1.28 |
| 등락 +2.4% / +3.6% | 1,331 / 1,176 | 1.57 / 1.66 | 1.22 / 1.31 |
| 고가 0.8% / 1.2% 이내 | 1,022 / 1,484 | 1.66 / 1.62 | 1.31 / 1.26 |
| 거래량 1.6배 / 2.4배 | 1,982 / 838 | 1.45 / 1.91 | **1.13** / 1.49 |
| 거래량 조건 제거 | 6,481 | 1.09 | 0.85 |
| 고가 조건 제거 | 3,514 | 1.43 | 1.13 |

→ 엣지의 원천은 **거래량 급증**이다(제거 시 소멸, 강화 시 단조 증가). 비용 A에서는 인접 전부 PF ≥ 1.45,
비용 B에서는 거래량 1.6배가 1.13으로 §4 민감도 1.15 미달.

### 4.3 생존편향

- 55종목 중 44종목이 S1 합산 플러스, 종목별 건당 평균의 중앙값 +0.387%
- 기여 상위: 277810(18건 합 +48.1%p) · 066570(35건 +45.6%p) · 000250(29건 +38.5%p) · 067310(29건 +31.3%p) · 028300(22건 +27.8%p)
- 상위 5종목 제외: 1,131건 · A +0.242 PF 1.40 · B +0.062 PF 1.09
- 종목 7년 수익률 기준 반분 (분할 밤 제외 근사 수익률, 최하 293490 −81% ~ 최상 042700 +4,766%):

| 반쪽 | 건수 | A 건당 | PF A | B 건당 | PF B |
|---|---|---|---|---|---|
| 뒤처진 28종목 | 615 | +0.251 | 1.38 | +0.071 | 1.09 |
| 앞선 27종목 | 649 | +0.479 | 1.95 | +0.298 | 1.50 |

→ 사후 승자에서 엣지가 두 배. 상장폐지 종목이 없는 표본이라 실제는 뒤처진 쪽 수치에 더 가깝다고 봐야 한다.

### 4.4 지수 레짐 (전일 KOSPI vs MA120)

| 레짐 | 건수 | A 건당 | PF A | B 건당 | PF B |
|---|---|---|---|---|---|
| 위 | 851 | +0.474 | 1.88 | +0.294 | 1.47 |
| 아래 | 362 | +0.122 | 1.18 | −0.058 | 0.93 |
| 판단 불가(초기 120일) | 51 | +0.341 | 1.90 | +0.160 | 1.33 |

MA120 위에서만: 비용 A 기준 8개 연도 전부 플러스(2022도 24건 +0.284 · PF 2.91). 비용 B 연도별은 미측정.

### 4.5 밤 길이

평일 밤 987건 A +0.304 PF 1.52 · 주말 250건 +0.573 PF 2.02 · 연휴 27건 +0.803 PF 2.59 — 전체 표본과 달리 금요일 제외 불필요.

### 4.6 현실 사이징 (종목당 10% · 최대 5종목, 거래량 배수 순, 레짐 필터 없음)

| 구간 | 신호 밤 | 비용 A 총수익 (연환산) | MDD | 비용 B 총수익 (연환산) | MDD |
|---|---|---|---|---|---|
| 2019~2025 | 673 | +36.5% (4.72%) | −5.3% | +11.4% (1.61%) | −8.6% |
| 전체 | 720 | +51.8% (5.78%) | −5.3% | +21.9% (2.70%) | −8.6% |

신호가 나는 밤은 전체의 39.5%, 신호 밤당 평균 1.76종목 — 자금 대부분이 밤에 논다. 계좌 기여가 작다.

### 4.7 종가 선견편향 — 분봉 17일 실측 (2026-08-04~09-11, 935 종목-일)

S1은 **최종 종가·하루 전체 거래량**으로 종목을 고르고 같은 종가에 산다. 실제 판단 가능 시각은 마감 경매(15:20~15:30) 전이다.

| 대상 | 종목-일 | 경매 중 가격 변화 평균 | 평균 절댓값 | 90분위 절댓값 | 경매 거래량 비중 |
|---|---|---|---|---|---|
| 전체 | 935 | −0.037% | 0.247% | 0.597% | 8.3% |
| 15:19에 강함 (시가→15:19 ≥ 3%, 고가 근처) | 102 | +0.008% | 0.269% | 0.657% | 10.6% |

- 강한 날 판정(시가 대비 근사)이 15:19와 종가에서 갈리는 비율: 둘 다 91 · 15:19만 11 · 종가만 10 → 약 19%가 뒤집힘
- 다음 날 갭(일화 수준 — 표본 작고, 08-24→08-27·09-08→09-10 두 쌍은 하룻밤이 아님):
  둘 다 강함 83건 평균 −0.705%·중앙 −1.056% · 15:19만 10건 −0.630% · 종가만 9건 −0.263% · 나머지 668건 −0.052%
  → 일봉 7.4년 결과와 **반대 방향**. 17일은 시장 국면 몇 개에 불과해 결론 불가.
- 엣지(+0.37%/건)가 경매 흔들림(±0.25%)과 같은 자릿수 — 15:19 판정으로 다시 재면 크게 줄 수 있다.
- 필요한 분봉 양: S1 발생률 1.333%/종목-밤 → 55종목 0.733건/거래일 → **신호 100건 ≈ 136거래일**
  (MA120 필터 시 ≈ 203거래일). 현재 17일, PC가 꺼진 날은 수집 누락.

### 4.8 §4 대입 (사전 선별, 비용 B 기준)

| 지표 | 기준 | S1 | S1 + MA120 |
|---|---|---|---|
| 트레이드 | ≥ 100 | 1,264 ✓ | 851 ✓ |
| PF (비용 후) | ≥ 1.3 | 1.28 ✗ | 1.47 ✓ |
| 기대값 | > 0, 워크포워드 검증 구간 | +0.187% — 표본 내라 채점 불가 | +0.294% — 동일 |
| MDD | ≤ 15% | 8.6% (10%×5 사이징) ✓ | 미측정 |
| 민감도 | 인접 PF ≥ 1.15 | 1.13~1.49 ✗ | 미측정 |

## 5. 구현하려면 걸리는 것 (코드 대조)

- `src/main/java/com/trading/risk/MarketCloseRule.java:33` — 마감 10분 전(평시 15:20) 이후 매수 거부, **칸 면제 없음** → 마감 경매 참여 불가
- `src/main/java/com/trading/risk/PostTimeCutBuyRule.java:53` — 15:15 이후 매수 거부, `multiDayHold` 칸만 면제
- `src/main/java/com/trading/scheduler/TimeCutScheduler.java:155` — 15:15 전량 매도에서 `multiDayHold` 칸만 제외
- `src/main/java/com/trading/order/KisOrderClientImpl.java:31` — 시장가(`ORD_DVSN=01`)만 사용. 모의투자의 경매·시간외 주문 지원 여부는 공개 자료로 확인 못 함
- ADR-001 §2.1 (2026-08-07 개정) — A동 다일 추세 40% · B동 당일 단타 30% · 현금 30%. 1박 보유 방식이 들어갈 칸이 없다 → ADR 개정은 사람 결정
- CLAUDE.md 결함 5 — 모의에서 매도 체결가를 얻지 못해, 이 방식의 엣지가 사는 곳(경매 체결가)을 모의로 검증할 수 없다
- 야간 보유 중에는 `StopLossMonitor`·`RiskMonitor`가 개입할 수 없다 — 손실은 시가에 이미 확정된다

## 6. 부수 발견

- `src/main/java/com/trading/backtest/BacktestCosts.java:13` 매도 제세 0.18% vs 2026-01-01부터 법정 0.20%(코스피 0.05%+농특세 0.15%, 코스닥 0.20%).
  돈치안 비용 여유(§15.4, +0.09%p에 탈락)의 약 1/4. 별도 작업 칩으로 등록(이번 작업에서 수정하지 않음).

## 7. 재현 SQL (핵심)

H2 2.2.224 `org.h2.tools.Shell`, DB 사본 대상. 구간별·민감도·레짐 집계는 아래 테이블에 같은 패턴
(`COUNT`, `AVG(net)`, `PF = SUM(양수) / −SUM(음수)`)을 적용했다.

```sql
CREATE TABLE ov AS
SELECT stock_code, d, nd, o, h, c, v, pc, v20,
  nxo / c - 1 AS gap, c / pc - 1 AS dayret, c / o - 1 AS intra,
  nxo * (1 - 0.00015 - 0.0020) / (c * (1 + 0.00015)) - 1 AS net_a,
  nxo * (1 - 0.001) * (1 - 0.00015 - 0.0018) / (c * (1 + 0.001) * (1 + 0.00015)) - 1 AS net_b,
  nxo * (1 - 0.00015) / (c * (1 + 0.00015)) - 1 AS net_etf,
  DATEDIFF(DAY, d, nd) AS gapdays
FROM (
  SELECT stock_code, candle_date AS d, open_price AS o, high_price AS h, close_price AS c, volume AS v,
    LAG(close_price) OVER (PARTITION BY stock_code ORDER BY candle_date) AS pc,
    LEAD(open_price) OVER (PARTITION BY stock_code ORDER BY candle_date) AS nxo,
    LEAD(candle_date) OVER (PARTITION BY stock_code ORDER BY candle_date) AS nd,
    AVG(CAST(volume AS DOUBLE)) OVER (PARTITION BY stock_code ORDER BY candle_date
                                      ROWS BETWEEN 20 PRECEDING AND 1 PRECEDING) AS v20
  FROM candle_history WHERE timeframe = 'DAILY' AND open_price > 0 AND close_price > 0
) x
WHERE nd IS NOT NULL AND DATEDIFF(DAY, d, nd) <= 7 AND ABS(nxo / c - 1) < 0.3;

-- 전일 기준 지수 레짐 (당일 종가를 쓰면 판단 시점보다 늦은 정보)
CREATE TABLE reg AS
SELECT d, LAG(kc) OVER (ORDER BY d) AS pkc, LAG(ma120) OVER (ORDER BY d) AS pma, LAG(cnt) OVER (ORDER BY d) AS pcnt
FROM (SELECT candle_date AS d, close_price AS kc,
        AVG(close_price) OVER (ORDER BY candle_date ROWS BETWEEN 119 PRECEDING AND CURRENT ROW) AS ma120,
        COUNT(*) OVER (ORDER BY candle_date ROWS BETWEEN 119 PRECEDING AND CURRENT ROW) AS cnt
      FROM candle_history WHERE timeframe = 'DAILY' AND stock_code = 'KOSPI') k;

CREATE TABLE s1 AS
SELECT stock_code, d, gapdays, dayret, gap, net_a, net_b, CAST(v AS DOUBLE) / v20 AS vr, EXTRACT(YEAR FROM d) AS yr
FROM ov
WHERE stock_code NOT IN ('KOSPI', 'KOSDAQ') AND dayret >= 0.03 AND c >= h * 0.99 AND v20 > 0 AND v >= 2 * v20;

-- 현실 사이징: 밤마다 거래량 배수 상위 5종목, 종목당 10%
CREATE TABLE s1p AS
SELECT d, yr, SUM(net_a) * 0.10 AS ra, SUM(net_b) * 0.10 AS rb
FROM (SELECT d, yr, net_a, net_b, ROW_NUMBER() OVER (PARTITION BY d ORDER BY vr DESC) AS rk FROM s1) t
WHERE rk <= 5 GROUP BY d, yr;
-- 복리: EXP(SUM(LN(1 + r)) OVER (ORDER BY d ...)), MDD: MIN(eq / GREATEST(1, 누적최대 eq) − 1)

-- 분봉(백업 사본): 15:19 가격 vs 15:30 경매 종가
CREATE TABLE md AS
SELECT stock_code, d,
  MAX(CASE WHEN rn_first = 1 THEN o END) AS day_open,
  MAX(CASE WHEN t = TIME '15:30:00' THEN c END) AS day_close,
  MAX(CASE WHEN t = TIME '15:19:00' THEN c END) AS p1519,
  MAX(CASE WHEN t <= TIME '15:19:00' THEN h END) AS hi1519,
  MAX(h) AS hi_day,
  SUM(CASE WHEN t <= TIME '15:19:00' THEN v ELSE 0 END) AS vol1519,
  SUM(v) AS vol_day
FROM (SELECT stock_code, candle_date AS d, candle_time AS t, open_price AS o, high_price AS h, close_price AS c, volume AS v,
        ROW_NUMBER() OVER (PARTITION BY stock_code, candle_date ORDER BY candle_time) AS rn_first
      FROM candle_history WHERE timeframe = 'MINUTE') x
GROUP BY stock_code, d;
```

제외한 |gap| ≥ 30% 17건: 028300 2021-03-12 · 042700 2022-04-05 · 058470 2025-04-24 · 086520 2021-05-27, 2024-04-24 ·
089030 2022-07-29 · 112040 2021-09-10 · 141080 2020-06-15 · 196170 2020-07-22, 2021-03-23 · 207940 2025-11-21(+47%) ·
247540 2022-06-24 · 263750 2021-04-15 · 319660 2022-09-20 · 348210 2021-01-12 · 403870 2023-03-15 · 950160 2022-10-24(+100%)

## 8. 외부 근거

- Lou·Polk·Skouras, "A Tug of War: Overnight Versus Intraday Expected Returns" — 이상 현상 수익이 밤/낮 한쪽에 몰리고 부호가 반대: https://personal.lse.ac.uk/polk/research/TugOfWar.pdf
- Alpha Architect, "Trading Costs Wipe Out the Overnight Return Anomaly" — 비용 반영 시 오버나잇 전략 누적 수익이 음수로: https://alphaarchitect.com/trading-costs-wipe-out-the-overnight-return-anomaly/
- Berkman·Koch·Tuttle·Zhang (JFQA 2012), "Paying Attention" — 관심 집중 종목은 밤에 오르고 장중 되돌림: https://papers.ssrn.com/sol3/papers.cfm?abstract_id=1625495
- 한국주식시장의 시가효과에 관한 연구 (KCI) — 밤수익률 양, 낮수익률 0 또는 음, 코스닥에서 더 현저: https://www.kci.go.kr/kciportal/landing/article.kci?arti_id=ART001634924
- 투자자 관심과 주식수익률의 반전현상 (KCI, 코스닥): https://www.kci.go.kr/kciportal/landing/article.kci?arti_id=ART002182863
- 멘탈헷징, 코스닥 종가 베팅 백테스트 (코스닥150 ETF): https://mentalhedge.com/blog/2024/01/13/kosdaq-overnight-position-backtesting/
- 2026 증권거래세 환원: https://news.nate.com/view/20251201n26338 · https://v.daum.net/v/20251201095752428
