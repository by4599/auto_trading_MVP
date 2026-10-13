# 27_impl — 장중 SAFE_MODE 플래핑 수정 (2026-09-30)

> 근거: `_workspace/25_ops_safemode-flapping-diagnosis.md` §6 측정표.
> 지시받은 3건(A 눈먼 시간 조건 · B 장외 잔고 호출 중단 · C HTTP 500 본문 로깅)을 구현했다.
> **앱(8080)은 끄지 않았다** — 배포는 사람이 한다. 실행 중 앱은 수정 전 코드로 계속 돌고 있다.

## 0. 한 줄

SAFE_MODE 전환 조건에 **"마지막 성공 이후 90초 연속 실패"**를 AND로 붙였고(문턱 3은 그대로),
장외에 보유가 0이면 잔고 API를 아예 부르지 않게 했고, HTTP 500 본문을 **비밀값을 가린 200자**로
남기게 했다. **830/830 통과 · 종료 코드 0** (직전 기준선 812 + 신규 18).

## 1. 바꾼/만든 파일

| 파일 | 줄 수 | 무엇을 | 왜 |
|---|---|---|---|
| `src/main/java/com/trading/market/KisApiClient.java` | 363 → **430** | (A) `BLIND_MILLIS_BEFORE_SAFE_MODE` 상수 + `blindSince` 필드 + `recordFailure()` 판정식 + `blindMillis()` · 생성자에 KST `Clock` 주입 · (C) 500 로그에 본문 추가 + `secretsToMask()` | 판정이 호출 횟수만 봐서 11초 실패 뭉치에도 자체 정지했다 |
| `src/main/java/com/trading/market/KisErrorBodySnippet.java` | **신규 57** | (C) 오류 본문 가리기·자르기 전용 | 비밀값 처리 로직을 따로 떼어 단독 검증 가능하게. `KisApiClient`를 더 불리지 않기 위함 |
| `src/main/java/com/trading/position/KisPositionManager.java` | 158 → **194** | (B) `canSkipBalanceCall()`/`hasHoldings()` + `snapshotAccount()` 앞단 분기 · `MarketCalendarService` 주입 | 장외 잔고 실패(16시 한 시간 135건)가 (A) 카운터에 쌓여 아침을 SAFE_MODE 근처에서 시작시켰다 |
| `src/test/java/com/trading/market/KisApiClientTest.java` | 236 → **420** | 신규 `BlindWindowTest` 6건 + 기존 2건에 시계 전진 | 아래 §5 |
| `src/test/java/com/trading/market/KisErrorBodySnippetTest.java` | **신규 87** | (C) 6건 | 비밀값 유출 점검 |
| `src/test/java/com/trading/position/KisPositionManagerTest.java` | 165 → **284** | 신규 `OutsideMarketHoursTest` 6건 + 조립에 `MutableClock`+실제 캘린더 | 아래 §5 |

### 건드리지 않은 것 (확인 완료)

- `RiskEngine` · 14개 리스크 룰 · `LiquidationService`/`LiquidationPhase` · `Account.isFresh()` 의미 — **0줄**
- `Strategy→Signal→RiskEngine→OrderEngine` 경로 — **0줄**
- `pausedByConnectionLoss` / `maybeAutoResume()` (자동 RUNNING 복귀) — **0줄**
- 토큰 발급 실패 경로(`refreshToken` → `triggerSafeModeIfRunning`) — **0줄**
- 다른 사람의 취소 재시도 수정분(`order/FillProcessor`·`KisOrderCancelClient`·`OrderCancelClient`·
  `CancelRetryGate`·`risk/KisBrokerageApiClient`) — 읽기만 했다. `git diff`상 내 변경 0줄
- `static/` · `com.trading.backtest` · `com.trading.dashboard` — **0줄**

⚠ **규칙 이탈 1건**: `KisApiClient`가 430줄로 300줄 상한을 넘는다. **원래도 363줄로 넘어 있었다.**
더 줄이려면 토큰 관리나 SAFE_MODE/자동복귀 판정을 밖으로 빼야 하는데 둘 다 "건드리지 마라"로
지정된 곳이다. 그래서 새로 생긴 책임(C)만 별 파일로 뺐다. 분리는 별건으로 남긴다.

## 2. (A) 판정식 before/after

```
before:  연속 실패 >= 3                              → SAFE_MODE
after :  연속 실패 >= 3  AND  눈먼 시간 >= 90,000ms   → SAFE_MODE
```

- `CONSECUTIVE_FAILURE_THRESHOLD = 3`은 **그대로**다. 조건을 하나 *더* 붙였을 뿐이라
  오발동만 줄고 진짜 장애 감지는 둔해지지 않는다.
- 조건 미달이면 `log.warn("KIS API 연속 N회 실패 — 아직 X초째라 멈추지 않습니다 (하한 90초)")`로
  남긴다(침묵하지 않는다).
- 시각은 **주입된 KST `Clock`**(`ClockConfig`)만 쓴다. `System.currentTimeMillis()`·`Instant.now()`
  직접 호출 0건.
- 상수는 코드에 박고 설정 파일로 빼지 않았다(지시대로). 근거 측정을 상수 주석에 남겼다.

### 90초의 근거 (25_ops §6)

장중 실패 뭉치 108개 중 **대다수가 0초(고립된 단발)**, 45초 초과 6개, 60초 초과 4개,
**90초 초과 1개(229초)**. 그런데 SAFE_MODE는 9회 걸렸고 그중 둘은 **11초 뭉치**였다.

### ⚠ 기준점을 "마지막 성공 시각"이 아니라 "연속 실패가 시작된 시각"으로 잡았다 — 지시에서 한 발 벗어남

지시는 "마지막 성공으로부터 90초"였다. 그대로 구현하면 **(B)와 충돌해 아침 오발동이 그대로 남는다**:
(B) 때문에 장외에는 잔고 호출이 끊기므로 마지막 성공이 전날 오후가 된다 → 개장 직후 첫 실패 3회가
곧바로 "17시간째 눈먼 상태"로 계산돼 즉시 SAFE_MODE. 실측 9회 중 09:08:48이 바로 그 시간대다.

그래서 `blindSince`를 **연속 실패가 0→1로 시작될 때** 찍는다. 1초 폴링이 이어지는 장중에는 두 정의가
사실상 같고(마지막 성공은 직전 0.1초 전), 호출이 끊긴 구간에서만 다르다. 성공이 한 번 끼면
`consecutiveFailures`가 0으로 리셋되므로 다음 실패에서 다시 찍힌다 = **눈먼 시간도 리셋된다**.

부작용(정직하게): 첫 실패 자체가 소비한 대기 시간(읽기 타임아웃 최대 10초, 500 재시도 약 7초)은
안 세므로 실제로는 90초보다 10~20초 늦게 걸린다. 229초 장애에는 영향 없다.

## 3. (B) 안전성 논증 — 지시받은 세 가지 확인

조건: **`marketCalendar.isDuringMarketHoursNow() == false` AND 보유 0** → KIS 호출 없이
마지막 스냅샷을 `asStale()`로 반환(캐시가 없으면 기존 `fallbackFromDb()`, 역시 낡음 표시).

| 확인 항목 | 결과 | 근거(코드 실측) |
|---|---|---|
| ① 청산·손절이 낡은 값이면 건너뛰는가 | **예** | `RiskMonitor:93 if (!account.isFresh()) return;` · `StopLossMonitor:91 if (!account.isFresh()) return;` |
| ② 전고점이 낡은 값으로 갱신되는가 | **아니오(갱신 안 함)** | `ShadowPortfolio:113 if (!account.isFresh()) { ... return; }` |
| ③ 매수 차단 룰이 낡은 값에 영향받는가 | **아니오** | 낡으면 보수적 차단 쪽이고, 게다가 장외에는 `TradingScheduler:89`가 `isDuringMarketHoursNow()`로 루프 자체를 돌지 않아 신호 판정이 없다 |

즉 **지금 장외에 "우연히" 일어나는 일(호출 → 실패 → 낡음 → 스킵)을 호출 없이 결정론적으로
만드는 것**이고, 판정 동작은 바뀌지 않는다. 대신 그 실패가 (A) 카운터에 쌓이지 않는다.

- **보유가 있으면 장외에도 계속 부른다.** 지금 paper는 15:15 타임컷으로 밤에 보유가 없지만
  다일 보유 칸이 켜지면 밤샘 감시가 필요하다. 보유 판단은 보수적으로 두 군데를 본다 —
  브로커가 마지막으로 알려준 보유분(캐시) **또는** 우리 `position` 테이블(`count()`).
- **체결누락 desync가 묻히지 않는가**: `ShadowPortfolioReconciler`는 `snapshotAccount()`가 아니라
  `balanceClient.fetchBalance()`를 직접 쓰고 장중에만 돈다(`:79`) → 이 변경에 영향 없음.

### ⚠ 부수 효과 2건 (해롭지 않다고 판단했으나 기록해 둔다)

1. **`daily_equity`의 당일 시작 자산이 00:00:0x 대신 개장 첫 스냅샷에 찍힌다.**
   실측 확인: 09-22·09-23 모두 `00:00:02`에 찍혔고 값은 전일 종가 자산과 같다
   (9,787,960 / 9,751,155). **보유가 0일 때만 스킵하므로 그때 총자산은 전액 현금이고
   밤새 변하지 않는다** → 기록 시각만 옮겨지고 값은 같다. 보유가 있으면 스킵하지 않으므로
   다일 보유 시에도 기준이 전일 종가로 유지된다. `dailyPnlPercent`(= `DailyLossRule` −3% ·
   `RiskMonitor` −5% 입력) 의미 불변.
2. **휴장일(주말·공휴일)에는 `daily_equity` 행이 아예 생기지 않는다.** 그 행은 금요일 종가의
   중복이었고, `/api/performance/account`의 시계열·MDD·전고점 최댓값은 달라지지 않는다.
3. 장외에 앱을 새로 띄우고 보유가 0이면 `/api/risk/status`의 `dailyPnlPercent`가 개장까지
   0.0으로 보인다(캐시가 없어 DB 폴백). **표시 전용**이고 `consecutiveLossCount`는
   `portfolio_state`에서 오므로 정확하다. 성적 탭은 `daily_equity`를 읽어 무관.

## 4. (C) 비밀값 노출 점검

`HTTP 500 수신 — {}ms 후 재시도 ({}/3) 본문={...}` 형태로 남긴다.

- 가리는 값: **appkey · secretkey · 계좌번호 · 현재 토큰** (`secretsToMask()`)
- **가리기 → 자르기 순서**를 지킨다. 반대로 하면 200자 경계에서 비밀값이 반토막으로 샌다 —
  Red 실험에서 실제로 `50000000-0`이 로그에 남는 것을 확인했다(§5).
- 빈/공백 비밀값은 무시한다. 넣으면 `replace("", "***")`가 로그 전체를 `***`로 만든다(테스트로 고정).
- 길이 상한 200자 + `…(생략)`, 여러 줄은 한 줄로 접는다.
- **토큰 만료(EGW00123) 경로와 충돌하지 않는다**: 그 판정은 재시도 루프 *앞*에서 끝나고
  `return`하므로 이 로그 경로로 오지 않는다.
- 본문을 못 읽어도 예외를 던지지 않는다(로그 한 줄 때문에 호출이 실패하면 안 된다).
- ⚠ 이건 **진단선이지 수정이 아니다.** 500의 이유는 여전히 미규명이다(§7).

## 5. 고정한 테스트 · Red-Green

### 신규 18건

`KisApiClientTest$BlindWindowTest` (6)
- `three_failures_within_blind_window_does_not_trigger_safe_mode` — 3회 + 89초 → RUNNING
- `three_failures_after_blind_window_triggers_safe_mode` — 3회 + 정확히 90초 → SAFE_MODE
- `success_in_the_middle_resets_the_blind_window` — 성공 1회 후 9초 안 3회 실패 → RUNNING
- `measured_eleven_second_cluster_no_longer_trips` — **실측 10:04:03 재현** → RUNNING
- `measured_two_hundred_twenty_nine_second_outage_still_trips` — **실측 13:34:38 재현** → SAFE_MODE
- `token_refresh_failure_ignores_the_blind_window` — 시계 전진 0초인데 SAFE_MODE

`KisPositionManagerTest$OutsideMarketHoursTest` (6)
- `outside_market_hours_without_holdings_skips_balance_api` — 호출 0회 · `isFresh()==false`
- `outside_market_hours_returns_last_snapshot_as_stale` — 마지막 값 그대로 · 추가 호출 0
- `outside_market_hours_with_holdings_still_calls_balance_api` — **계속 호출**
- `outside_market_hours_trusts_last_broker_holdings_too` — DB 0인데 브로커 보유 있으면 계속
- `during_market_hours_calls_balance_api_even_without_holdings` — 장중은 기존과 동일
- `holiday_is_outside_market_hours_even_at_ten_am` — 토요일 10:00도 장외

`KisErrorBodySnippetTest` (6) — 4종 비밀값 가리기 / 200자 자르기 / 빈 비밀값 무시 /
빈 본문 / 여러 줄 접기 / 응답에서 직접 읽기

### 기존 테스트 2건은 시계를 전진시키도록 고쳤다 (의도한 동작 변경)

`three_consecutive_api_failures_trigger_safe_mode` · `auto_resumes_to_running_after_connection_recovers`
— 둘 다 "3회 실패 → SAFE_MODE"를 전제하므로 이제 90초를 태워야 한다. 삭제·완화 없이 시각만 넣었다.

### Red-Green (전부 이번에 실행)

| 실험 | 되돌린 것 | 결과 |
|---|---|---|
| A | `recordFailure()`의 90초 분기 비활성 | **13개 중 3개 실패** — 11초 뭉치·90초 미경과·성공 리셋. 229초 장애와 토큰 경로는 **계속 통과**(= 안전망이 느슨해지지 않았다는 증거) |
| B | `snapshotAccount()`의 스킵 분기 비활성 | **14개 중 3개 실패** — 스킵 3건. "계속 호출" 3건은 계속 통과 |
| C | 가리기/자르기 순서 뒤집기 | **6개 중 1개 실패**, 메시지에 `...xxxxx50000000-0` — 계좌번호 반토막이 실제로 샜다 |

세 실험 모두 복구 후 재실행해 전부 통과 확인. `grep -rn "RED 실험" src/main/` 매치 0건.

## 6. 측정표 재적용 — 09/22의 9회는 몇 회가 되나

| SAFE_MODE(실측) | 직전 뭉치 | 새 로직 |
|---|---|---|
| 09:08:48 | 44초 | ✗ 안 걸림 |
| 09:25:11 | 38초 | ✗ |
| 09:39:53 | 58초 | ✗ |
| 10:04:03 | **11초** | ✗ |
| 10:12:48 | 16초 | ✗ |
| 13:32:48 | 64초 | ✗ |
| **13:34:38** | **229초** | **✓ 걸린다** |
| 14:54:35 | **11초** | ✗ |
| 15:00:53 | 31초 | ✗ |

**9회 → 1회.** 정지 시간은 6분 38초(9조각) → 약 2분 20초(1조각, 90초 지점에서 걸려 회복까지).

⚠ **이 재적용의 한계**: §6의 "뭉치"는 *실패 로그만* 30초 규칙으로 묶은 것이다. 성공은 DEBUG라
로그에 없어 **뭉치 안에 성공이 섞였는지 확인할 수 없다.** 섞였다면 내 카운터가 리셋되므로
229초 건도 안 걸릴 수 있다. 즉 위 표는 **상한**이다(새 로직의 전환 횟수 ≤ 1). 방향은 안전하지
않은 쪽이 아니라 "덜 멈추는" 쪽이고, 그 구간의 판정은 `isFresh()` 게이트가 이미 건너뛴다.

## 7. 남은 것 / 하지 않은 것 / 확인하지 못한 것

- ⚠ **HTTP 500의 이유는 여전히 미규명이다.** (C)는 본문을 남기기 시작한 것뿐이고, 09/22의
  379건은 이미 지나갔다. **다음 장중 관측이 필요하다.**
- 실패 뭉치 108개 자체(모의 서버 응답 품질)는 그대로다. 이 변경은 **모드 진동을 멈추는 것이지
  시야를 좋게 하는 것이 아니다.**
- 25_ops §2의 **장외 취소 무한 재시도(5,983회)는 이 작업 범위가 아니다** — 다른 사람이
  수정 중(미배포). (B)는 그 카운터 압박을 잔고 쪽에서만 덜어낸다.
- 모드 전환 이력의 `reason` 칸은 여전히 null이다(`TradingStatusManager.changeMode`가 사유를
  안 받는다). 범위 밖이라 손대지 않았다.
- `READ_TIMEOUT_MS = 10_000`은 건드리지 않았다(지시대로 — 스케줄러 풀이 1개다).
- **실측 검증은 못 했다.** 장이 닫혀 있고(2026-09-30 20:58 이후) 앱은 수정 전 코드로 돌고 있다.
  배포 후 첫 거래일에 ① 모드 전환 횟수 ② 16시대 잔고 실패 건수 ③ 500 본문을 봐야 한다.
- `KisApiClient` 430줄(상한 300 초과, 원래 363) — 분리는 별건.

## 8. 검증 증거

```
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build \
      ~/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gvueggl8a5cioxuftxrik/gradle-9.2.0/bin/gradle \
      test --console=plain
종료 코드: 0
결과: 830개 중 830개 통과, 실패 0, 오류 0, 건너뜀 0
     (직전 기준선 812 + 신규 18 = 830 — 일치)
→ 판정: 통과
```
