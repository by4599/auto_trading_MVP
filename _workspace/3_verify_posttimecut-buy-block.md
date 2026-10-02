# 3_verify — PostTimeCutBuyRule 증거 검증

검증자: 리더(오케스트레이터) 직접 실행 · 2026-09-01 21:07~21:12 KST
대상: `_workspace/1_impl_posttimecut-buy-block.md` 구현 + `2_audit_` MEDIUM 1건 해소분

## 감사 MEDIUM 1건 해소 (리더 직접 수정)

감사 지적: 백테스트 진입 시각 드리프트 가드가 리터럴(10:00·15:00)로만 되어 있어,
훗날 종가 진입 시각을 15:15 이후로 옮기면 **룰이 백테스트 진입을 전량 차단하는데
테스트는 그대로 통과**한다. 여유 15분.

이 결합은 신규 룰이 만든 것이므로 같은 변경 안에서 막았다(외과적 범위 내).

| 파일 | 변경 |
|---|---|
| `src/main/java/com/trading/backtest/DailyBarSimulator.java` | 인라인 `LocalTime.of(10,0)`/`(15,0)` → `public static final BREAKOUT_ENTRY_AT`/`CLOSE_ENTRY_AT` 상수 추출 (동작 동일) |
| `src/test/java/com/trading/risk/PostTimeCutBuyRuleTest.java` | 리터럴 대신 두 상수를 참조 + `isBefore(TIME_CUT_AT)` 순서 단언 추가 |

## [검증 증거]

### 1) 전체 테스트 (수정 반영, 캐시 무시)

```
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build gradle test --rerun-tasks --console=plain
종료 코드: 0
결과: 92클래스 607개 중 607개 통과 — skipped=0 failures=0 errors=0
      (XML 집계: build/test-results/test/*.xml)
→ 판정: 통과
```

### 2) Red-Green — 새 드리프트 가드가 실제로 드리프트를 잡는가

```
[RED] CLOSE_ENTRY_AT을 15:00 → 15:20으로 드리프트
명령: gradle test --tests "com.trading.risk.PostTimeCutBuyRuleTest" --console=plain
종료 코드: 1
결과: 8개 중 1개 실패
  - "백테스트 진입 시각은 영향받지 않는다 — 실제 상수를 대조해 드리프트까지 잡는다" FAILED
→ 가드가 버그를 잡는다

[복구] 백업과 diff 바이트 동일 확인

[GREEN] 전체 재실행 (--rerun-tasks)
종료 코드: 0 / 607개 중 607개 통과
```

구현자가 별도로 수행한 Red-Green(룰 무력화 → 2건 실패, `TIME_CUT_AT` 15:20 드리프트 →
3건 실패)은 `1_impl_` 노트 참고. 이 문서의 증거는 **리더가 이번에 직접 실행한 것만** 적는다.

## 변경 규모 (git 대조)

기존 코드 수정은 최소:
- `TimeCutScheduler.java` — 주석 1줄 (동작 무변경)
- `DailyBarSimulator.java` — 상수 추출 (동작 무변경)
- `RiskEngine`·기존 리스크 룰 8종 — **변경 0**

신규: `PostTimeCutBuyRule.java`, `PostTimeCutBuyRuleTest.java`
문서: `CLAUDE.md`(리스크 룰 표에 9번째 행), `SCOPE_GUARDIAN.md`(항목1 근거 갱신)

## 검증하지 **않은** 것 (통과로 적지 않음)

1. **스프링 실기동 주입 미확인.** 저장소에 `@SpringBootTest`가 없고 paper 기동은 실계좌
   API를 부르므로 컨텍스트 조립을 실행 검증하지 못했다. 다음 paper 기동 로그의
   `[RiskEngine] Loaded N risk rules:`(`RiskEngine.java:26`)에서 **13→14개 +
   `PostTimeCutBuyRule` 포함**을 반드시 확인할 것.
   (실측: 08-31 08:30:19 기동 로그 = `Loaded 13 risk rules: BucketBudgetRule, ConsecutiveLossRule,
   DailyLossRule, DisclosureCooldownRule, EntryTimeWindowRule, GlobalEquityStopRule, IndexRegimeRule,
   IndexTrendRule, MarketCloseRule, MaxPositionCountRule, OrderFailureCooldownRule, PendingOrderRule,
   PositionLimitRule` — CLAUDE.md 표의 8개는 핵심 룰만 센 것이고 필터 룰이 함께 주입된다)
2. **실장중 15:15~15:20 차단 로그 미관측.** 다음 거래일 15:15 이후 매수 신호가 났을 때
   거부 사유가 찍히는지 확인 필요(신호가 안 나면 관측 불가).
3. **risk-lab 재실행·앵커 대조 미수행.** 백테스트 무영향은 코드 경로 증명 + 상수 대조
   테스트이지 실행 증거가 아니다. 다음 risk-lab 실행 시 `## 회귀 앵커 대조` 절이
   `docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml`과 일치하는지 확인하면 실행 증거가 된다.
4. **미배포.** 실행 중인 앱(PID 9776, 2026-08-31 08:30:12 기동)은 옛 코드다.

## 배포 시 주의 — 연속 가동일과 충돌

`RunStreakRecorder.judgeStreak`은 "그날 개장(09:00) **이전부터** 켜져 있었어야 무중단
하루로 인정, 개장 후 기동이면 **0으로 리셋**"이다(`RunStreakRecorder.java:88-95`).
현재 4일/5일, 마지막 기록 09-01.

| 재시작 시점 | 결과 |
|---|---|
| 09-01 밤 (09-02 09:00 전) | 수정 반영 + 09-02에 5일 달성 — 둘 다 성립 |
| 09-02 장중 | 연속 기록 0으로 리셋 — 달성이 다음 주로 밀림 |

`run-paper.bat`은 `gradlew bootRun`이라 기동 시 소스를 새로 컴파일한다 — 사전 빌드 불필요.

## 남은 결함 (이번 범위 밖)

**타임컷 매도 실패 시 재시도 없음** — 09-01 15:16:34 066570 Read timeout 실측.
`TimeCutScheduler`의 종목별 catch가 로그만 남긴다. 별도 작업으로 처리할 것.
