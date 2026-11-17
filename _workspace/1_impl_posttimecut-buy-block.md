# 1_impl — 15:15 타임컷 이후 신규 매수 차단 (PostTimeCutBuyRule)

## 한 줄 결론

15:15~15:20 사이 5분간 열려 있던 신규 매수 구멍을 새 리스크 룰 1개로 막았다.
전체 테스트 607개 통과(실패 0, 종료 코드 0), 회귀 테스트는 Red-Green으로 검증했다.

## 바꾼 파일

| 파일 | 종류 | 내용 |
|---|---|---|
| `src/main/java/com/trading/risk/PostTimeCutBuyRule.java` | 신규 (64줄) | `RiskRule` 구현체 + `@Component`. 15:15 이후 매수 신호 거부 |
| `src/test/java/com/trading/risk/PostTimeCutBuyRuleTest.java` | 신규 (133줄) | 8개 케이스 (경계·실사고 회귀·면제 3종·백테스트 결정성·크론 대조) |
| `src/main/java/com/trading/scheduler/TimeCutScheduler.java` | 주석 1줄 추가 | cron 위에 `TIME_CUT_AT`과 짝이라는 사실만 명시. 동작 무변경 |

`RiskEngine`·`MarketCloseRule`·기존 룰 파일은 **한 글자도 건드리지 않았다**
(CLAUDE.md 아키텍처 규칙 3 — `@Component`만 붙이면 스프링이 자동 주입).

## 설계 판단 (지시받은 대로 유지)

1. **`MarketCloseRule`을 고치지 않음** — 두 룰은 판단 근거가 다르다.
   그쪽은 "마감이 임박했다"(마감 -10분), 이쪽은 "오늘의 출구가 이미 닫혔다"(타임컷).
2. **15:15 고정 상수** — 마감 시각에서 유도하지 않는다. `market-calendar.yml`의
   2026-11-19(수능일) 마감이 16:30이라 "마감 - N분"으로 유도하면 그날만 16:15까지
   매수가 열려 **한 시간짜리 더 큰 구멍**이 생긴다. cron이 15:15 고정이므로 룰도 고정.
3. **다일 보유 칸(`multiDayHold`) 면제** — `TimeCutScheduler`가 그 칸을 타임컷에서
   제외하므로 15:15 이후 매수도 회수 불능이 아니다. 그 칸에도 `MarketCloseRule`의
   15:20 컷은 그대로 걸린다.
4. **휴장일 면제** — 그날은 타임컷 자체가 돌지 않아(`TimeCutScheduler` 휴장일 가드) 막을 근거가 없다.
5. **기본 ON, 플래그 없음** — 원래 의도된 불변식(당일 청산)의 복구이지 새 기능이 아니다.

판정 순서: 매도 → 다일보유 칸 → 휴장일 → `!now.isBefore(15:15)` 거부 → 통과.
경계는 **정각 포함**(15:15:00 차단) — 그 순간 cron이 이미 발동한다.

## 백테스트 결정성

`DailyBarSimulator`는 진입 시각을 `checkEntry` 10:00(103행), `checkMeanReversionEntry`
15:00(132행)으로 놓는다. 둘 다 15:15 이전이므로 이 룰은 백테스트에서 **항상 통과**한다
(= B-3/§14/§15 결과 불변). 이 사실을 테스트로 못박았다(`backtest_entry_times_are_unaffected`).

## 실행한 명령과 결과

### 1) 새 테스트만 (GREEN)

```
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build gradle test \
      --tests "com.trading.risk.PostTimeCutBuyRuleTest" --console=plain
종료 코드: 0
결과: 8개 중 8개 통과, 실패 0  (test-results XML: tests="8" failures="0" errors="0")
→ 판정: 통과
```

### 2) RED-A — 룰을 무력화(`if (false)`)하고 재실행

```
종료 코드: 1
결과: 8개 중 2개 실패
  - 회귀 — 2026-08-31 15:19:14 005930 오버나잇 사고: 15:19 매수는 차단돼야 한다
      → AssertionFailedError (PostTimeCutBuyRuleTest.java:83) "Expecting value to be false but was true"
  - 15:15:00 정각 매수 → 차단 (경계 포함)
      → AssertionFailedError (PostTimeCutBuyRuleTest.java:74)
→ 판정: 테스트가 실제로 그 버그를 잡는다 (사고 재현 확인)
```

### 3) RED-B — `TIME_CUT_AT`을 15:20으로 드리프트시키고 재실행

```
종료 코드: 1
결과: 8개 중 3개 실패 (위 2건 + 크론 드리프트 가드)
  - 크론 드리프트 가드 — TimeCutScheduler cron과 TIME_CUT_AT이 어긋나면 실패한다
      → AssertionFailedError (PostTimeCutBuyRuleTest.java:131)
→ 판정: 크론과 상수가 어긋나면 빌드가 깨진다 (구멍 재발 차단 장치가 실제로 동작)
```

### 4) 수정 복구 후 전체 테스트 (GREEN)

```
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build gradle test --console=plain
종료 코드: 0
결과: 92개 테스트 클래스 / 607개 중 607개 통과, 실패 0, 에러 0, 스킵 0
→ 판정: 통과 (기존 테스트 회귀 없음)
```

복구는 백업본과 `diff`로 바이트 동일(`RESTORED-IDENTICAL`) 확인했다.

## 남긴 한계 (검증 안 된 것)

1. **스프링 실제 기동 시 주입은 실행으로 확인하지 않았다.** `@SpringBootTest`가
   저장소에 하나도 없고, paper 기동은 실계좌 API를 호출하므로 돌리지 않았다.
   의존 3개(`Clock`·`MarketCalendarService`·`BucketParameterResolver`)는 모두 기존
   빈이며 같은 조합을 `MarketCloseRule`·`MaxHoldScheduler`가 이미 쓰고 있다.
   → 다음 paper 기동 로그의 `[RiskEngine] Loaded N risk rules:` 줄에
   `PostTimeCutBuyRule`이 포함되는지 **운영 검증자가 확인할 것** (기존 8개 → 9개).
2. **타임컷 매도 실패 시 재시도 없음**(SCOPE_GUARDIAN 항목1 결함 ①, 09-01 15:16:34
   066570 Read timeout)은 이 작업 범위 밖이라 손대지 않았다 — 별도 작업.
3. 15:15 정각에 앱이 꺼져 있어 타임컷을 건너뛴 날에도 이 룰은 매수를 막는다.
   (보수적 방향이라 안전하지만, "타임컷이 실제로 돌았는지"는 보지 않는다는 뜻이다.)
