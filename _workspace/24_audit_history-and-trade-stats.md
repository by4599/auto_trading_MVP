# 24_audit — 모드 전환 이력 · 매수 차단 이력 · 거래 기준 성적(추정) 감사

> 감사 2026-09-22 KST · `risk-auditor` · **코드 무수정 · 빌드/테스트 미실행**(지시).
> 정적 대조만 했다. 대상: `23_impl`의 미커밋 증분 — 수정 3파일(`TradingScheduler`·
> `TradingStatusManager`·`OpportunityCostLogger`) + 신규 16파일.
> `src/main/resources/static/`(동시 편집 중)과 `com.trading.backtest`는 판정 대상에서 뺐다.

---

## 0. 판정

# **CRITICAL 0 — 오늘 개장 전 배포를 막을 사유 없음**

**CRITICAL 0 · HIGH 0 · MEDIUM 4 · LOW 6**

매매 판정·주문 경로는 **한 줄도 제거되지 않았다**(제거 줄 전수 열거는 §1-1).
`RiskEngine` 무수정 · 새 `RiskRule` 0개 · `LiquidationService`/`LiquidationPhase` 무변경 ·
KIS 호출 증분 0 · 비밀키 0건.

다만 구현자가 **안전 근거로 내세운 트랜잭션 전제가 틀렸다**(M-1). 지금 당장 돈이 새지는
않지만, "기록 실패가 다른 걸 망가뜨리지 않는다"는 주장의 근거가 무너지므로 반드시 읽을 것.

### 이월 항목 현재 상태 (맨 위에 적는다)

| 출처 | 항목 | 이번 변경 후 상태 |
|---|---|---|
| `12_audit` **HIGH** | `ConsecutiveLossRule` paper 무발동 (매도가 대사 경로로 종결돼 `recordRoundTrip` 미호출) | **미해소.** 이번 범위 밖·증분 0. 단 이번 차단 이력이 이 결함을 **화면에서 보이게** 만든다(`ConsecutiveLossRule` 행이 영영 안 찍히는 것이 증거가 된다) |
| `20_audit` M-1 | 전고점 알림 발송 사각지대(장외 스킵 + 그날 1회 할당 소모) | **이번 변경과 무관 · 미확인.** 해당 파일은 이번 증분이 아니다 |
| `20_audit` M-2 | 1초 감시 루프 안 블로킹 HTTP(최대 8초) | **좋아졌다(별건 수정으로).** `position/BackgroundAlertSender.java`(09-22 00:31 — 이번 작업 01:10보다 앞선 별도 변경)가 전고점 알림을 전용 스레드로 뺐다. **이번 변경은 M-2를 악화시키지 않는다** — 새 블로킹 HTTP 0건, 추가된 것은 로컬 H2 INSERT뿐(§1-5) |
| `20_audit` M-3 | `real` 프로필 공백 | **표면이 더 넓어졌다(L-2).** `RiskBlockRecorder`는 `!backtest`(= real 포함)인데 정리 배치 `RiskBlockRetention`은 `@Profile("paper")`다 → real에서는 쌓이기만 한다. 실전 전환은 게이트 G2(사람) 선행이므로 그 안에서 함께 처리 |

---

## 1. 지시 항목 1~9 판정

| # | 항목 | 판정 | 핵심 근거 |
|---|---|---|---|
| 1 | 판정·주문 경로 불변 | 통과 | 제거 줄 전수 = 2줄(생성자 시그니처 1 + TODO 주석 1) |
| 2 | 기록 실패가 매매 루프를 안 멈춤 | 통과 (3중 방어) | catch 3겹 + `finally` 가드 |
| 3 | 기록 실패가 모드 전환을 안 막음 | 통과 / **전제 오류 M-1** | 상태 변경이 기록보다 먼저 — 맞다. 그러나 "호출부 9곳 전부 비트랜잭션"은 **호출 스택 기준으로는 거짓** |
| 4 | DB 폭증 | 통과 (L-3) | 창 합치기 정상 동작. 단 하루 행 수 상한은 문서의 780행이 아니라 **최대 10,920행** |
| 5 | 스케줄러 스레드 블로킹 | 통과 | 풀 1개는 사실이나, 추가분은 수 ms INSERT. 기존 8초 HTTP·실측 +321초 크론 지연에 비해 3자릿수 작다 |
| 6 | `RiskRuleNameResolver` 취약성 | **표시 전용** | 16개 조각 ↔ 14개 룰 실제 메시지 전수 대조 일치. 사유 원문 별도 보존 |
| 7 | 신규 조회 API 읽기 전용 | 통과 | `save`/`delete`/`flush`/setter/KIS **매치 0건** |
| 8 | 프로필 격리 | 통과 | backtest에 기록기 빈 없음 + `logDropped` 호출부가 backtest 경로에 없음 |
| 9 | 비밀키 | 통과 | 19개 파일 전수 스캔 매치 0건 |

### 1-1. 판정·주문 경로 불변 — 통과

`git diff --numstat` 실측:

```
43  0   risk/OpportunityCostLogger.java      <- 제거 0줄
51  0   risk/TradingStatusManager.java       <- 제거 0줄
 9  2   scheduler/TradingScheduler.java      <- 제거 2줄
```

**`TradingScheduler`에서 제거된 줄 전부 (2줄, 이게 전부다):**

```
-                             MarketCalendarService marketCalendarService) {      (인자 1개 붙여 재작성)
-                // TODO: result.isPass()==false면 거부 사유를 signal_history에 기록   (주석)
```

- `Strategy -> Signal -> RiskEngine -> OrderEngine` 순서 불변 —
  `TradingScheduler.java:103`(dispatch) -> `:107`(riskEngine.check) -> `:110`(orderEngine.execute).
- `result.isPass()` 분기 조건 불변 (`:109`), `orderEngine.execute(signal)` 호출 조건 불변 (`:110`).
- 새 호출은 **else 분기에만** 있다 (`:114`). 통과 경로(`:110`)에는 0건.
- 진입 게이트 5개(`:79` 자격증명 · `:82` 휴장일 · `:85` 장중 · `:91` RUNNING · `:94` running 가드) 전부 불변.
- `TradingStatusManager`/`OpportunityCostLogger`는 **제거 0줄** — 기존 로직 위에 덧댄 것뿐.
- `RiskEngine.java` 무수정, `implements RiskRule` 구현체 **14개 그대로**(신규 0개) ->
  CLAUDE.md 규칙 3 준수.
- `LiquidationService`·`LiquidationPhase` 미변경(`git status` 미포함) -> ADR-001 단일 상태머신 불변.

### 1-2. 기록 실패가 매매 루프를 멈추는가 — 통과 (3중 방어)

예외가 나도 루프는 **어떤 경우에도** 안 멈춘다. 방어가 3겹이고 서로 다른 계층에 있다.

| 겹 | 위치 | 잡는 것 |
|---|---|---|
| 1 | `RiskBlockRecorder.java:70` catch (Exception) | `repository.save()`의 DB 오류·제약 위반·락 대기 타임아웃(전부 RuntimeException 계열) |
| 2 | `OpportunityCostLogger.java:56` catch (Exception) | 기록기 자체가 터지는 경우(NPE 등) |
| 3 | `TradingScheduler.java:117-119` finally { running.set(false); } | 위 둘을 다 뚫어도 **틱 잠금이 영구화되지 않는다** |

- **트랜잭션 경계:** `TradingScheduler.run()`에 `@Transactional` 없음 -> `repository.save()`가
  자기 트랜잭션을 열고 닫는다. **이 경로에 한해서는** 구현자 설명대로 커밋 실패까지
  1번 catch가 받는다. (모드 전환 경로는 다르다 — M-1)
- **락 대기:** `risk_block_record`는 이번에 새로 만드는 테이블이고 쓰는 스레드가
  스케줄러 1개뿐이다 -> `order_history`·`position`·`daily_equity`와 락을 다투지 않는다.
- **Error(OOM 등)는 안 잡힌다** — 다만 finally가 `running`을 풀고, Spring `@Scheduled`는
  예외가 나도 다음 주기를 다시 잡는다(`TaskUtils` 에러 핸들러). 주의: 이 마지막 문장은
  **Spring 동작에 대한 정적 추론이며 런타임 미확인**이다.
- 잔여 영향: 예외가 3겹을 뚫으면 그 틱의 for 루프가 중단돼 **남은 신호가 건너뛰어진다**.
  다음 라운드로빈(N초 뒤)에 재평가되므로 손실이 아니라 지연이다.

### 1-3. 기록 실패가 모드 전환을 막는가 — 통과, 다만 **전제가 틀렸다 (M-1)**

**전환 자체는 안전하다.** `TradingStatusManager.java:50`에서 `mode.getAndSet(newMode)`가
**먼저** 일어나고, `:51` 판정(`previous != newMode`)도 무변경이며,
기록(`:53 recordQuietly`)은 **그 뒤**다. `recordQuietly`(`:68-76`)는 Exception을 삼킨다.
-> 기록이 어떻게 터져도 모드는 이미 바뀌어 있고 되돌아가지 않는다.

강제청산 경로도 확인했다:
- `LiquidationService.java:43` — ADR-001 `phase.getAndSet(FULL_LIQUIDATING)`이 **DB 기록보다 먼저**.
- `:58` changeMode(FORCE_LIQUIDATING) -> `:59` 텔레그램 -> `:61` 미체결 취소 -> `:64~` 매도.
- `:81` finally의 changeMode(EMERGENCY_STOPPED)도 모드 먼저·기록 나중 -> 종단 수렴 보장.

**그러나 "호출부 9곳이 전부 비트랜잭션"은 선언 메서드 기준일 뿐, 호출 스택 기준으로는 거짓이다.** -> M-1

### 1-4. 1초 루프 x 20종목에서 DB 폭증 — 통과 (상한은 문서보다 크다)

중복 억제는 **설명대로 동작한다.** `RiskBlockRecorder.java:89-105` claim()을 줄 단위로 대조:

- 첫 차단: previous == null -> decided = 0 + 1 = 1 -> **즉시 저장**, 상태 (now, 0) (`:101-102`)
- 창 안 반복: Duration.between(lastSavedAt, now) < window -> decided = 0 -> 저장 안 함,
  suppressed + 1 누적 (`:97-99`) -> `:79`에서 return
- 창 경과: decided = previous.suppressed() + 1 -> 억제분까지 **합쳐서** 1행에 실림 (`:101`)
- **창 경계:** 창 기준점이 벽시계 버킷이 아니라 lastSavedAt에 붙은 **슬라이딩 창**이라
  경계에서 두 번 찍히는 구멍이 없다.
- 억제 해제 손잡이: suppress-minutes: 0 -> window = ZERO -> 비교가 항상 거짓 -> 매번 저장.

**하루 행 수 (장중 390분 / 창 10분 = 키당 최대 39~40행/일):**

| 시나리오 | 계산 | 행/일 | 90일 누적 |
|---|---|---|---|
| 문서의 추정(종목당 룰 1개) | 20 x 39 | 780 | 7만 |
| 현실적(하루 중 룰 3~4종 교대) | 20 x 3.5 x 39 | **약 2,700** | 24만 |
| **이론 상한**(룰 14종 전부 교대) | 20 x 14 x 39 | **10,920** | **98만** |

`RiskEngine.java:31-36`이 **첫 거부 룰에서 즉시 반환**하므로 한 평가당 룰은 1개다.
따라서 상한이 14배로 튀려면 하루 안에 14개 룰이 돌아가며 막아야 하는데 비현실적이다.
**폭증은 아니다** — idx_risk_block_at 인덱스(`RiskBlockRecord.java:28`)가 7일 조회를
받치고, 조회 상한 1000행(`RiskBlockController.java:46`)이 화면을 보호한다.
다만 문서의 780행은 낙관적 끝값이다(L-3).

**앱 종료 시 유실 범위:** byKey는 메모리(`:50`)다. 종료 시 잃는 것은 **아직 저장 안 된 억제 카운트**뿐
(키당 최대 창 1개 분량)이고, **행 자체는 안 잃는다**(첫 차단 즉시 저장 설계 덕분).
재기동 후 첫 차단은 blockedCount=1로 다시 찍힌다.

**보존 정리 배치는 실제로 돈다:** `RiskBlockRetention.java:25-26`(@Component @Profile("paper")) +
`:44` @Scheduled(cron="0 20 23 * * *", zone="Asia/Seoul"). @EnableScheduling은
`SchedulingConfig`(@Profile("!backtest"))에 살아 있다. `:47` 0일 이하면 생략,
`:53` 먼저 세어 보고 0이면 삭제 생략. (조건은 L-2·L-6)

### 1-5. 스케줄러 스레드 블로킹 — 통과

**풀이 1개인 것은 맞다** — spring.task.scheduling.pool.size 설정 없음, TaskScheduler 빈 없음.
이제는 정적 추론만이 아니라 **코드 안의 실측 기록으로 뒷받침된다**:
`DailyPnlRecorder.java:91-94` — "스케줄 스레드 1개를 @Scheduled 14개가 나눠 쓰는...
실측: 15:25 크론이 +321초 밀려 15:30:21에 발화한 날이 있다".

그 위에서 이번 증분을 재면:

| 새로 얹은 것 | 어디 | 빈도 | 대략 비용 |
|---|---|---|---|
| ConcurrentHashMap.compute (억제 판정) | TradingScheduler.run() else 분기 | 차단 신호마다 (약 1회/초) | 마이크로초 |
| log.warn | `OpportunityCostLogger.java:42` | 차단 신호마다 | 수십 마이크로초(버퍼 파일 append) |
| **H2 INSERT 1건** | `RiskBlockRecorder.java:81` | **(종목,룰)당 10분에 1회** | 수 ms |
| H2 INSERT 1건 | `ModeTransitionRecorder.java:56` | 모드 전환 시(하루 몇 건) | 수 ms |

- **락 대기는 다른 문제다** — 맞다. 그래서 확인했다: risk_block_record·mode_transition은
  **이번에 새로 생기는 테이블이고 쓰는 주체가 단일 스레드**다. 기존 테이블과 락을 다투지 않는다.
  읽는 쪽(조회 API)은 Tomcat 스레드이고 H2 MVCC라 쓰기를 막지 않는다.
  RiskBlockRetention(23:20)은 장 마감 후라 TradingScheduler가 이미 조기 반환하는 시각이다.
- **새 블로킹 HTTP 0건** — 이것이 20_audit M-2의 본질이었고, 이번엔 재발이 없다.
- `StopLossArmer`가 이미 같은 스레드에서 최대 8초 KIS 호출을 한다(`:51-56` AFTER_COMMIT +
  `:77`). 이번 증분(수 ms)은 그 옆에서 무시할 수 있는 크기다.

### 1-6. RiskRuleNameResolver — **판정 무영향 · 표시 전용**

**14개 룰의 실제 거부 메시지를 전수 대조했다. 16개 조각 전부 정확히 맞는다.**

| 룰 실제 메시지(소스) | 매칭 조각 | 결과 |
|---|---|---|
| `PendingOrderRule:46,53` "이미 보유 중인 종목: ..." / "미체결 매수 주문 대기 중: ..." | 동일 2개 | 일치 |
| `PositionLimitRule:24` "종목 비중 한도 초과: ..." | 동일 | 일치 |
| `MaxPositionCountRule:24` "최대 보유 종목 수 초과: ..." | 동일 | 일치 |
| `MarketCloseRule:35` "%s 이후 신규 매수 금지 (장 마감 임박)" | "장 마감 임박" | 일치 |
| `DailyLossRule:27,32` "일일 손실 한도 ..." | 동일 | 일치 |
| `GlobalEquityStopRule:41` "전고점 대비 MDD ..." | 동일 | 일치 |
| `ConsecutiveLossRule:48,59` "연속 손실 ..." | 동일 | 일치 |
| `BucketBudgetRule:37,43` "칸 비활성: ..." / "칸 예산 소진: ..." | 동일 2개 | 일치 |
| `PostTimeCutBuyRule:59` "%s 타임컷 이후 신규 매수 금지 ..." | "타임컷 이후 신규 매수 금지" | 일치 |
| `DisclosureCooldownRule:59` "공시 쿨다운 — ..." | 동일 | 일치 |
| `EntryTimeWindowRule:34` "진입 시간창 필터 — ..." | 동일 | 일치 |
| `IndexTrendRule:38` "지수 추세 필터 — ..." | 동일 | 일치 |
| `IndexRegimeRule:30` "지수 레짐 필터 — ..." | 동일 | 일치 |
| `OrderFailureCooldownRule:33` "직전 주문 실패로 대기 중: ..." | 동일 | 일치 |

- **순서 함정 없음**: MarketCloseRule("...장 마감 임박")과 PostTimeCutBuyRule("...타임컷 이후...")은
  서로의 조각을 포함하지 않는다. LinkedHashMap 앞쪽 조각이 뒤쪽 메시지를 가로채는 경우 0건.
- **판정 영향 없음**: ruleName은 (1) risk_block_record.rule_name 컬럼 (2) 조회 API의 집계 라벨
  (3) 억제 키의 일부 — **어떤 매매/리스크 판정에도 쓰이지 않는다.**
- **사유 원문 보존 확인**: `RiskBlockRecord.java:49-51` reason 컬럼에 룰이 준 문자열을
  그대로(500자 클립) 담는다. `RiskBlockRecorder.java:82`가 reason을 원문으로 넘긴다.
  -> 메시지가 바뀌어 UNKNOWN이 돼도 **화면에서 이유는 계속 읽힌다.**
- 부작용 1개(L-4): 두 룰이 동시에 UNKNOWN으로 떨어지면 억제 키(종목|UNKNOWN)가 합쳐져
  서로를 억제한다 -> 기록 해상도 저하. 판정 영향은 여전히 0.

### 1-7. 신규 조회 API 읽기 전용 — 통과

TradeStatsService·SellPriceEstimator·TradePairer·TradeStats·EstimatedTrade·
TradeStatsController·ModeHistoryController·RiskBlockController 8개 파일에서
`.save(` / `.delete` / `.flush(` / `.set[A-Z]` / KisApiClient / MarketDataService /
BrokerageApiClient / RestClient / OrderEngine 스캔 -> **매치 0건.**

- **KIS 호출 0건** -> 모의 유량(1건/초) 소모 증분 0. `SellPriceEstimator.java:34` 주석이
  선언한 대로 candle_history만 읽는다(`:96-99`).
- 리포지토리 시그니처 변경 없음 — `TradeStatsService.java:98`은 **기존** 파생 쿼리
  findByFilledQuantityGreaterThan(0)을 재사용한다(PerformanceBackfillService가 쓰던 것).
  OrderHistoryRepository 이번 증분은 20_audit에서 이미 본 findByStatusIn... 1개뿐.
  -> CLAUDE.md 규칙 4(인터페이스 시그니처 고정) 준수.
- 불변성: 응답은 전부 새 LinkedHashMap(`TradeStatsService:108`, `TradeStats:42`,
  `RiskBlockController:98,107`, `ModeHistoryController:66,77`). TradePairer의 매수 로트는
  불변 레코드(`:46-49`, minus()로 교체).
- NPE/캐스팅 점검: `TradePairer:62` StrategyBucket.orDefault(null) -> VB(`StrategyBucket:47-48`)로
  레거시 null 칸 방어 · `fillTimeOf:120`이 requestedAt으로 떨어지는데 이 컬럼은
  nullable=false(`OrderHistory:60`) · `TradeStatsService:159`의 (long) row.get("totalPnl")은
  `TradeStats:48` Math.round(double) -> long 박싱이라 Long으로 안전 ·
  `EstimatedTrade:34`가 buyPrice <= 0에서 0 나눗셈 차단.

### 1-8. 프로필 격리 — 통과 (백테스트 결정성 영향 0)

| 빈 | 프로필 | 판정 |
|---|---|---|
| `ModeTransitionRecorder:34` / `RiskBlockRecorder:38` | @Profile("!backtest") | backtest에 빈 없음 |
| `TradingStatusManager:40-43` / `OpportunityCostLogger:35-38` | ObjectProvider.getIfAvailable() | 빈 없으면 null -> 기동 실패 없음 |
| `ModeHistoryController:35` / `RiskBlockController:39` | @Profile("!backtest") | TradingController 선례와 동일 |
| `TradeStatsController:30` / `TradeStatsService:33` | @Profile("paper") | 프로필 없는 PerformanceController에 얹지 않은 것이 정확 |
| `RiskBlockRetention:26` | @Profile("paper") | 기록기(!backtest)와 불일치 -> L-2 |
| `SellPriceEstimator:36` | 프로필 없음 | 무해(읽기 전용·호출부가 paper 전용) |

**"백테스트가 새 기록기를 타는 경로가 있는가" — 없다. 근거 2겹:**

1. OpportunityCostLogger 호출부 전수 grep -> **`TradingScheduler:114` 단 1곳**(그리고 테스트).
   TradingScheduler는 @Profile("paper")(`:38`)다.
   백테스트 진입 경로(`DailyBarSimulator:123,148`, `DailyBarExitSimulator:209`)는
   riskEngine.check()를 직접 부르고 **logDropped를 부르지 않는다.**
2. `BacktestStateReset:60 @Transactional` -> `:67 changeMode(RUNNING)`은 **backtest에서 실제로 실행되는
   유일한 changeMode**인데, 그 프로필에는 ModeTransitionRecorder 빈이 없어
   `TradingStatusManager:69`의 if (recorder == null) return; 에서 즉시 빠진다. -> DB 쓰기 0건.

**DB가 어디로 가는가:** 두 DB는 물리적으로 분리돼 있다 —
`application-paper.yml:85` jdbc:h2:file:./trading-db vs `application-backtest.yml:14`
jdbc:h2:file:./backtest-db. 백테스트가 운영 DB에 쓸 경로 자체가 없다.
ddl-auto: update라 backtest-db에도 빈 테이블 2개가 생기지만 **쓰는 코드가 없어 항상 비어 있다** ->
결정성 영향 0 (L-6 참고).

### 1-9. 비밀키 — 통과

신규 16 + 수정 3 = 19개 파일에 appkey|secretkey|app_secret|Bearer <토큰>|password|token="
전수 스캔 -> **매치 0건.** 새 설정 키는 trading.risk-block.suppress-minutes(기본 10) ·
trading.risk-block.retention-days(기본 90) 둘뿐이며 비밀값이 아니다.

---

## 2. 범위 초과 변경 별도 판정 — TradingScheduler에 logDropped 호출 추가

### 필요했는가 — **그렇다. ADR-001이 요구하는 것이고, 없으면 기능이 0이다.**

- ADR-001 §2.6(284-288줄): "드롭된 모든 신호는 OpportunityCostLogger에 ... **무조건 기록한다.
  침묵 처리하지 않는다.**"
- `docs/TRADING-RULES-AUDIT.md:163` **F-14**가 "OpportunityCostLogger 연동 — 미구현"으로
  이미 결함 등록돼 있었다.
- 구현자 주장("호출부 0개였다")을 독립 검증했다: 저장소 전체에 logDropped 호출부가 없었고
  그 자리에 있던 것은 **TODO 주석 한 줄**뿐이다(§1-1의 제거 줄 참조).
  -> **호출을 안 넣었으면 테이블은 영원히 비어 있다.** 지시의 목적("왜 안 샀나를 남긴다")이
  달성 불가였다.

### 최소였는가 — **그렇다.**

추가 = else 분기 1개 + 호출 1줄 + 생성자 인자 1개 + import 1줄 = **9줄 추가 / 2줄 제거**.
판정식·주문 호출·게이트 5개 전부 무변경(§1-1). 더 작게 만들 방법이 없다.

### 위험은 무엇인가

| 위험 | 평가 |
|---|---|
| 통과 경로 오염 | **없음** — if (result.isPass()) 본문 불변, 새 호출은 else에만 |
| 루프 정지 | **없음** — catch 3겹 + finally(§1-2) |
| 스레드 점유 | 낮음 — 수 ms INSERT, 그나마 10분에 1회(§1-5) |
| 기동 실패 | **없음** — OpportunityCostLogger는 프로필 없는 @Component, TradingScheduler는 paper 전용 -> 주입 항상 성립 |
| **회귀 시험 부재** | **M-2** — 이 변경만 테스트가 없다 |
| ADR §2.6 "무조건 기록" vs 10분 억제 | 형식상 긴장. 다만 log.warn(`OpportunityCostLogger:42`)은 **억제 없이 매번** 나가고 억제분은 blockedCount로 이월되므로 "침묵 처리"에는 해당하지 않는다고 본다. **판단 보류 + 양쪽 병기**: 엄격 해석이면 ADR 개정 또는 suppress-minutes: 0이 정본 |
| ADR §2.6이 요구한 "스코어·Capacity 상태" 미기록 | 해당 개념이 Sleeve A/Capacity Scaling 소속이고 **Sleeve A는 구현 보류**(CLAUDE.md 규칙 6) -> 지금 넣는 것이 오히려 위반. 부분 이행이 맞다 |

**결론: 범위 초과는 정당하고 최소였다.** 다만 M-2(테스트 부재)를 붙여서 통과시킨다.

---

## 3. 위반 항목

### M-1 (MEDIUM) — "호출부 9곳 전부 비트랜잭션"이라는 안전 전제가 **호출 스택 기준으로 거짓이다**

| 항목 | 내용 |
|---|---|
| 위치 | `ModeTransitionRecorder.java:20-24`(주석의 설계 전제) · `StopLossArmer.java:51-56, 77, 88` · `ResearchController.java:79, 92, 206` |
| 근거 | CLAUDE.md 안전장치 설계 / `23_impl` §6-3 |

**무슨 일인가.** 구현자는 기록기에 @Transactional을 일부러 안 붙였고, 이유를
"안 붙이면 스프링 데이터 리포지토리가 **자기 트랜잭션을 열고 닫으므로** 커밋 실패까지
안쪽 catch가 받아낸다"(`ModeTransitionRecorder.java:20-24`)라고 적었다.
이 말은 **주변에 트랜잭션이 없을 때만** 참이다. 트랜잭션이 이미 열려 있으면
SimpleJpaRepository.save()는 기본 전파(REQUIRED)로 **그 트랜잭션에 합류한다.**

**"9곳 전부 비트랜잭션"은 changeMode를 부르는 메서드 자신만 본 것이다.** 호출 스택을 따라가면
트랜잭션 안에서 changeMode에 도달하는 길이 실재한다:

```
FillPoller(@Scheduled) -> FillStateUpdater.applyFill(@Transactional) 커밋
  -> StopLossArmer.onOrderFilled                                   StopLossArmer.java:51-52
        @TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW)   <- 새 트랜잭션 열림
  -> arm() -> marketDataService.getDailyCandles(...)                            :77   <- 트랜잭션 안에서 KIS HTTP
  -> KisApiClient.executeTracked -> recordFailure() / recordSuccess()      :268 / :275
  -> triggerSafeModeIfRunning() / maybeAutoResume()                        :297 / :310
  -> statusManager.changeMode(...)                                         :299 / :318
  -> TradingStatusManager.recordQuietly :71 -> ModeTransitionRecorder.record :56
  -> repository.save(...)   <- ★ StopLossArmer의 REQUIRES_NEW 트랜잭션에 합류한다
```

같은 형태가 하나 더 있다: `ResearchController.addToWatchlist`(`:79 @Transactional`)가
`:92 fetchQuote()` -> `:206 kisApiClient.getClient()`로 트랜잭션 안에서 KIS를 부른다.

**귀결 2가지.**

1. **(덜 흔하지만 나쁜 쪽)** mode_transition INSERT가 실패하면 스프링이 주변 트랜잭션을
   **rollback-only로 표시**한다. 안쪽 catch가 예외를 삼켜 실행은 계속되고
   `StopLossArmer:88`이 positionRepository.save(pos)로 손절선을 넣고 `:89`에
   **"손절선 장착" 성공 로그까지 찍는다.** 그러나 커밋 시점에 UnexpectedRollbackException으로
   **손절선이 통째로 롤백된다** — 로그는 성공, DB는 미장착.
   되짚어 보면 KIS 호출이 *실패*해서 SAFE_MODE로 넘어가는 경우엔 getDailyCandles가
   먼저 던져 어차피 미장착이다. **실제로 손해가 나는 쪽은 자동재개(maybeAutoResume) 경로** —
   KIS가 *성공*해 연속 성공 3회로 RUNNING 복귀할 때다. 이때는 원래 손절선이 장착됐을 상황이다.
2. **(더 흔하고 기능을 갉는 쪽)** INSERT가 성공해도, 주변 트랜잭션이 나중에 롤백되면
   **모드 전환 이력 행이 함께 사라진다.** 하필 사라지는 것이 SAFE_MODE 전환 기록 —
   이번 작업이 남기려던 바로 그 순간이다.

**왜 CRITICAL/HIGH가 아닌가 (판단 근거를 같이 남긴다).**
- 발동에 **복합 조건**이 필요하다: 매수 체결 + 임계 교차 + INSERT 실패가 한 순간에 겹쳐야 한다.
- 보상 장치가 둘 있다: `ShadowPortfolioReconciler`(`:76 @Scheduled(fixedRate=600_000)`)의
  armMissingStops()가 10분마다 "보유분은 반드시 손절선을 갖는다"를 강제하고,
  TimeCutScheduler 15:15가 최후 방어선이다.
- 청산 경로(LiquidationService)는 **전용 executor 스레드**(`:28-31`)라 주변 트랜잭션이 없다 ->
  가장 중요한 경로는 영향권 밖이다. 이 점이 등급을 낮추는 가장 큰 이유다.

**부수 발견 — 코드가 자기 주석과 모순된다.**
`TradingStatusManager.java:61-63`은 "그 메서드는 @Transactional(REQUIRES_NEW)라서 커밋 실패는
메서드 바깥(프록시)에서 터진다"고 적혀 있는데, ModeTransitionRecorder.record에는
**@Transactional이 아예 없다**(`:54`). 두 파일의 설명이 정반대다.
흥미롭게도 **주석 쪽이 옳은 설계**다 — 아래 §5 권고 참고.

---

### M-2 (MEDIUM) — 범위 초과 변경만 테스트가 없다

| 항목 | 내용 |
|---|---|
| 위치 | `src/test/java/com/trading/scheduler/TradingSchedulerTest.java` |
| 근거 | 골든 원칙 3·10, verification.md |

TradingSchedulerTest의 @DisplayName 전수(7건)는 **진입 게이트와 라운드로빈만** 다룬다
(`:129` 장 시작 전 · `:139` 장 마감 후 · `:149` 장중 · `:165` 라운드로빈 · `:178` 빈 유니버스 ·
`:188` KIS 미설정 · `:199` 휴장일). **isPass() 분기 자체를 검증하는 테스트가 0건이다.**

이번 diff가 그 파일에 한 일은 생성자 인자 `new OpportunityCostLogger()` 3곳 추가가 전부다
(기계적, 기록기 null 경로 — `:96`, `:125`, `:213`). 따라서 다음이 **전부 미검증**이다:

- 거부 시 logDropped가 **불리는가**
- 통과 시 logDropped가 **안 불리는가**  <- 회귀했을 때 가장 위험한 쪽
- 터지는 로거를 끼워도 run()이 예외를 안 던지고 다음 신호로 넘어가는가

구현자는 `RiskBlockRecorderTest:194`에서 "터지는 기록기를 끼워도 OpportunityCostLogger는
예외를 안 흘린다"를 고정했다 — **삼킴 자체는 검증됐다**(고 문서가 주장한다).
빠진 것은 **배선**이다. 지시서가 "특히 정밀하게 보라"고 한 변경이 하필 테스트가 가장 얇다.

---

### M-3 (MEDIUM) — TradeStatsService는 요청마다 주문 전량 + 분봉 수만 행을 훑는다

| 항목 | 내용 |
|---|---|
| 위치 | `TradeStatsService.java:97-99` · `SellPriceEstimator.java:61-63, 96-99` |

window()는 **기간과 무관하게** findByFilledQuantityGreaterThan(0)으로 체결 주문을
**전부** 읽고(`:98`), FIFO 짝짓기를 전 기간에 돌린 뒤(`:97`) days로 자른다(`:102-103`).
설계 이유는 타당하다(`:28-30` 주석 — 기간 앞 매수가 잘리면 멀쩡한 거래가 "짝 없음"이 된다).

문제는 비용이다. 매도 1건마다 (종목,날짜) 분봉을 읽는데 하루치가 391행이고,
캐시는 **요청 1회 수명**(`:61-63`)이라 요청마다 새로 읽는다.
구현자가 보고한 현재 규모(체결 매도 104건, 거래 90건)를 그대로 받으면 요청당
대략 **2~3만 행 스캔**이며, 주문·분봉이 쌓일수록 선형 증가한다.

- 위험도를 낮추는 사실: **Tomcat 요청 스레드**에서 돌고, H2 MVCC라 읽기가 쓰기를 막지 않는다.
  스케줄러 스레드(§1-5)와 직접 경쟁하지 않는다.
- 위험도를 높이는 사실: 화면이 이 엔드포인트를 **주기 폴링**하면 장중 내내 같은 스캔이 반복된다.
  프런트(`static/`)는 이번 판정 대상이 아니라 **폴링 여부를 확인하지 못했다** —
  화면 작업자에게 "수동 갱신 또는 긴 주기"를 못 박아 둘 것을 권한다.

---

### M-4 (MEDIUM) — 강제청산 개시 앞에 동기 DB 쓰기가 새로 한 칸 생겼다

| 항목 | 내용 |
|---|---|
| 위치 | `LiquidationService.java:58` -> `TradingStatusManager:53` -> `ModeTransitionRecorder:56` |

executeForceLiquidation()은 `:58 changeMode(FORCE_LIQUIDATING)` -> `:61 미체결 취소` ->
`:64~ 전량 매도` 순이다. 이제 `:58`이 **동기 INSERT 1건**을 포함한다.
DB가 멈추면 그만큼 청산이 늦어진다 — 돈이 걸린 경로다.

**차단 사유는 아니다.**
- ADR-001 상태머신 자체는 이미 `:43`에서 phase.getAndSet(FULL_LIQUIDATING)으로 선점돼 있고,
  `:81-82` finally도 모드 먼저·기록 나중이라 **종단 수렴은 보장된다.**
- 바로 다음 줄 `:59`가 이미 **블로킹 텔레그램**(connect 3s + read 5s)이다.
  수 ms INSERT는 그 옆에서 3자릿수 작다 — 새로 만든 위험이라기보다 **기존에 수용된 위험의 연장**이다.
- mode_transition은 신규 테이블이고 이 스레드 말고 쓰는 곳이 없다 -> 락 경합 대상이 없다.

기록으로 남겨 두고, M-1을 고칠 때 이 경로도 함께 보면 된다.

---

### LOW

| # | 내용 | 위치 | 판단 |
|---|---|---|---|
| L-1 | 주석이 코드와 모순 — @Transactional(REQUIRES_NEW)라고 써 있으나 실제로는 애너테이션 없음 | `TradingStatusManager.java:61-63` vs `ModeTransitionRecorder.java:20-24, 54` | M-1과 같은 뿌리. 다음 사람이 "이미 REQUIRES_NEW니 안전하다"고 오판할 소지 -> 문구부터 맞출 것 |
| L-2 | 기록기는 !backtest(real 포함)인데 정리 배치는 paper 전용 -> real에서 무한 증가 | `RiskBlockRecorder:38` vs `RiskBlockRetention:26` | 20_audit M-3 패턴의 재발. 실전 전환은 게이트 G2(사람) 선행이므로 **그 게이트 안에서** 처리 |
| L-3 | 하루 행 수 상한이 문서의 780행이 아니라 최대 10,920행(§1-4) | `23_impl` §5 표 | 폭증은 아니나 용량 계획 숫자를 현실값으로 고칠 것 |
| L-4 | RiskBlockRecord.of가 reason만 클립하고 stockCode(20)·ruleName(60)·strategyName(60)은 클립 안 함 | `RiskBlockRecord.java:62-77` | 현재 값은 전부 상한 내(전략명 최장 약 29자). 초과 시 INSERT 실패 -> catch -> 행 유실(매매 영향 0). 대칭성 차원의 지적 |
| L-5 | MAX_KEYS(500) 초과 시 byKey.clear() -> 억제 카운트 전량 유실 + 직후 저장 버스트 | `RiskBlockRecorder.java:90-94` | 현실 최대 280키라 도달하기 어렵다. 유니버스가 장기간 교체되면 누적될 수 있음 |
| L-6 | 보존 정리(23:20)는 그 시각 앱이 떠 있어야 돈다 / backtest-db에 빈 테이블 2개 생성 | `RiskBlockRetention.java:44` · `application-backtest.yml:20` | MinuteCandleRetention 23:10과 동일 관행. backtest-db 빈 테이블은 쓰는 코드가 없어 결정성 영향 0 |

---

## 4. 20_audit M-1·M-2·M-3 델타

| | 이번 변경의 영향 |
|---|---|
| **M-1** (전고점 알림 사각지대) | **무관.** 해당 파일은 이번 증분이 아니다. 여전히 미확인 |
| **M-2** (1초 루프 블로킹 HTTP 8초) | **좋아졌다 — 단 이번 작업 덕분은 아니다.** BackgroundAlertSender(별도 변경, 09-22 00:31)가 전고점 알림을 전용 스레드로 뺐다. **이번 변경은 새 블로킹 HTTP 0건**이므로 악화 없음. 그 파일(`:13-24`)이 "스프링 기본 스케줄러 풀은 1개" 전제를 **독립적으로 재확인**해 주고, `DailyPnlRecorder:91-94`의 실측(+321초 크론 지연)이 뒷받침한다 -> M-2의 전제는 이제 정적 추론 이상이다 |
| **M-3** (real 프로필 공백) | **조금 나빠졌다(L-2).** RiskBlockRetention이 paper 전용이라 real 공백 목록에 한 항목이 늘었다. 이번 변경이 만든 구조적 결함은 아니며 게이트 G2 안에서 다룰 것 |

---

## 5. 어떻게 고쳐야 하는가 (권고만 — 코드는 손대지 않았다)

| # | 권고 | 비고 |
|---|---|---|
| M-1 | ModeTransitionRecorder.record와 RiskBlockRecorder.record에 **@Transactional(propagation = REQUIRES_NEW)를 붙인다.** 그러면 주변 트랜잭션과 완전히 분리돼 rollback-only 오염이 사라지고, 프록시 바깥에서 터지는 커밋 실패는 **이미 있는 바깥 catch**(`TradingStatusManager:72`, `OpportunityCostLogger:56`)가 받는다 — 즉 `TradingStatusManager:61-63` 주석이 묘사한 그 설계가 된다. **코드를 주석에 맞추는 것이지 새 설계가 아니다** | 새 커넥션 1개를 잠깐 더 쓴다. 두 겹 catch가 이미 있으므로 추가 방어는 불필요 |
| M-1 보조 | 고치기 전까지는 `ModeTransitionRecorder.java:20-24`와 `TradingStatusManager.java:61-63`의 **상충 문구를 하나로 통일**할 것 | 문서만 고치고 코드를 안 고치면 M-1은 남는다 |
| M-2 | TradingSchedulerTest에 3건 추가: (1) 거부 시 logDropped 호출됨 (2) 통과 시 호출 안 됨 (3) 터지는 로거로도 run()이 안 던짐. 기존 테스트가 쓰는 new OpportunityCostLogger() 대신 기록 여부를 볼 수 있는 대역을 끼우면 된다 | RiskBlockRecorderTest:194 패턴 재사용 가능 |
| M-3 | 화면에서 /api/performance/{trades,by-stock,by-bucket}을 **주기 폴링하지 말 것**(수동 갱신 또는 분 단위 이상). 필요해지면 TradeStatsService에 짧은 TTL 캐시 | 화면 작업자에게 전달 필요 |
| M-4 | M-1을 고치면 이 경로의 커넥션 획득도 분리된다. 별도 조치 불필요 | |
| L-3 | 23_impl §5의 "780행/일"을 "현실 약 2,700 / 상한 10,920"으로 정정 | |
| L-1·L-2·L-4·L-5·L-6 | 배포 차단 사유 아님. BACKLOG.md 등재 | |

**배포 판단:** CRITICAL 0 · HIGH 0 -> **오늘 개장 전 배포 가능.**
M-1·M-2는 배포 후 같은 스프린트 안에서 해소할 것. real 전환은 별개이며
`docs/TRADING-RULES-AUDIT.md` CRITICAL 해소 + **게이트 G2(사람) 선행**이 변함없는 전제다.

---

## 6. 감사하지 못한 것 (미확인 — 추측으로 메우지 않았다)

1. **빌드·테스트 미실행**(지시). `23_impl` §9의 **785/785 통과·종료 코드 0·Red-Green 4건**은
   **구현자 문서의 주장을 인용한 것이지 내가 재현한 것이 아니다.** 나는 컴파일조차 확인하지 않았다.
2. **스프링 컨텍스트 실기동 미검증.** 프로필·빈 배선 판정은 전부 애너테이션 정적 대조다.
   ObjectProvider 주입, 새 파생 쿼리 이름 파싱, 3-생성자 @Autowired 선택 모두 **런타임 미확인**.
   (구현자는 TradingStatusManagerTest$Wiring·RiskBlockRecorderTest$Wiring·DiagnosticsQueryTest로
   덮었다고 적었으나 — 역시 인용이다.)
3. **@Scheduled 풀 크기 1은 여전히 런타임 미측정.** 설정 부재 + 코드 주석 2곳
   (`DailyPnlRecorder:91-94`, `BackgroundAlertSender:13-24`)의 일치에서 온 추론이다.
   M-1/§1-5의 지연 폭은 실측하지 않았다.
4. **DB 쓰기 지연·락 대기 실측 없음.** "수 ms"는 로컬 H2 단일 INSERT에 대한 통상값이고
   이 장비에서 재보지 않았다.
5. **M-1 시나리오를 재현하지 못했다.** 트랜잭션 합류는 스프링 데이터 JPA 전파 규칙
   (SimpleJpaRepository.save가 REQUIRED)에서 연역한 것이다. 실제 rollback-only 발생과
   손절선 유실은 **관측하지 않았다.**
6. **실제 행 수 미관측.** §1-4의 수치는 창 산식으로 계산한 것이고, 새 테이블은 아직
   한 줄도 없다(다음 기동 때 ddl-auto: update로 생성).
7. **구현자 §8의 운영 DB 검산(체결 매도 104건 / 추정 가능 90건 등)을 재현하지 않았다** —
   전부 인용이다. DB 사본을 열지 않았다.
8. **`static/` 프런트엔드는 판정 대상 밖**(동시 편집 중). M-3의 폴링 주기를 확인 못 한 이유다.
   `_workspace/22_impl_dashboard-frontend.md`도 읽지 않았다 — 지시된 범위 밖.
9. **SCOPE_GUARDIAN.md 릴리즈 체크리스트**와 이번 증분의 대조는 하지 않았다(지시 범위 밖).
   항목 추가·삭제 여부는 별도 확인이 필요하다.
