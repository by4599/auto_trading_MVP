# 23_impl — 모드 전환 이력 · 매수 차단 이력 · 거래 기준 성적(추정)

> 작업 2026-09-22 KST · 브랜치 `backtest/regime-filter-and-validation`
> 승인 계획 `mvp-quiet-engelbart.md` Step 2·3 · 선행 `19_impl` / 감사 `20_audit`

## 0. 한 줄

**"왜 안 샀나 / 언제 멈췄나 / 어디서 벌고 잃었나"를 화면이 읽을 수 있는 데이터로 만들었다.**
판정 로직은 한 줄도 바꾸지 않았다 — 전부 기록 추가와 읽기 전용 조회다.

테스트 **798개 전부 통과 · 종료 코드 0** (직전 기준선 706 → 내 증분 +92).
**감사 `24_audit`의 M-1·M-2·M-3·L-2 반영 완료 — §12 참고.**
Red-Green 4건 전부 확인. 운영 DB 사본으로 검산: **체결 매도 104건 중 90건 추정 가능(86.5%)**.

---

## 1. 착수 중 발견 — `OpportunityCostLogger`는 **아무도 부르지 않고 있었다**

지시서에는 "지금 `logDropped()`가 `log.warn`으로만 남긴다"고 돼 있었지만, 실제로는
**호출부가 0개**였다. `TradingScheduler:108`에 `// TODO: result.isPass()==false면 거부 사유를
signal_history에 기록`이라는 주석만 있었다. `docs/TRADING-RULES-AUDIT.md`의 **F-14**가
"OpportunityCostLogger 연동 — 미구현"으로 이미 기록해 둔 상태였다.

즉 **저장만 더했으면 영영 한 줄도 안 쌓인다.** 그래서 그 TODO 자리에 호출을 넣었다
(`else` 분기 신설, 판정 코드 무변경). 지시 범위를 한 칸 넘는 변경이라 여기 먼저 적는다.

```java
if (result.isPass()) {
    orderEngine.execute(signal);
} else {
    opportunityCostLogger.logDropped(signal, result.getReason());   // ← 추가
}
```

---

## 2. 바꾼/만든 파일 (300줄 상한 대비)

### 신규 생산 코드 — 전부 300줄 미만 · 함수 최장 20줄 · 중첩 2단계

| 파일 | 역할 | 줄 |
|---|---|---|
| `risk/ModeTransition.java` | 모드 전환 엔티티 | 87 |
| `risk/ModeTransitionRepository.java` | 최신순 조회 | 14 |
| `risk/ModeTransitionRecorder.java` | 저장 + 호출부 출처 추출 (예외 삼킴) | 102 |
| `control/ModeHistoryController.java` | `GET /api/trading/mode-history` | 87 |
| `risk/RiskBlockRecord.java` | 매수 차단 엔티티 | 86 |
| `risk/RiskBlockRecordRepository.java` | 조회 + 보존 정리 | 19 |
| `risk/RiskBlockRecorder.java` | 중복 억제 + 저장 (예외 삼킴) | 106 |
| `risk/RiskRuleNameResolver.java` | 사유 문자열 → 룰 이름 | 57 |
| `risk/RiskBlockRetention.java` | 보존 기간 정리 (23:20) | 63 |
| `control/RiskBlockController.java` | `GET /api/risk/blocks` + 룰별 집계 | 118 |
| `dashboard/SellPriceEstimator.java` | 매도가 추정 (분봉→일봉→측정불가) | 106 |
| `dashboard/EstimatedTrade.java` | 짝지어진 거래 1건 | 39 |
| `dashboard/TradePairer.java` | FIFO 짝짓기 | 123 |
| `dashboard/TradeStats.java` | 승률·손익비 계산 | 61 |
| `dashboard/TradeStatsService.java` | 요약·종목별·칸별 | 162 |
| `dashboard/TradeStatsController.java` | 엔드포인트 3개 | 55 |

**분리 계획 (미리 세워 쪼갠 것)** — 한 파일에 몰았으면 (C)가 340줄을 넘었다:

| 원래 한 덩어리였을 것 | 쪼갠 결과 | 책임 |
|---|---|---|
| `TradeStatsService` 340줄 예상 | `SellPriceEstimator` 106 | 캔들에서 가격 되짚기 |
| | `TradePairer` 123 | FIFO 짝짓기 · 측정 불가 분류 |
| | `TradeStats` 61 | 승률·손익비 산식 |
| | `TradeStatsService` 162 | 기간 자르기 · 응답 조립 |
| | `EstimatedTrade` 39 | 값 객체 |

### 수정 (3개, 전부 "기록만 추가")

| 파일 | 바꾼 것 | 줄 |
|---|---|---|
| `risk/TradingStatusManager.java` | 전환 시 기록 호출 1줄 + 생성자 2개 추가. **`mode.getAndSet` / `previous != newMode` 판정은 무변경** | 26 → 77 |
| `risk/OpportunityCostLogger.java` | 기존 `log.warn` 그대로 두고 저장 위임 추가 | 18 → 61 |
| `scheduler/TradingScheduler.java` | TODO 자리에 `logDropped` 호출 + 생성자 인자 1개 | 122 → 129 |
| `test/.../TradingSchedulerTest.java` | 생성자 인자 추가 (기계적) | — |

### 신규 테스트 (79개)

| 파일 | 건수 | 줄 |
|---|---|---|
| `risk/TradingStatusManagerTest` | 12 | 216 |
| `risk/RiskBlockRecorderTest` | 14 | 255 |
| `risk/RiskRuleNameResolverTest` | 18 | 60 |
| `dashboard/SellPriceEstimatorTest` | 6 | 149 |
| `risk/DiagnosticsQueryTest` (`@DataJpaTest`, 실제 H2) | 5 | 121 |
| `dashboard/TradeStatsServiceTest` | 16 | 371 ⚠ |
| `control/DiagnosticsQueryControllerTest` | 8 | 191 |

⚠ `TradeStatsServiceTest` 371줄은 300줄 상한 초과다. 픽스처(주문·캔들 조립기)를 공유해야 해서
쪼개지 않았다 — 이 저장소 테스트는 원래 300줄을 넘는다(`ShadowPortfolioTest` 637 등,
`BACKLOG.md` [2026-08-14]로 추적 중). **생산 코드는 전부 상한 안이다.**

---

## 3. 새 API 계약 (화면 작업자가 이것만 보고 만들 수 있게)

### (A) `GET /api/trading/mode-history?days=30` — `@Profile("!backtest")`

`days`: 1~365 (기본 30, 범위 밖은 잘라 맞춤). 상한 **500행**.

| 필드 | 뜻 |
|---|---|
| `days` / `from` | 조회 기간 / 하한 날짜 (`2026-08-24`) |
| `count` / `limit` / `truncated` | 받은 행 수 / 상한(500) / 상한에 걸렸는지 |
| `transitions[]` | 최신 먼저 |

`transitions[]` 한 행:

| 필드 | 뜻 | 예 |
|---|---|---|
| `id` | 행 번호 | `12` |
| `at` | 사람용 시각 | `"09/22 09:05:12"` |
| `occurredAt` | ISO 시각 (정렬·계산용) | `"2026-09-22T09:05:12"` |
| `previousMode` / `newMode` | 이전 / 새 모드 | `"RUNNING"` / `"FORCE_LIQUIDATING"` |
| `reason` | 사유 — **지금은 항상 `null`** (§6 참고) | `null` |
| `source` | 트리거 출처 = 바꾼 클래스 | `"LiquidationService"` |

### (B) `GET /api/risk/blocks?days=7` — `@Profile("!backtest")`

`days`: 1~365 (기본 7). 상한 **1000행**.

| 필드 | 뜻 |
|---|---|
| `days` / `from` / `count` / `limit` / `truncated` | 위와 같음 |
| `blockedTotal` | 기간 내 **실제 차단 횟수 합계** (억제분 포함) |
| `byRule[]` | 룰별 집계 — 많이 막은 순 |
| `blocks[]` | 개별 내역, 최신 먼저 |

`byRule[]` 한 행: `{ruleName, blockedCount, recordCount, stockCount}`
— `blockedCount`(실제 횟수) ≠ `recordCount`(저장된 행 수). 화면에는 **`blockedCount`를 보여줄 것.**

`blocks[]` 한 행:
`{id, at, occurredAt, stockCode, ruleName, reason, strategyName, blockedCount}`

### (C) 거래 기준 성적 — `@Profile("paper")`

```
GET /api/performance/trades?days=30      요약
GET /api/performance/by-stock?days=30    종목별
GET /api/performance/by-bucket?days=30   칸별
```

`days`: 1~3650 (기본 30).

**세 응답 모두가 공유하는 머리말 (반드시 화면에 띄울 것)**

| 필드 | 뜻 |
|---|---|
| `estimated` | 항상 `true` |
| `warning` | `"매도 가격은 추정치입니다 — … 정확한 금액은 계좌 기준(/api/performance/account)을 보세요"` |
| `matchingRule` | 짝짓기 규칙 설명 문구 |
| `days` / `from` | 조회 기간 |
| **`unmeasurable`** | **추정 실패로 집계에서 빠진 조각 수** |
| `unmeasurableQuantity` | 그 조각들의 주식 수 합 |
| `unmeasurableReasons[]` | `{reason, count, quantity}` — 왜 빠졌는지별 내역 |

**`/trades` 추가 필드** (= `TradeStats` 공통 블록 + `sellSource`)

| 필드 | 뜻 |
|---|---|
| `totalTrades` / `wins` / `losses` / `breakEven` | 거래 수 · 이긴/진/본전 |
| `winRatePercent` | 승률 (%) — **거래 0건이면 `null`** (0%로 꾸미지 않는다) |
| `totalPnl` / `avgProfit` / `avgLoss` | 총손익 · 평균이익 · 평균손실 (원, 반올림) |
| `payoffRatio` | **손익비** = 평균이익 ÷ 평균손실 — 한쪽이 없으면 `null` |
| `profitFactor` | 총이익 ÷ 총손실 — 한쪽이 없으면 `null` |
| `avgReturnPercent` | 평균 수익률 (%) |
| `sellSource` | `{minute, daily, none}` — 추정을 어디서 얻었나 |

**`/by-stock`** → `stocks[]` = `{stockCode, …위 공통 블록}` (손익 큰 순)
**`/by-bucket`** → `buckets[]` = `{bucket, …공통 블록, displayName}` (예: `"VB"` / `"방식1·돌파"`)

> ⚠ 손익은 **수수료·세금 미반영 총손익**이다. 정확한 금액의 정본은 `/api/performance/account`.

---

## 4. 매도가 추정 규칙 · 폴백 3단계 · 짝짓기 규칙

### 기준 시각 (`TradePairer.fillTimeOf`)

```
filledAt이 있고 requestedAt과 같은 날짜  → filledAt
그 외(= 체결 인지가 날짜를 넘어갔다)      → requestedAt
```

날짜를 넘긴 경우 접수 시각을 쓰는 이유: 모의에서는 체결 확인이 대사(reconcile) 경로로 늦게 와
**날짜를 넘겨 찍힌다**(실측: `id=27057` 09-08 15:29:55 접수 → 09-09 00:00:27 체결 기록).
그대로 두면 다음 날 캔들에서 가격을 찾는다. 전부 시장가 주문이라 접수 직후 체결로 보는 편이
실제에 가깝다. 테스트 `fill_recorded_after_midnight_uses_the_request_date`가 고정한다.

### 폴백 3단계 (`SellPriceEstimator`)

| 단계 | 내용 | `source` |
|---|---|---|
| 1 | 그날 그 종목 **분봉** 중 기준 시각에 **가장 가까운 봉의 종가** | `MINUTE` |
| 2 | 그날 **일봉 종가** | `DAILY` |
| 3 | 둘 다 없으면 **측정 불가** — 0원으로 꾸미지 않고 집계에서 뺀다 | `NONE` |

- **KIS를 부르지 않는다.** `candle_history`만 읽는다(모의 유량 1건/초 보호).
- 조회는 (종목·날짜·주기)별로 **호출마다 새로 만드는 캐시**에 담는다 — 분봉은 하루 391행이라
  매도 한 건마다 다시 읽으면 화면 한 번에 수만 행을 훑는다. 싱글턴에 캐시를 두지 않은 이유는
  동시 요청 간 상태 공유와 메모리 증가를 피하기 위해서다.
- ⚠ **2단계(일봉)는 지금 사실상 죽은 길이다** — 운영 DB(`trading-db`)에는 DAILY 캔들이
  **0건**이다(일봉은 `backtest-db`에만 있다). 코드는 맞고 테스트도 통과하지만, 현재 데이터로는
  발동하지 않는다. §8 검산에서 `DAILY 0건`으로 확인된다.

### 매수-매도 짝짓기 (`TradePairer`)

- **선입선출(FIFO)** — 종목별로 매수를 줄 세우고, 매도가 나오면 가장 오래된 매수부터 채운다.
- 매도 한 건이 매수 여러 건을 덮으면 **거래도 그만큼 나뉜다**(매수 2건을 한 번에 팔면 2건).
- **칸(bucket)은 매수 쪽 것**을 쓴다 — 매도 주문에는 칸이 안 붙는다(실측: 체결 매도 104건 전부 `null`).
- 아직 안 팔린 매수는 **측정 불가가 아니다** — 그냥 열린 포지션이라 집계에 안 들어간다.
- **짝짓기는 항상 전 기간**으로 하고, `days`는 **매도 시각** 기준으로 결과를 자른다.
  주문부터 잘라내면 기간 앞의 매수가 사라져 멀쩡한 거래가 "짝 없음"으로 둔갑한다.

**측정 불가로 세는 것 (매도 수량 기준 3종)**

| 사유 문자열 | 언제 |
|---|---|
| `매도가 추정 불가 — 그 시각의 분봉·일봉이 없음` | 폴백 3단계가 전부 실패 |
| `짝지을 매수 기록 없음` | 매도인데 남은 매수 로트가 없다 |
| `매수 체결가 없음` | 매수 행의 `filled_price`가 0/null (결함 잔재, 실측 4건) |

---

## 5. 차단 이력 중복 억제 — 고른 방식과 이유

**고른 방식: 창(window) 단위 합치기.** 같은 `(종목, 룰)`은 창(**기본 10분**,
`trading.risk-block.suppress-minutes`) 안에서 한 번만 저장하고, 그 사이 몇 번 더 막혔는지는
메모리에 세뒀다가 **다음 저장 행의 `blockedCount`에 실어 보낸다.**

### 왜 이 방식인가 (후보 3개 비교)

| 후보 | 문제 |
|---|---|
| 그냥 매번 저장 | 1초 루프 × 라운드로빈 → 유니버스 1종목이면 하루 23,400행 |
| 분 단위 버킷(분당 1건) | 상한이 20종목 × 390분 = **7,800행/일**로 여전히 크다 |
| **창 합치기 + 횟수 이월** ✅ | 종목·룰 조합 하나당 6창/시간이라 **한 종목이 한 룰에만 걸리면 780행/일**, 하루 동안 막는 룰이 계속 바뀌는 최악에는 20종목 × 14룰 × 6창 × 6.5시간 = **10,920행/일** (감사 24_audit L-3 정정). 그래도 억제 없는 23,400행/일·분버킷 7,800행/일보다 현실 기대값이 훨씬 낮고, **횟수를 잃지 않는다** |

단순 건너뛰기(횟수 이월 없음)를 안 쓴 이유: "어떤 룰이 몇 번 막았나"가 실제보다 작게 나온다.
창이 끝날 때 몰아 쓰는 방식을 안 쓴 이유: 드물게 한 번 막힌 건이 앱 종료 시 영영 기록되지 않는다.
그래서 **첫 차단은 즉시 저장**한다.

- 메모리 상한: 키(`종목|룰`) 500개 초과 시 통째로 비우고 로그(현실 최대는 20 × 14 = 280).
- 창을 **0분으로 두면 억제 없이 매번 저장**한다 — 되돌릴 손잡이를 남겼다(테스트로 고정).

### 보존 기간

`RiskBlockRetention` — **기본 90일**(`trading.risk-block.retention-days`),
매일 **23:20 KST**(`MinuteCandleRetention` 23:10 다음 순서), `@Profile("paper")`.
7거래일 정지 같은 사고를 한 분기 안에서 되짚을 수 있으면 충분하다는 판단.

**모드 전환 이력(`mode_transition`)은 정리하지 않는다** — 하루 몇 건뿐이고,
"언제 멈췄나"는 오래된 것일수록 값어치가 있다.

---

## 6. 모드 전환 기록이 전환을 막지 않음을 어떻게 보장했나

### ① 순서 — 기록은 항상 **전환이 끝난 뒤**

```java
TradingMode previous = mode.getAndSet(newMode);   // ← 여기서 이미 바뀐다 (무변경)
if (previous != newMode) {                        // ← 판정 무변경
    log.warn(...);                                // ← 기존 로그 무변경
    recordQuietly(previous, newMode);             // ← 추가된 한 줄
}
```

### ② try/catch **두 겹**

- `ModeTransitionRecorder.record()` 안 — `repository.save()` 예외를 삼킨다
- `TradingStatusManager.recordQuietly()` — 기록기 자체가 터지는 경우까지 받는다

### ③ 저장은 별도 빈의 `REQUIRES_NEW` 독립 트랜잭션으로

> ⚠ **여기 처음 적었던 근거는 틀렸다.** "`@Transactional`을 안 붙였으니 커밋 실패까지 안쪽
> catch가 받는다, 호출부 9곳은 전부 비트랜잭션"이라고 썼는데, **호출 스택 기준으로 거짓**이다
> (`StopLossArmer`가 트랜잭션 안에서 KIS를 호출하고 그 실패가 `changeMode`를 부른다).
> 감사 `24_audit` M-1이 잡았고 **§12에서 고쳤다.**

지금 구조는 이렇다 — 저장을 **별도 빈**(`ModeTransitionWriter`/`RiskBlockWriter`)으로 떼어
`@Transactional(REQUIRES_NEW)`를 실제로 걸리게 했다. 같은 클래스 안에서 부르면 스프링
프록시를 안 타 애너테이션이 무시되므로 **반드시 별도 빈**이어야 한다. 그러면 바깥 트랜잭션이
잠시 멈춰 있는 동안 INSERT가 독립적으로 커밋/롤백되고, 실패는 `record()`의 catch로 올라온다.
**남의 트랜잭션은 절대 오염되지 않는다.** 자세한 경로와 테스트는 §12.

### ④ 빈이 없어도 기동한다

`ModeTransitionRecorder`/`RiskBlockRecorder`는 `@Profile("!backtest")`라 backtest에는 없다.
주입은 **`ObjectProvider`**로 받는다(`getIfAvailable()`).
`@Nullable`을 쓰지 않은 이유: `org.springframework.lang.Nullable`은 JSR-305 메타 애너테이션을
달고 있는데 이 프로젝트 클래스패스에 `javax.annotation.meta.When`이 없다(컴파일 경고로 확인).
**앱이 떠 있는 상황에서 기동 실패 위험을 만들지 않으려고** 의존이 확실한 `ObjectProvider`로 갔다.
실제 스프링 컨텍스트를 띄워 "기록기 빈 없이도 기동한다"를 테스트로 고정했다
(`TradingStatusManagerTest$Wiring`, `RiskBlockRecorderTest$Wiring`).

### ⑤ 스케줄러 스레드 블로킹 (감사 20_audit M-2 대응)

별도 스레드로 빼지 **않았다.** 감사가 지적한 것은 최대 8초 걸리는 **동기 HTTP**였지
DB 쓰기가 아니고, 같은 스케줄러 스레드에서 도는 `ShadowPortfolio.tick()`·`DailyPnlRecorder`가
이미 같은 방식으로 로컬 H2에 쓴다(수 ms). 새 블로킹 HTTP는 0건이다.

### ⑥ `reason`은 `null`이다 (문서화된 한계)

`changeMode(TradingMode)`는 새 모드만 받고 사유를 받지 않는다. 사유를 채우려면
청산·SAFE_MODE 판정의 심장인 호출부 9곳(`LiquidationService`·`KisApiClient`·
`TradingController`·`ShadowPortfolioReconciler`)을 고쳐야 해서 **지시대로 건드리지 않았다.**
대신 **`StackWalker`로 호출부 클래스를 `source`에 남긴다** — "누가 바꿨나"는 알 수 있다.

⚠ 여기서 함정 하나를 밟고 고쳤다: 처음엔 `startsWith`로 자기 클래스를 걸렀는데
`"TradingStatusManagerTest"`가 `"TradingStatusManager"`로 시작해 함께 걸러졌다.
`equals || startsWith(name + "$$")`(CGLIB 프록시만)로 바꿨다.

---

## 7. 룰 이름 되짚기의 한계 (감사 대비 선고백)

`RiskEngine`은 거부한 룰의 이름을 돌려주지 않고 `RiskResult`에는 사유 문자열 하나뿐이다.
이름을 제대로 얻으려면 `RiskEngine`이나 14개 룰 전부를 고쳐야 하는데 **둘 다 금지**
(CLAUDE.md 아키텍처 규칙 3, 지시서 "RiskEngine 무수정").

그래서 `RiskRuleNameResolver`가 **사유 문자열의 고유 조각으로 룰을 되짚는다.**

- 룰이 메시지를 바꾸면 **조용히 `UNKNOWN`으로 떨어진다.** 사고는 아니다 —
  이름은 집계용 라벨일 뿐이고 **사유 원문은 그대로 저장**되므로 화면에서는 계속 읽힌다.
- 안전망: `RiskRuleNameResolverTest`가 **14개 룰의 실제 메시지 16종**을 표로 고정한다.
  메시지가 바뀌면 이 테스트가 먼저 깨진다.
- `MarketCloseRule`과 `PostTimeCutBuyRule`은 둘 다 시각으로 시작해 헷갈리기 쉬워
  별도 테스트로 구분을 고정했다.

---

## 8. 실제 DB로 검산 (운영 백업 사본, 읽기 전용 → 사용 후 삭제)

`backup/trading-db-20260921_233002.zip`을 임시 폴더에 풀어 H2 읽기 전용
(`ACCESS_MODE_DATA=r`)으로 열었다. **운영 DB도, 실행 중인 앱(PID 17048)도 건드리지 않았다.**
주문·캔들을 CSV로 뽑아 **파이썬으로 같은 규칙을 독립 재구현**해 돌렸다(코드 재사용 아님).
사본과 CSV는 확인 후 전부 지웠다.

### 매도 추정 가능성

| 항목 | 값 |
|---|---|
| 체결 매도 주문 | **104건** |
| 그중 `filled_price`가 0/null (결함 5) | **101건 (97%)** |
| **분봉으로 추정 가능** | **90건 (86.5%)** |
| 일봉으로 추정 | **0건** (운영 DB에 DAILY 캔들 0행) |
| **추정 불가** | **14건 (13.5%)** |

### FIFO 짝짓기 결과 (전 기간)

| 항목 | 값 |
|---|---|
| 체결 주문 | 209건 (매수 105 / 매도 104) |
| **짝지어진 거래** | **90건** (추정 출처 전부 `MINUTE`) |
| **측정 불가 조각** | **16건 / 101주** (매도가 추정 불가 14 · 매수 체결가 없음 2) |
| 승 / 패 / 본전 | 49 / 39 / 2 → **승률 54.4%** |
| 총손익 (추정, **비용 제외**) | **+62,200원** |
| 평균이익 / 평균손실 | 15,902원 / 18,385원 |
| **손익비 / PF** | **0.86 / 1.09** |
| 열린 포지션 | 066570 5주 |
| 칸 VB | 27건 · 승률 44.4% · +58,450원 |
| 칸 MIX(방식3·스캘핑) | 63건 · 승률 58.7% · +3,750원 |

### 계좌 기준과의 차이 — 왜 부호가 다른가

| 출처 | 값 |
|---|---|
| 계좌 기준 누적 손익 (`/api/performance/account`, 정본) | **-212,040원** |
| 거래 기준 추정 손익 (이번 API) | **+62,200원** |
| 차이 | **274,240원** |

매수 체결 대금 합계는 **104,331,600원**이다. 모의 왕복 비용을 거래세 0.18% + 수수료 약
0.03%로 잡으면 **약 219,000원**, 백테스트의 보수적 가정 0.41%로는 **427,760원**이다.
즉 **차이의 대부분은 "수수료·세금을 빼지 않은 총손익"이라는 설계에서 나온다**
(나머지는 집계에서 빠진 101주와 추정 오차). 정확히 일치시키려는 시도는 하지 않았다 —
**정본은 계좌 기준이고 이 API는 분포를 보기 위한 것**이라는 것이 설계 전제다.
화면에 이 두 숫자를 나란히 놓을 때 반드시 `warning`을 함께 띄워야 한다.

---

## 9. 테스트 결과

```
[검증 증거 — 전체]
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build \
      /c/Users/SAMSUNG/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gvueggl8a5cioxuftxrik/gradle-9.2.0/bin/gradle \
      test --console=plain
종료 코드: 0
결과: 785개 중 785개 통과, 실패 0 / 에러 0 / 건너뜀 0 (테스트 클래스 124개)
→ 판정: 통과
```

직전 기준선 **706** + 내 증분 **79** = **785**. 정확히 맞는다 (기준선 미만 아님).

| 클래스 | 건수 | 실패 |
|---|---|---|
| `TradingStatusManagerTest` (+ `$Recording` `$RecordingFailure` `$Wiring`) | 12 | 0 |
| `RiskBlockRecorderTest` (+ `$Suppression` `$Failure` `$Wiring`) | 14 | 0 |
| `RiskRuleNameResolverTest` | 18 | 0 |
| `SellPriceEstimatorTest` | 6 | 0 |
| `TradeStatsServiceTest` (+ 중첩 5개) | 16 | 0 |
| `DiagnosticsQueryControllerTest` (+ 중첩 2개) | 8 | 0 |
| `DiagnosticsQueryTest` (`@DataJpaTest`, 실제 H2) | 5 | 0 |

### Red-Green (한 번 통과한 것만으로는 아무것도 증명하지 못한다)

지시받은 4가지를 **전부** 변이 검사로 확인했다. 매번 수정을 되돌려 실행 → 실패 확인 → 복구.

| # | 무엇을 되돌렸나 | 결과 | 실패한 테스트 |
|---|---|---|---|
| 1 | `TradingStatusManager` + `ModeTransitionRecorder`의 **try/catch 두 겹 제거** | 종료 1 · **10개 중 3개 실패** | `DB 저장이 터져도 모드는 바뀐다` · `기록기 자체가 터져도 모드는 바뀐다` · `기록기를 직접 불러도 예외가 새어 나오지 않는다` |
| 2 | `RiskBlockRecorder.claim()`의 **창 검사 제거**(매번 저장) | 종료 1 · **12개 중 2개 실패** | `창 안에서 반복되면 더 저장하지 않는다` · `창이 지나면 억제된 횟수가 blockedCount에 실린다` |
| 3 | `SellPriceEstimator`의 **일봉 폴백 제거** | 종료 1 · **6개 중 1개 실패** | `분봉이 없으면 그날 일봉 종가로 내려간다` |
| 4 | `TradePairer`가 **추정 불가를 0원 거래로 계산**하게 변경 | 종료 1 · **16개 중 1개 실패** | `캔들이 없어 매도가를 못 구하면 집계에서 빼고 건수로 센다` |

네 경우 모두 **정확히 그 요구에 해당하는 테스트만** 실패했다 — 테스트가 우연히 통과하는
것이 아니라 실제로 그 동작을 잡는다는 증거다. 복구 후 전체 785/785 통과 · 종료 코드 0.

---

## 10. 아키텍처 규칙 준수

| 규칙 | 이번 변경 |
|---|---|
| `Strategy→Signal→RiskEngine→OrderEngine` | **무변경.** 추가한 것은 거부 분기(`else`)의 기록 호출뿐 — 주문 경로에 손대지 않았다 |
| 새 `RiskRule` 없음 / `RiskEngine` 무수정 | ✅ 둘 다 없음 |
| 연동 인터페이스 시그니처 고정 | `MarketDataService`·`KisOrderClient`·`PositionManager`·`BrokerageApiClient`·`OrderHistoryRepository`·`CandleHistoryRepository` **전부 무변경** (기존 파생 쿼리만 재사용 — `OrderLifecycleTest`의 손수 만든 스텁이 안 깨진다) |
| 단일 청산 상태머신 | 무변경 |
| `@EnableScheduling` 위치 | 무변경 |
| 비밀키 | 코드에 없음 |
| 시간 판정 | `LocalDateTime.now()` / `LocalDate.now()` 직접 호출 **0건** — 전부 주입한 KST `Clock` |
| 프로필 격리 | 조회 컨트롤러 3개: 진단 2개 `@Profile("!backtest")`(= `TradingController` 선례), 성적 1개 `@Profile("paper")`(= `AccountPerformanceController` 선례). URL 충돌 없음 |
| 불변성 | 응답 맵은 전부 새로 만들어 반환. `TradePairer`의 매수 로트도 불변 레코드(`minus(q)`로 갈아 끼움) |
| `static/` · `com.trading.backtest` 미접촉 | ✅ 파일 수정 시각으로 확인 (00:40 이후 수정분 0건) |

---

## 11. 남은 것 / 하지 않은 것 / 확인하지 못한 것

### 아직 배포 전 — 반드시 읽을 것

- 실행 중인 앱(**PID 17048, 8080, RUNNING**)은 **끄지도 재시작하지도 않았다.**
  작업 끝에 `/api/status`로 `RUNNING` 생존 확인만 했다.
- **새 테이블 2개(`mode_transition`, `risk_block_record`)는 다음 기동 때 생긴다**
  (`ddl-auto: update`). 지금 떠 있는 앱은 이 코드가 아니므로 **아직 한 줄도 안 쌓인다.**
  배포(재기동)는 장 마감 후 리더가.

### 하지 않은 것 (범위 밖)

- 화면(프런트엔드) 일체 — 진단 탭·성적 탭 연결은 다른 작업자 몫 (§3이 계약서다)
- `changeMode`에 사유 인자 추가 — 호출부 9곳이 청산·SAFE_MODE의 심장이라 손대지 않았다 (§6⑥)
- `RiskEngine`이 거부 룰 이름을 돌려주게 만들기 — 금지 (§7)
- `mode_transition` 보존 정리 — 일부러 안 만들었다 (§5)
- 수수료·세금을 추정 손익에 반영 — 추정가에 비용까지 얹으면 오차만 커진다 (§8)
- 루트의 낡은 중복 `.java` 정리

### 확인하지 못한 것

1. **스프링 전체 컨텍스트 실기동 검증 없음.** 앱을 재시작할 수 없어 `paper` 프로필 전체 기동은
   못 봤다. 대신 위험한 두 지점을 개별로 막았다:
   ① 새 빈 배선은 **실제 `AnnotationConfigApplicationContext`를 띄워** "기록기 빈이 없어도 뜬다 /
   있으면 주입된다"를 4건 테스트로 고정, ② URL 중복은 `/api/trading/mode-history`·
   `/api/risk/blocks`·`/api/performance/{trades,by-stock,by-bucket}`가 기존 매핑과 겹치지
   않음을 확인(`PerformanceController`는 `GET /api/performance` 루트와 `POST /backfill`뿐).
2. ~~새 파생 쿼리를 실제 H2에서 돌려보지 않았다~~ ✅ **해소** — `DiagnosticsQueryTest`
   (`@DataJpaTest`, 5건)로 `findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc`(×2) ·
   `countByOccurredAtBefore` · `deleteByOccurredAtBefore`의 이름 파싱·정렬·기간 필터·
   페이징·삭제를 실제 H2에서 확인했다(19_impl의 `OrderHistoryQueryTest`와 같은 방식).
   기동 시 파생 쿼리 이름 오류로 앱이 못 뜨는 경로는 닫혔다.
3. **실행 중인 앱으로 HTTP 호출은 하지 않았다** — 현재 코드에 새 엔드포인트가 없다.
   §8의 수치는 같은 규칙을 같은 데이터에 적용한 **독립 재계산**이다.
4. `gradle test`가 운영 로그 `logs/paper.log`에 이어 쓰는 문제(19_impl §8 기록)는 여전하다.
   이번에 전체 3회 + 부분 8회 실행했다. **별건으로 둔다.**

### 알려진 문제 (고치지 않고 보고만)

- `TradeStatsServiceTest` 371줄 — 300줄 상한 초과 (§2, 저장소 관행·`BACKLOG.md` 추적 중)
- 룰 이름 되짚기의 메시지 의존 (§7) — 구조적 한계, 테스트로만 방어
- 운영 DB에 DAILY 캔들 0건 → 폴백 2단계가 현재는 무발동 (§4)
- `ConsecutiveLossRule`이 paper에서 무발동인 문제(12_audit HIGH)는 **이번 범위 밖·증분 0**


---

## 12. 감사 후속 — M-1 · M-2 · M-3 · L-2 (2026-09-22 05:35)

감사 `_workspace/24_audit_history-and-trade-stats.md`(CRITICAL 0 · HIGH 0 · MEDIUM 4 · LOW 6)의
지적 4건을 고쳤다. **M-4(청산 개시 앞 INSERT 1건)는 리더 판단으로 손대지 않았다** —
바로 다음 줄이 이미 블로킹 텔레그램이라 증분이 무의미하다.

### M-1 — 내가 쓴 안전 근거가 틀렸다 (필수)

**무엇이 문제였나.** §6③에 "`@Transactional`을 안 붙였으니 커밋 실패까지 안쪽 catch가 받는다,
호출부 9곳은 전부 비트랜잭션"이라고 적었다. **호출 스택 기준으로 거짓이었다.**
직접 확인한 실재 경로:

```
StopLossArmer.onOrderFilled :52-53   @Transactional(REQUIRES_NEW)   ← 트랜잭션 열림
 → arm() → MarketDataService.getDailyCandles() :77                  ← 트랜잭션 안에서 KIS HTTP
 → KisApiClient.recordFailure()/recordSuccess() :268/:275
 → triggerSafeModeIfRunning()/maybeAutoResume() → changeMode() :299/:318
 → ModeTransitionRecorder.record() → repository.save()              ← 그 트랜잭션에 합류
```

`save()`가 실패하면 바깥 트랜잭션이 **rollback-only**가 되고, `StopLossArmer:88-89`가
"손절선 장착" 로그를 찍고도 **손절선이 롤백된다.** 손절선 누락은 2026-08-04에 이미 한 번
사고가 났던 자리다. 같은 형태가 `ResearchController.addToWatchlist`(`@Transactional` +
`fetchQuote` KIS 호출)에도 있음을 확인했다.

**어떻게 고쳤나.** 저장을 **별도 빈**으로 떼어 `REQUIRES_NEW`를 실제로 걸리게 했다.
같은 클래스 안에서 부르면 스프링 프록시를 안 타 애너테이션이 무시되므로 **반드시 별도 빈**이다.

| 신규 파일 | 역할 | 줄 |
|---|---|---|
| `risk/ModeTransitionWriter.java` | `@Transactional(REQUIRES_NEW) saveInNewTransaction(...)` | 51 |
| `risk/RiskBlockWriter.java` | 같음 (매수 차단 이력) | 33 |

```
ModeTransitionRecorder.record(...)        ← @Transactional 없음 · try/catch 유지
    └─ writer.saveInNewTransaction(...)   ← 별도 빈 · REQUIRES_NEW
```

`REQUIRES_NEW`는 바깥 트랜잭션을 **잠시 멈추고** 새 트랜잭션으로 INSERT한다. 커밋/롤백이
안쪽 프록시 경계에서 끝나므로 예외가 `record()`의 catch로 올라오고 **바깥은 오염되지 않는다.**
`RiskBlockRecorder`도 같은 방식으로 맞췄다(지금 호출부는 비트랜잭션이지만 같은 취약성을
남겨둘 이유가 없다).

⚠ `TradingStatusManager:61-63` 주석도 코드에 맞춰 다시 썼다 — **주석 쪽이 옳은 설계**라는
지적대로 코드를 주석에 맞췄다. §6③ 본문도 이 절을 가리키도록 갱신했다.

**고정한 테스트** (`TradingStatusManagerTest$TransactionIsolation` 3건 ·
`RiskBlockRecorderTest$TransactionIsolation` 2건)

| 요구 | 테스트 이름 |
|---|---|
| 저장이 `REQUIRES_NEW` 독립 트랜잭션으로 나간다 | `save_runs_in_its_own_transaction` (양쪽) |
| 저장기가 기록기와 **다른 빈**이다 (프록시를 타야 한다) | `writer_is_a_separate_bean` |
| 저장기가 프록시 경계에서 터져도(커밋 실패) 기록기가 삼킨다 | `writer_failure_at_the_proxy_boundary_is_swallowed` (양쪽) |

### M-2 — 범위 초과로 넣은 호출에만 테스트가 없었다 (필수)

`TradingSchedulerTest` 7건은 전부 진입 게이트·라운드로빈이라 `isPass()` 분기를 아예 안 봤다.
**신규 파일** `scheduler/TradingSchedulerDropRecordingTest.java`(184줄)로 3건을 고정했다.
기존 파일에 얹으면 324줄이 되어 300줄 상한을 넘기 때문에 따로 뺐다.

| 요구 | 테스트 이름 |
|---|---|
| 리스크 **거부**면 사유와 함께 **1회** 기록 | `rejected_signal_is_recorded_once` |
| 리스크 **통과**면 기록 없이 **주문이 나간다** (`orderClient.buy` 호출 검증) | `passed_signal_is_not_recorded_and_the_order_goes_out` |
| 기록이 **터져도** 루프가 안 멈추고 **다음 종목으로 넘어간다** | `a_failing_recorder_does_not_stop_the_loop` |

세 번째를 만족시키려면 코드도 손봐야 했다 — `TradingScheduler`의 거부 분기 호출을
`recordDropQuietly(...)`(try/catch)로 감쌌다. **통과 경로(`orderEngine.execute`)는 무변경**이고,
추가된 것은 거부 분기와 private 헬퍼 1개뿐이다.

`OpportunityCostLogger`는 구체 클래스라 목으로 만들지 않고 호출을 세는 실객체로 상속했다
(Java 25 인라인 Mockito 제약). 목은 인터페이스(`MarketDataService`·`KisOrderClient`·리포지토리)뿐이다.

### M-3 — 성적 조회가 매 요청마다 전량 스캔 (같이)

화면이 **60초 폴링**으로 API 3종을 부르므로, 없으면 한 바퀴마다 체결 주문 전량 + 분봉을
세 번 다시 훑는다. `TradeStatsService` 안에 **`days` 키 · TTL 30초 메모리 캐시**를 넣었다
(스프링 캐시 추상화는 들이지 않았다).

| 항목 | 결정 |
|---|---|
| 수명 | `CACHE_TTL = 30초` — 거래는 하루 몇 건 늘어날 뿐이라 이만큼 낡아도 무해 |
| 키 | `days`(경계 보정 후 값). 기간이 다르면 따로 계산한다 |
| 상한 | `MAX_CACHE_ENTRIES = 16` 초과 시 통째로 비움 — `days` 값이 제각각인 요청에 메모리가 안 늘게 |
| 분리 | 비싼 계산은 `buildWindow(...)`로 떼어내 `window(...)`는 캐시 판정만 (파일 162 → 196줄) |

**고정한 테스트** — 신규 `dashboard/TradeStatsCacheTest.java`(119줄, 4건):
`repeated_calls_within_the_ttl_scan_once` · `a_new_scan_happens_after_the_ttl` ·
`different_periods_do_not_share_a_cache_entry` · `the_cached_answer_is_identical`.
시계는 테스트가 직접 굴린다(`MovableClock`) — TTL을 진짜 시간에 기대지 않는다.

### L-2 — real에서 이력이 무한 증가 (한 단어)

`RiskBlockRetention`의 `@Profile("paper")` → **`@Profile("!backtest")`**.
기록하는 쪽(`RiskBlockRecorder`)이 `!backtest`인데 정리만 `paper`면 real에서는 쌓이기만 한다.

**고정한 테스트**: `RiskBlockRecorderTest.retention_profile_matches_the_recorder` —
두 클래스의 `@Profile` 값이 같은지 대조한다(둘이 갈라지면 바로 깨진다).

### 안 고친 것 (리더 지시)

- **M-4** 청산 개시 앞 동기 INSERT 1건 — 다음 줄이 이미 블로킹 텔레그램이라 증분 무의미
- `RiskRuleNameResolver` 메시지 의존 — 감사가 16조각 전수 대조로 통과 판정, 표시 전용
- `TradeStatsServiceTest` 371줄 — `BACKLOG.md` [2026-08-14] 추적 중

### 테스트 결과

```
[검증 증거 — 전체]
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build       /c/Users/SAMSUNG/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gvueggl8a5cioxuftxrik/gradle-9.2.0/bin/gradle       test --console=plain
종료 코드: 0
결과: 798개 중 798개 통과, 실패 0 / 에러 0 / 건너뜀 0 (테스트 클래스 128개)
→ 판정: 통과
```

직전 기준선 **785** + 이번 증분 **13**(M-1 5 · M-2 3 · M-3 4 · L-2 1) = **798**. 줄지 않았다.

### Red-Green (5건 전부 확인 — 매번 되돌려 실행 → 실패 확인 → 복구)

| # | 무엇을 되돌렸나 | 결과 | 실패한 테스트 |
|---|---|---|---|
| M-1 | 두 저장기에서 **`@Transactional(REQUIRES_NEW)` 제거** | 종료 1 · **32개 중 2개 실패** | `save_runs_in_its_own_transaction` (양쪽) |
| M-2a | 거부 분기의 **기록 호출 통째로 제거**(옛 TODO 상태) | 종료 1 · **3개 중 2개 실패** | `rejected_signal_is_recorded_once` · `a_failing_recorder_does_not_stop_the_loop` |
| M-2b | 호출은 두되 **루프 가드(try/catch)만 제거** | 종료 1 · **3개 중 1개 실패** | `a_failing_recorder_does_not_stop_the_loop` |
| M-3 | **캐시 제거**(요청마다 전량 스캔) | 종료 1 · **4개 중 1개 실패** | `repeated_calls_within_the_ttl_scan_once` |
| L-2 | 정리 배치 프로필을 **`paper`로 되돌림** | 종료 1 · **17개 중 1개 실패** | `retention_profile_matches_the_recorder` |

다섯 경우 모두 **정확히 그 요구에 해당하는 테스트만** 실패했다. 복구 후 전체 798/798 · 종료 코드 0.

### 지시 준수 확인

| 항목 | 결과 |
|---|---|
| `TradingScheduler:110` 통과 경로 불변 | ✅ `orderEngine.execute(signal)` 한 줄 그대로, 손댄 것은 `else` 분기뿐 |
| `TradingStatusManager` `getAndSet`이 기록보다 먼저 | ✅ 무변경 (바뀐 것은 주석과 기록 위임 대상) |
| `RiskEngine` · 14개 룰 · `LiquidationService` 무수정 | ✅ 이번 라운드에도 건드리지 않았다 |
| 파일 300줄 / 함수 50줄 | ✅ 생산 코드 최대 196줄(`TradeStatsService`). ⚠ `RiskBlockRecorderTest`가 300 → **304줄**(테스트, 4줄 초과 — 위 `TradeStatsServiceTest`와 같은 취급) |
| 앱 미정지 | ✅ 끄거나 재시작하지 않았다 (`/api/status` = `RUNNING`, 05:35 확인) |
| `static/` · `com.trading.backtest` 미접촉 | ✅ 파일 수정 시각으로 확인 — static 변경은 **01:32~01:43**(다른 에이전트), 내 이번 라운드는 **05:25~05:34** |

### 여전히 배포 전

새 테이블 2개(`mode_transition`·`risk_block_record`)는 **다음 기동 때** 생긴다.
지금 떠 있는 앱(PID 17048)은 이 코드가 아니다. **개장(09:00)까지 3시간 25분 남았다** —
배포 재기동은 리더가 개장 전에.
