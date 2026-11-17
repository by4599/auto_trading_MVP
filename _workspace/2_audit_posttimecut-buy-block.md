# 2_audit — PostTimeCutBuyRule (15:15 타임컷 이후 신규 매수 차단) 감사

감사자: `risk-auditor` (2026-09-01) · 코드 미수정, 위반 탐지만 수행

**판정: CRITICAL 0 · HIGH 0 · MEDIUM 1 · LOW 5 — 배포 차단 사유 없음**

이전 감사(`_workspace_20260901_203948/9_audit_anchor-sot-and-coverage.md`)는 CRITICAL 0 · HIGH 0 — 이월 미해소 없음.

## 감사자 독립 검증 (구현자 보고 미신뢰)

- `git status`로 실제 변경분 직접 확인. **구현자 노트는 "3개"라 했으나 워킹트리는 4개**(`SCOPE_GUARDIAN.md` 포함 — 리더가 갭 체크로 갱신한 것).
- 전체 테스트 **직접 재실행** (`gradle test --rerun-tasks`, exit 0): 92클래스 / **607건 전원 통과, 실패 0 · 에러 0 · 스킵 0**. 신규 `PostTimeCutBuyRuleTest` = `tests="8" failures="0" errors="0"`.
- 경계·cron 검산 직접 실행: `CronExpression.parse("0 15 15 * * MON-FRI").next(2026-08-31T00:00)` → `2026-08-31T15:15` → `toLocalTime()=15:15` = `TIME_CUT_AT`. `15:14:59 blocked=false / 15:15:00 blocked=true`.
- 실사고 전제 확인: `logs/paper-2026-08-31.0.log.gz:323-325` — `15:19:14 [Sizing] 005930 수량=4 (칸=MIX)` → `15:19:17 주문 접수 side=BUY qty=4 ordNo=0000038987 bucket=MIX`. 같은 로그 307~319행에 15:15:06~15:15:58 타임컷 SELL 5건. **구멍 실재 확인.**

## 필수 판정 7항목

### 1. 주문 흐름 우회 없음 — PASS
- `RiskEngine.java` 워킹트리 변경 **0**, 자동 주입 생성자(`RiskEngine.java:24-38`) 원형 유지 → 아키텍처 규칙 3 준수.
- 신규 룰은 `RiskRule` 구현 + `@Component`만 사용(`PostTimeCutBuyRule.java:32-33`).
- 신규 파일에 `OrderEngine`/`KisOrderClient`/`BrokerageApiClient` import **0건** — 주문 경로 신설 없음.
- 기존 8개 룰 파일·`strategy` 패키지 변경 0건.

### 2. 타임컷 자신의 매도를 막지 않는가 — PASS (경로 증명)

```
TimeCutScheduler.java:131  Signal.sell(stockCode, "TimeCut-1515")
Signal.java:31-33          new Signal(Type.SELL, ...)
Signal.java:35             isBuy() = (type == Type.BUY) → false
TimeCutScheduler.java:132  riskEngine.check(...) → RiskEngine.java:31 룰 순회
PostTimeCutBuyRule.java:52 if (!signal.isBuy()) return RiskResult.pass();   ← 첫 줄 조기 반환
```

SELL은 시계·칸·휴장일을 **보기도 전에** 통과한다. 회귀 테스트 존재(`PostTimeCutBuyRuleTest.java:88-92`).
같은 논리로 다른 매도 경로도 안전: `StopLossMonitor.java:139`, `MaxHoldScheduler.java:151`, `DailyBarExitSimulator.java:169`.

### 3. 강제청산 경로를 막지 않는가 — PASS
강제청산은 **`RiskEngine`을 아예 거치지 않는다**: `LiquidationService.java:61-67`(취소+실잔고 조회), `:165` `brokerageClient.sendMarketOrder(ticker,"SELL",remaining)`. `RiskMonitor.java` 전체에 `riskEngine` 필드·호출 0건(19행 주석뿐). 백테스트도 동일(`BacktestRunner.java:178-181`). ADR-001 단일 상태머신(`LiquidationService.java:25-26` `AtomicReference<LiquidationPhase>`)에 별도 플래그 추가 없음 → ADR-001 위반 없음.

### 4. 프로필 격리 / 백테스트 결정성 — PASS (회귀 앵커 안전)
전 코드베이스 `riskEngine.check` 호출부 전수 조사 결과 **백테스트 BUY 진입점은 정확히 2개**이고, 둘 다 호출 **직전에** 시계를 15:15 이전으로 세팅한다.

| 진입점 | 시계 | check | 판정 |
|---|---|---|---|
| `DailyBarSimulator.checkEntry` | `:103` `setTo(date, 10:00)` | `:115` | 10:00 < 15:15 통과 |
| `checkMeanReversionEntry` | `:132` `setTo(date, 15:00)` | `:140` | 15:00 < 15:15 통과 |

**시계 되돌림 순서 추적:** `BacktestRunner.java:139`·`:152`(15:15)·`:162`(15:30)로 민 뒤 하루가 끝나지만, 다음 거래일은 `BacktestRunner.java:101` `clock.setTo(date, 9:05)`으로 **먼저 되돌린 뒤** `:104` `simulateDay`를 부르고, 종목별로도 `:103`/`:132`가 dispatch·check 직전 재세팅한다. `clock.setTo` 호출부 전수(`BacktestRunner` 4 + `DailyBarExitSimulator` 7[전부 09:05~14:30] + `DailyBarSimulator` 2)가 전부이며 `SignalDispatcher`·전략·`setSimPrice`는 시계를 만지지 않는다 → `:103`↔`:115`, `:132`↔`:140` 사이 변동 없음. 진입 경로도 `BacktestRunner.run` 하나로 수렴(`simulateDay` 호출부 1곳; `WalkForwardEngine:94,107`·`FullBacktestLab:88`·`BacktestModeRunner:97` 모두 `runner.run()` 경유).

→ `docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml` 앵커 **깨지지 않는다**. `MutableClock.java:18,26-33` KST 고정.
휴장일 분기(`PostTimeCutBuyRule.java:55`)는 결과가 `pass()`라 어느 방향으로도 백테스트를 바꾸지 않는다. `trading.bucket.enabled`는 `application-paper.yml:53-54`에만 true.

### 5. 다일 보유 칸 면제 집합 일치 — PASS
`TimeCutScheduler.java:101`은 **Position**.bucket, `PostTimeCutBuyRule.java:53`은 **Signal**.bucket을 키로 같은 술어(`BucketParameterResolver.java:55-58`)를 쓴다. 어긋날 유일 경로("다른 라벨 붙은 기존 Position에 추가 매수")는 ① `PendingOrderRule.java:42-47`이 보유 중 매수를 차단, ② 전량 청산 시 Position 행 **삭제**(`FillStateUpdater.java:302-304`)라 낡은 라벨 잔존 불가. 보정 포지션(bucket=null)은 `orDefault`→VB로 양쪽 다 보수적. 게다가 `multi-day-hold`는 **어느 yml에도 없어** 현재 전 칸 false → 두 집합 자명하게 동일. "타임컷은 파는데 매수는 허용"/역방향 **없음**.

### 6. 경계·시간대 — PASS
KST 고정 `Clock` 생성자 주입(`PostTimeCutBuyRule.java:38,42-46`), `LocalTime.now(clock)`만 사용(`:57`) — 시스템 시계 직접 호출 0건, F-8 회귀 없음. 정각 포함(`:58`, 검산 확인). cron↔상수 드리프트 가드 실재(`PostTimeCutBuyRuleTest.java:124-132` 리플렉션 대조). 휴장일은 `pass()`지만 `TradingScheduler.java:81-86` 장중 게이트 + `MarketCloseRule`이 이중으로 덮어 안전 방향. **마감 시각 유도 대신 고정 상수를 쓴 판단이 옳다** — `market-calendar.yml` early-open-days에 `2026-11-19 close 16:30` 실재 확인(유도했으면 그날만 16:15까지 1시간 구멍).

### 7. 비밀키·민감정보 — PASS
신규 2파일에 appkey/secretkey/토큰/계좌번호 리터럴 0건. 거부 사유(`:59-60`)는 시각 2개만. 해당 사유는 `TradingScheduler.java:101-109`에서 로그로도 남지 않아 15분간 초당 로그 폭주 없음.

## 지적 사항

| 심각도 | 항목 | 위치 | 내용 | 조치 |
|---|---|---|---|---|
| MEDIUM | 백테스트 진입 시각 드리프트 가드 비대칭 | `PostTimeCutBuyRuleTest.java:116-119` | 10:00·15:00을 **리터럴로만** 검증하고 `DailyBarSimulator`의 실제 값을 참조하지 않는다. cron 쪽은 리플렉션 대조를 하는데 백테스트 쪽만 비어 있다. 훗날 종가 진입 시각을 15:15 이후로 옮기면 **룰이 백테스트 진입을 전량 차단하는데 테스트는 그대로 통과**한다. 여유가 15분뿐 | ✅ **해소 (리더, 2026-09-01)** — `DailyBarSimulator.BREAKOUT_ENTRY_AT`/`CLOSE_ENTRY_AT` 상수 추출 후 테스트가 두 상수를 `isBefore(TIME_CUT_AT)`로 직접 대조 |
| LOW | 리허설 수동 매수 차단 창 5분 확대(미문서화) | `DrillService.java:87-93` | `manualBuy()`도 `riskEngine.check`를 지나 이제 15:20이 아니라 **15:15부터** 막힌다. 안전 방향이나 문서에 없다 | 기본 deadline 14:00·enabled=false라 현재 무해 — 보고서에 기록 |
| LOW | 예약 리허설 deadline 상한 미검증 | `LiquidationDrillScheduler.java:91`, `DrillProperties.java:29,53` | `buy-if-flat` 선행 매수가 `drill.manualBuy()`를 타므로 `trading.drill.deadline`을 15:15 이후로 두면 조용히 실패. yml 주석만 있고 기동 시 검증 없음 | 기본값 무해 — 백로그 후보 |
| LOW | cron 가드가 요일 드리프트는 못 잡음 | `PostTimeCutBuyRuleTest.java:127-131` | `toLocalTime()`만 비교해 `MON-FRI`→`MON` 변경은 통과 | 룰이 요일을 인코딩하지 않아 실해 없음 |
| LOW | 두 매수 차단 룰의 정각 처리 반대 | `MarketCloseRule.java:34` `isAfter` vs `PostTimeCutBuyRule.java:58` `!isBefore` | 15:20:00 정각은 `MarketCloseRule` 통과, 15:15:00 정각은 신규 룰 차단 | 새 룰이 더 엄격 = 안전 방향, 결함 아님 |
| LOW | 감사 추적 단절 | `_workspace/` | 이전 감사물 16개가 `_workspace_20260901_203948/`로 이동 — 워킹트리에서 이전 지적 추적이 끊긴다(히스토리엔 잔존) | 스킬 Phase 0 절차대로 아카이브. 커밋 시 아카이브 폴더도 함께 add |

## 판단 보류 없음 (의도된 설계로 확인)
- 룰이 `@Profile` 없이 `real`에도 주입된다 → 매수만 막는 보수적 방향이라 안전. **실전 전환은 `docs/TRADING-RULES-AUDIT.md` CRITICAL 해소 + ADR-001 재논의 + 게이트 G2(사람) 선행이 먼저다.**
- 15:15에 앱이 꺼져 타임컷을 건너뛴 날에도 매수를 막는다("타임컷이 실제로 돌았는지"를 보지 않음) → 보수적 방향.

## 감사자가 확인하지 **않은** 것 (통과로 적지 않음)
1. **스프링 실기동 주입 미확인** — `@SpringBootTest`가 저장소에 없어 컨텍스트 조립을 실행 검증하지 못했다. 의존 3개가 기존 빈이라는 정적 확인만 했다. → 다음 paper 기동 로그 `[RiskEngine] Loaded N risk rules:`(`RiskEngine.java:26`)에서 **8→9개 + `PostTimeCutBuyRule` 포함**을 확인할 것.
2. **실장중 15:15~15:20 차단 로그 미관측**.
3. **risk-lab 재실행·앵커 대조 리포트 미수행** — §4는 코드 경로 증명이지 실행 증거가 아니다.
