# 4_impl — 타임컷 매도 실패 재시도 스윕 (TimeCutScheduler)

## 한 줄 결론

15:15 매도가 실패해도 장 마감 전에 다시 파는 스윕(15:20·15:24·15:28)을 `TimeCutScheduler`에
추가했다. 전체 테스트 616개 통과(실패 0, 종료 코드 0), 회귀는 Red-Green 3변형으로 검증했다.

## 바꾼 파일

| 파일 | 종류 | 내용 |
|---|---|---|
| `src/main/java/com/trading/scheduler/TimeCutScheduler.java` | 수정 (147 → 267줄) | 생성자 +2(`BrokerageApiClient`, `NotificationService`), 스윕 스케줄 2개 + `executeRetrySweep`/`retrySell`/`brokerConfirmedFlat`/`alertIfStillHeld`, 가드·대상선정 private 헬퍼 추출 |
| `src/test/java/com/trading/scheduler/TimeCutRetrySweepTest.java` | 신규 (274줄) | 9개 케이스 (핵심 회귀 · 중복매도 방지 3 · 가드 3 · 최종 알림 2) |
| `src/test/java/com/trading/scheduler/TimeCutSchedulerTest.java` | 수정 (2줄) | 생성자 인자 2개 추가 (컴파일 유지). 기존 13개 테스트 내용·기대값 무변경 |

`RiskEngine`·리스크 룰·`LiquidationService`·`OrderEngine`·인터페이스 시그니처는 **무변경**.

## 설계 판단 (지시받은 대로 유지)

1. **인라인 재시도 없음** — 스케줄은 단일 스레드(`scheduling-1`)라 `Thread.sleep` 루프는
   `RiskMonitor`·`StopLossMonitor` 감시를 함께 멈춘다. 뒤따르는 별도 스케줄로 분리.
2. **재매도 전 브로커 실잔고 확인** — 타임아웃 ≠ 미접수. 09-01 15:23 대사에서 브로커 보유는 0이었다.
3. **불확실하면 재시도** — `getActualHoldingQuantity`는 `BalanceClient.fetchBalance()`를 그대로 타고
   실패 시 예외를 던진다. **신선하게 확인된 0일 때만** 건너뛰고, 예외는 삼킨 뒤 재매도로 보낸다.
4. **한 클래스 안에서 해결** — 별도 클래스로 빼면 가드·매도 경로가 복제된다. 대신 가드(`canRun`)와
   대상 선정(`heldPositions`/`timeCutTargets`)을 private 헬퍼로 뽑아 타임컷과 스윕이 **같은 코드**를 쓴다.
5. **청산 경로 미사용** — `Signal → RiskEngine → OrderEngine`(기존 `sellPosition` 재사용).
   `BrokerageApiClient`는 읽기(`getActualHoldingQuantity`)로만 쓴다.
   테스트가 `sendMarketOrder` 미호출을 검증한다.
6. `@Profile("paper")` — 백테스트 영향 원천 차단.

스윕 1종목 처리 순서: `hasPendingSell` → 브로커 실잔고 → `sellPosition`.
미체결이 있으면 **잔고 조회조차 하지 않는다**(불필요한 KIS 호출 절감 — 테스트로 고정).

## 실행한 명령과 결과

### 1) RED — 테스트 먼저 (구현 전)

```
명령: gradle test --tests "com.trading.scheduler.TimeCutRetrySweepTest" --console=plain
종료 코드: 1
결과: 컴파일 실패 10건 — cannot find symbol: executeRetrySweep(boolean) / 생성자 인자 11개
→ 판정: RED 확인
```

### 2) GREEN — 구현 후 대상 테스트

```
명령: gradle test --tests "com.trading.scheduler.TimeCutRetrySweepTest" \
                 --tests "com.trading.scheduler.TimeCutSchedulerTest" --console=plain
종료 코드: 0
결과: TimeCutRetrySweepTest  tests="9"  failures="0" errors="0"
      TimeCutSchedulerTest   tests="13" failures="0" errors="0"
→ 판정: 통과
```

### 3) 전체 테스트 (최종, 복구 후 재실행)

```
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build gradle test --console=plain
종료 코드: 0
결과: 93개 클래스 616개 테스트 전원 통과 (skipped 0, failures 0, errors 0)
      결과 XML 93개 전부 2026-09-07 01:01:50 — 캐시 재사용 아님
→ 판정: 통과  (기존 607 + 신규 9 = 616)
```

## Red-Green (스윕을 무력화했을 때)

| 변형 | 무력화 내용 | 깨진 테스트 |
|---|---|---|
| A | `executeRetrySweep` 첫 줄에서 즉시 return | 4건 실패 — 「2026-09-01 066570 사고 재현」, 「잔고 조회 실패 시 재매도」, 「최종 스윕 알림 1건」, 「최종 스윕 알림 없음(매도 검증)」 |
| B | 브로커 실잔고 확인 제거 (무조건 재매도) | 1건 실패 — 「브로커 보유가 0이면 재매도하지 않는다」 |
| C | 잔고 조회 예외를 '정리 완료'로 처리(`return true`) | 1건 실패 — 「브로커 잔고 조회가 실패하면 재매도한다」 |

세 변형 모두 복구 후 재실행에서 전원 통과(위 3번). 임시 패치 잔여 없음(`grep "RED 검증용"` 0건).

## 남긴 한계

1. **최종 스윕(15:28) 알림은 과알림 쪽으로 기운다.** "남았는지"를 재매도 시도 뒤 리포지토리를
   다시 읽어 판단하는데, DB 수량은 체결 반영(FillPoller 3초 → FillProcessor) 뒤에야 줄어든다.
   즉 15:28에 재매도를 **성공적으로 접수**해도 그 순간에는 수량이 남아 있어 알림이 나간다.
   알림 누락(무방비 오버나잇)보다 안전한 방향이라 그대로 뒀다. 알림 문구도 "이대로면"으로 적었다.
2. **스윕은 앱이 15:20~15:28에 떠 있어야 돈다** — 타임컷과 같은 제약(알려진 결함 3번)이며 이번 범위 밖.
3. **모의 환경 한정**: 매도 체결가 조회가 비어 오는 문제(알려진 결함 5번)는 그대로다.
   스윕은 체결가가 아니라 보유 수량만 보므로 영향받지 않는다.
4. 스윕 cron(15:20·24·28)과 `MarketCloseRule`/`PostTimeCutBuyRule` 시각 사이의 드리프트 가드는
   넣지 않았다(스윕은 매도 전용이라 매수 차단 시각과 짝이 아니다).
5. **아직 배포 전** — 실행 중인 앱은 옛 코드다. 재시작해야 적용된다.
