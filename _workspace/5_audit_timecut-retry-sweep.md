# 5_audit — 타임컷 재시도 스윕 (TimeCutScheduler 15:20·15:24·15:28) 감사

감사자: `risk-auditor` (2026-09-07) · 코드 미수정, 위반 탐지만 수행
범위: `git status`/`git diff`로 확인한 워킹트리 실제 변경분 (`src/` 하위만)

**판정: CRITICAL 0 · HIGH 1 · MEDIUM 5 · LOW 5**

HIGH 1건(H-1)은 **이번 변경이 만든 것이 아니라 기존 결함**이지만, **이번 변경의 목적을
정면으로 무력화**한다. 해소 전까지 "타임컷 재시도가 완성됐다"고 말할 수 없다.
스윕 코드 자체에 배포 차단(CRITICAL) 사유는 없다.

## 이전 감사 이월 상태

`_workspace/2_audit_posttimecut-buy-block.md` (2026-09-01): CRITICAL 0 · HIGH 0 · MEDIUM 1 · LOW 5.

- MEDIUM 1 (백테스트 진입 시각 드리프트 가드 비대칭) → **해소 확인**.
  `DailyBarSimulator.java:41-46`에 `BREAKOUT_ENTRY_AT`/`CLOSE_ENTRY_AT` 상수 추출,
  `PostTimeCutBuyRuleTest.java:116-124`가 두 상수를 직접 대조. 값은 10:00/15:00 그대로이므로
  백테스트 동작·회귀 앵커 불변.
- LOW 5건은 상태 변화 없음 (지우지 않고 유지).
- 이월 CRITICAL·HIGH 없음.

## 감사자 독립 검증 (구현자 보고 미신뢰)

- 변경분 직접 확인: `git status --porcelain` → 워킹트리 수정 `Claude.md`, `SCOPE_GUARDIAN.md`,
  `DailyBarSimulator.java`, `TimeCutScheduler.java`, `TimeCutSchedulerTest.java` +
  신규 `PostTimeCutBuyRule(.java/Test)`, `TimeCutRetrySweepTest.java`.
  구현자 노트의 "3파일"보다 많다(선행 미커밋 작업 포함) — 노트가 아니라 diff로 감사했다.
- `RiskEngine.java` 변경 0건 (`git diff --name-only`에 없음) → 아키텍처 규칙 3 준수.
- 테스트 직접 재실행 (결과 XML timestamp 2026-09-06T16:13:58Z = KST 09-07 01:13):
  `TimeCutRetrySweepTest` tests=9/failures=0/errors=0, `TimeCutSchedulerTest` 13/0/0,
  `PostTimeCutBuyRuleTest` 8/0/0 — BUILD SUCCESSFUL. **전체 616건은 재실행하지 않았다.**
- 실사고 로그 직접 판독 (`logs/paper-2026-09-01.0.log.gz`):
  15:15:22 타임컷 개시(5종목) → 15:15:30 / 15:15:48 / 15:16:05 / 15:16:24 접수 →
  15:16:34 066570 요청 → **15:16:44 실패(10초 read timeout)** → 15:17:05 SAFE_MODE →
  15:21~15:22 잔고 API 연속 타임아웃 → 15:22:51 RUNNING →
  **15:23:27 Reconciler: 5종목 전부 "브로커 없음"** (= 066570도 실제로는 체결됨) →
  15:26:13~15:26:34 4건 FILLED 정렬.

## 필수 판정 9항목

### 1. 중복 매도 위험 — 조건부 통과 (M-1 · L-1 · L-2)

가드 순서(코드 확인): `retrySell` → `hasPendingSell`(`TimeCutScheduler.java:216-219`) →
`brokerConfirmedFlat`(`:220-222`) → `sellPosition`(내부에서 `hasPendingSell` 재확인 `:162-165`).
미체결이 있으면 잔고 조회조차 하지 않는다 (구현자 주장 그대로 — 코드·테스트 일치).

빠져나가는 구멍 전수:

| 경로 | 결과 |
|---|---|
| 타임아웃 주문은 DB에 **FAILED**로 기록(`KisOrderClientImpl.java:103-106`) → `hasPendingSell`=false | 브로커 조회가 유일한 방어 |
| 브로커 조회 성공·보유 0 | 재매도 안 함 (정상) |
| 브로커 조회 **실패** | 재매도 → **중복 매도 접수 가능** (M-1) |
| 브로커에 주문이 살아 있고 아직 미체결 | 잔고는 보유수량을 주므로 0이 아님 → 재매도 → 같은 주식에 매도 2건 (M-1) |
| 15:20·15:24·15:28 모두 조회 실패 | 최대 **3회** 중복 접수 |

최종 방어선은 **우리 코드가 아니라 브로커의 매도가능수량 검증**이다(보유 − 매도주문중).
KRX 현금계좌는 공매도가 불가해 초과분은 거부로 끝날 것으로 보이나, **코드·테스트 어디에도
이를 확인하는 근거가 없다.** 09-01 조건(15:21~15:22 잔고 API 연속 타임아웃 + 브로커 보유 0)에
이 코드를 대입하면 **실제로 중복 매도가 접수됐을 경로**다.

### 2. 불확실할 때의 방향 — 통과

`brokerConfirmedFlat`(`TimeCutScheduler.java:231-243`)은 `getActualHoldingQuantity(...) == 0`
**한 경우에만** true를 돌려주고, 예외는 로그만 남기고 false로 떨어져 재매도로 간다.
`getActualHoldingQuantity`(`KisBrokerageApiClient.java:97-103`)는 `balanceClient.fetchBalance()`를
그대로 타고, `KisBalanceClient.java:59-70`은 null·rt_cd 불일치·output2 없음에서 **예외를 던진다**
(폴백 0을 만들지 않는다). 즉 "통신 장애 = 재시도 억제"의 반대로 구현됐다 — 지시대로다.
회귀 테스트 존재(`TimeCutRetrySweepTest.java:175-185`).

### 3. 읽기 전용 사용 — 통과

`sendMarketOrder` 호출부 전수: `LiquidationService.java:165` **하나뿐**
(정의부 `BrokerageApiClient.java:6`, `KisBrokerageApiClient.java:88`, 백테스트 스텁 제외).
`TimeCutScheduler`는 `getActualHoldingQuantity`만 쓴다. 매도는 종전대로
`Signal.sell → riskEngine.check → orderEngine.execute`(`TimeCutScheduler.java:167-174`) —
ADR-001 2.3의 평시 매도 경로 유지. `strategy` 패키지에 주문 참조 0건.

### 4. 기존 15:15 경로 무변경 — 통과 (한 줄씩 대조)

`git show HEAD:...TimeCutScheduler.java`와 현재 파일을 조건 단위로 대조:

| 항목 | 변경 전 | 변경 후 | 판정 |
|---|---|---|---|
| 설정 미비 | `:80-82` 무로그 return | `canRun` `:126-128` 동일 | 동일 |
| 휴장일 | `:83-87` info 후 return | `:129-133` (태그만 파라미터화, 출력 문자열 동일) | 동일 |
| 모드 | `:88-92` RUNNING/SAFE_MODE 외 warn+return | `:134-138` 동일 | 동일 |
| 보유분 | `:94-96` findAll + qty>0 | `heldPositions()` `:142-146` 동일 | 동일 |
| 다일 보유 제외 | `:99-101` multiDayHold 부정 | `timeCutTargets()` `:153-157` 동일 | 동일 |
| kept 로그·빈 목록·종목별 try/catch | `:102-119` | `:104-121` 동일 | 동일 |
| `sellPosition`/`hasPendingSell` | `:122-145` | `:159-175`, `:261-266` **완전 동일** | 동일 |

조건이 미묘하게 달라진 곳 없음. 기존 13개 테스트도 기대값 무변경(생성자 인자 2개 추가만).

### 5. 청산 상태머신 양보 — 조건부 통과 (M-5)

`canRun`이 RUNNING/SAFE_MODE 외 전부 차단 → FORCE_LIQUIDATING·EMERGENCY_STOPPED에서 스윕은
아무것도 하지 않는다(테스트 `TimeCutRetrySweepTest.java:231-241`). 별도 청산 플래그 신설 없음 →
ADR-001 단일 상태머신 위반 없음. 다만 판정 기준이 mode뿐이라 M-5의 짧은 창이 남는다.

### 6. 다일 보유 칸 집합 일치 — 통과

스윕(`:194`)과 타임컷(`:102-103`)이 **같은 헬퍼**(`heldPositions` + `timeCutTargets`)를 호출한다 —
문자 그대로 동일 집합. 현재 어느 yml에도 `multi-day-hold`가 없어 전 칸 false(전량 대상).
`MaxHoldScheduler`(15:17)의 대상은 `maxHoldDays > 0`으로 서로 다르지만, 그 값 역시 어느 yml에도
없어 현재 공집합 → 겹침 없음.

### 7. 스케줄 시각 안전성 — 시각은 통과, 실행 시간은 M-3·M-4

- 15:20/24/28 모두 마감 15:30 전. 정각 충돌 없음: `MaxHoldScheduler` 15:17,
  `RunStreakRecorder` 15:25, `MinuteCandleCollector` 15:40. `LiquidationDrillScheduler`는
  매분이지만 `enabled=false`에서 즉시 return(`:61`).
- 스레드: `spring.task.scheduling.pool.size` 미설정 = **1** (09-01 로그의 모든 스케줄러가
  `[scheduling-1]`). 같은 스레드라 스윕끼리·스윕과 타임컷이 **겹칠 수 없다**(중복 매도 관점에선 유리).
- 대신 블로킹이 그대로 다른 감시를 굶긴다 → M-4.

### 8. 구현자가 밝힌 한계 검증 — 주장은 사실, 단 "과알림"으로만 기울지 않는다

"DB 수량은 체결 반영 뒤에야 줄어 재매도 성공 접수해도 알림이 나간다"는 맞다
(`alertIfStillHeld` `:245-249`가 리포지토리를 다시 읽는다). 그러나 모의 환경에서는
체결조회가 비어 오는 알려진 결함(CLAUDE.md 미구현/결함 5번) 때문에 **이미 팔린 종목에도**
헛알림이 난다(M-2) — 09-01을 그대로 재생하면 066570(실제로는 체결됨)이 알림 대상이 된다.
또 알림이 15:30을 넘기면 텔레그램 게이트에 막혀 **아예 안 나간다**(M-3).
즉 방향은 "과알림(안전)"과 "무알림(위험)" 두 갈래이며, 후자가 검토되지 않았다.

### 9. 비밀키·민감정보 — 통과

신규·수정 파일에 실키 0건. 테스트의 더미 키(`TimeCutRetrySweepTest.java:92-93`)는 기존
`TimeCutSchedulerTest`와 같은 패턴. 알림 문구(`:254-258`)는 종목코드·수량만 — 계좌번호·토큰 없음.
프로필 격리 유지: `TimeCutScheduler`는 `@Profile("paper")`(`:47`), `KisBrokerageApiClient`도
paper 전용 → backtest 무영향(`trading.bucket.enabled`는 `application-paper.yml:53-54`에만 true).

## 지적 사항

| 심각도 | 항목 | 위치 | 내용 | 근거 |
|---|---|---|---|---|
| HIGH | MDD 초과 시 **매도까지 거부** — 스윕의 목적 무력화 | `GlobalEquityStopRule.java:29-43` | 이 룰만 `signal.isBuy()` 가드가 없다(다른 12개 룰은 전부 첫 줄에서 SELL 통과). MDD 10% 초과면 타임컷·스윕·손절·최대보유의 매도 신호가 전부 RiskEngine에서 거부된다(`TimeCutScheduler.java:168-172`, `StopLossMonitor.java:138-146`, `MaxHoldScheduler.java:151-155`). 자동 강제청산이 보류되는 분기(`RiskMonitor.java:117-121` 전고점 미검증)와 낡은 스냅샷 스킵(`RiskMonitor.java:92-96`)에서는 **출구가 전부 닫힌 채 방치**된다. 룰 주석(`:10-11` "매수 거부만 담당")과 구현이 어긋나고 테스트도 매수만 본다(`GlobalEquityStopRuleTest.java:27`, SELL 케이스 0건). 09-01처럼 잔고 API가 죽는 상황에서 이 룰은 낡은 값으로도 판정한다(freshness 가드 없음) | CLAUDE.md 리스크 룰 표(매수 차단 + 청산 트리거는 RiskMonitor로 분리) · ADR 2.2 · **이번 diff가 만든 결함 아님**(기존) |
| MEDIUM | 잔고 조회 실패 시 중복 매도 접수 가능 | `TimeCutScheduler.java:231-243`, `KisOrderClientImpl.java:103-106` | 타임아웃 주문이 FAILED로만 남아 `hasPendingSell`이 못 잡고, 조회 실패는 재매도로 간다. 15:20·24·28 최대 3회. 브로커에 살아 있는 미체결 주문도 잔고(보유수량)로는 안 보인다. 방어는 브로커의 매도가능수량 거부뿐이며 코드·테스트에 근거 없음. 09-01 15:21~15:22 잔고 연속 타임아웃 + 15:23 브로커 보유 0 → 재현 조건 성립 | 지시된 설계 방향(불확실하면 재매도)과 일치 — **위반이 아니라 잔여 위험 기록**. 완화안: 미체결(정정취소가능주문) 조회 병행 또는 재매도 상한 1회 |
| MEDIUM | 최종 알림이 DB만 보고 브로커 진실을 버린다 | `TimeCutScheduler.java:245-259` (`:233`에서 이미 읽은 실수량 미사용) | 모의 체결조회가 비어 오는 결함 때문에 DB는 10~20분 늦다(`FillProcessor.java:38` 10분 타임아웃 → 15:26 정렬, `ShadowPortfolioReconciler.java:76` 10분 주기 · 2주기 필요). 이미 팔린 종목에 "밤새 들고 갑니다" 알림이 나가 사용자는 팔 수 없는 주식을 확인하러 간다. 09-01 재생 시 066570이 그 대상 | CLAUDE.md 미구현/결함 5번 · 09-01 로그 15:23:27 vs 15:26:34 |
| MEDIUM | 최종 알림이 15:30을 넘기면 조용히 사라진다 | `TelegramNotifier.java:51-55` + `MarketCalendarService.java:66-71` | 장중(09:00~15:30)에만 전송한다. 15:28 스윕이 대상 다수·타임아웃을 만나면 종료가 15:30을 넘긴다 — 09-01 실측 종목당 17~18초(15:15:22~15:16:45에 5종목), 스윕은 종목당 잔고 조회(최대 10초)까지 더한다. **가장 나쁜 날에만 알림이 사라지는** 구조 | 로그(`:253`)는 남으므로 손실 경로는 아님. 완화안: 최종 스윕을 15:26으로 당기거나, 재매도 전에 먼저 알리거나, 이 알림만 장중 게이트 예외 |
| MEDIUM | 단일 스케줄 스레드 점유 확대 → 감시 굶김 | `TimeCutScheduler.java:200-207` + `KisApiClient.java:50`(read 10초) + `KisRateLimiter.java:39-53`(호출자 스레드 블로킹) | 종목당 잔고 1회 + 매도 1회가 추가돼 15:20~15:30에 최대 3회의 긴 블로킹이 생긴다. 같은 스레드의 `RiskMonitor`(1초)·`StopLossMonitor`(1초)·`FillPoller`(3초)가 그만큼 밀린다 — 변동성이 큰 마지막 10분에 손절 감시가 분 단위로 지연될 수 있다 | 09-01 로그: 타임컷 5종목만으로 82초 점유, 전 스케줄러 `[scheduling-1]`. 기존 타임컷과 같은 성질이나 **발동 횟수 1회→4회**. 완화안: 스윕 대상 상한 또는 잔고 1회 스냅샷 재사용 |
| MEDIUM | 청산 양보 판정이 mode만 본다 | `TimeCutScheduler.java:134-138` vs `RiskMonitor.java:81` · `ShadowPortfolioReconciler.java:195` | 프로젝트 관례는 `liquidationService.isAnyLiquidationInProgress()`(phase 직접 확인)인데 스윕은 `TradingMode`만 본다. `LiquidationService.java:43`이 phase를 먼저 세우고 mode 전환은 **별도 스레드**(`:58`)에서 일어나므로, 그 사이에 스윕이 통과해 청산과 같은 종목에 매도를 낼 수 있다 | ADR-001 2.3 "포지션 소유권은 청산 상태머신". 기존 타임컷과 동일 성질이나 창이 1개→4개. **판단 보류 병기**: 창이 밀리초 수준이고 단일 스케줄 스레드라 실현 확률은 낮다 |
| LOW | 재매도 수량이 DB 기준 | `OrderEngine.java:102-110` (`TimeCutScheduler.java:233`에서 실수량을 읽고도 버림) | 부분 체결로 브로커 2주 / DB 5주면 5주 주문이 통째로 거부돼 남은 2주를 못 판다 | 모의에선 부분 체결이 관측 불가라 현재 노출은 작음 |
| LOW | `hasPendingSell`가 CANCEL_REQUESTED를 안 본다 | `TimeCutScheduler.java:261-266` | `FillProcessor.java:38,270`의 10분 타임아웃이 15:25경 15:15 주문을 CANCEL_REQUESTED로 바꾸므로 15:28 스윕에서 이 가드가 열린다 | 앞단 브로커 조회가 받쳐 잔여 위험은 "거부되는 주문 1건". 기존 `cancelAllPendingOrders`와 같은 상태 집합이라 일관성은 있음 |
| LOW | 스케줄러 풀 크기 1에 암묵 의존 | `TimeCutScheduler.java:178,183` (재진입 가드 없음) | `spring.task.scheduling.pool.size`를 올리면 `retrySweep`(15:24)와 `finalRetrySweep`(15:28)이 겹쳐 같은 종목에 동시 매도가 가능해진다 | 현재 미설정=1이라 안전. 값이 바뀌면 M-1이 커진다 |
| LOW | 회귀 테스트 전제가 실사고와 다름 | `TimeCutRetrySweepTest.java:146-160` (`:149` 브로커 5주 가정) | 실제 09-01은 브로커 보유 **0주**였다(로그 15:23:27) — 주문은 도달·체결됐고 문제는 DB 미반영이었다. 테스트가 증명하는 것은 "미접수였다면 재매도한다"이지 사고 재현이 아니다. 구현자 노트의 "사고 재현" 문구도 같은 오해 | 테스트 자체는 유효(다른 시나리오). 표기만 사실과 어긋남 |
| LOW | 스윕이 사실상 "늦은 타임컷"으로도 동작 | `TimeCutScheduler.java:188-197` | 앱이 15:15에 꺼져 있다 15:22에 켜진 날, 15:24 스윕이 전 보유분을 판다. 안전 방향이지만 "재시도" 범위를 넘는 동작 변화이고 문서에 없다 | 알려진 결함 3번(앱 꺼짐)의 부분 완화 — 의도된 것인지 확인 필요 |

참고(표에 넣지 않음): ① `KisBalanceClient`는 연속조회(CTX_AREA_NK100)를 따라가지 않는다 —
보유 상한 5종목이라 도달 불가하나, 이제 그 목록이 "재매도 안 함" 판단의 근거가 됐다는 점만 기록.
② 조기개장일(`market-calendar.yml` 2026-11-19, 마감 16:30)엔 스윕이 마감 1시간 전에 도는데,
매도 전용이라 무해하고 타임컷 15:15 고정과 일관된다.

## 판단 보류

- **M-5(청산 양보)**: 위반 근거 = ADR-001의 소유권은 phase이고 프로젝트의 다른 두 곳은 phase를
  직접 본다. 의도된 설계 근거 = 기존 타임컷도 mode만 보며 그 판정으로 이미 감사를 통과했고,
  단일 스케줄 스레드라 실현 창이 밀리초다. **어느 쪽인지 리더 판단 필요.**
- **L-5(늦은 타임컷 동작)**: 스코프 확장인지 의도된 부수효과인지 지시서에 근거가 없다.

## 감사자가 확인하지 **않은** 것 (통과로 적지 않음)

1. **전체 616건 재실행 안 함** — 3개 클래스 30건만 직접 돌렸다(9+13+8, 실패 0).
2. **스프링 실기동 주입 미확인** — 생성자 2개 추가(`BrokerageApiClient`, `NotificationService`)가
   paper 컨텍스트에서 실제로 조립되는지 실행 검증하지 못했다. 두 빈 모두 paper에 존재하고
   구현체가 각각 1개뿐(`KisBrokerageApiClient`, `TelegramNotifier`)이라는 정적 확인만 했다.
   다음 paper 기동 로그에서 `TimeCutScheduler` 빈 생성 성공과
   `[RiskEngine] Loaded N risk rules:`(9개 + `PostTimeCutBuyRule`)를 확인할 것.
3. **실장중 15:20/24/28 스윕 로그 미관측** — 아직 배포 전(구현자 노트 5번).
4. **KIS의 초과 매도 거부 동작 미검증** — M-1의 최종 방어선인데 실측·문서 근거가 없다.
5. **백테스트 재실행·앵커 대조 미수행** — `DailyBarSimulator` 변경이 값 보존 리팩터링이라는
   정적 확인만 했다(상수값 10:00/15:00 동일).

## 실전(real) 전환 관련

이 감사는 모의(paper) 경로만 본다. 실전 전환은 `docs/TRADING-RULES-AUDIT.md`의 CRITICAL 해소 +
ADR-001 재논의 + **게이트 G2(사람 승인) 선행**이 반드시 먼저다. 특히 H-1은 실계좌에서
"손실 중에 출구가 잠기는" 형태로 나타나므로 실전 전 필수 해소 항목이다.
