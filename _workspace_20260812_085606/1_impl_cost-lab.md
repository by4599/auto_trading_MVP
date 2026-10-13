# 1_impl_cost-lab — 왕복 거래비용 상향 민감도 모드 (BACKTEST-DESIGN §14.1)

## 바꾼 파일

| 파일 | 이유 |
|---|---|
| `src/main/java/com/trading/backtest/BacktestCostProperties.java` (신규) | 편도 슬리피지 하나만 가변으로 들고 있는 백테스트 전용 홀더. 목표 왕복비용 → 슬리피지 역산 담당 |
| `src/main/java/com/trading/backtest/BacktestCosts.java` | 슬리피지를 인자로 받는 `buyFillPrice`/`sellFillPrice` 오버로드 + `roundTripCost(double)` 추가. 기존 시그니처는 기본 슬리피지로 위임 |
| `src/main/java/com/trading/backtest/BacktestOrderClient.java` | 체결가 계산이 하드코딩 상수 대신 주입된 슬리피지를 쓰도록 생성자 주입 + 2개 호출부 교체 |
| `src/main/java/com/trading/backtest/BacktestOrchestrator.java` | `cost-lab` 모드 분기 + `runCostLab`/`runCostProfile`/`logCostLabBanner`/`logCostLabSummary` 신규. 기존 `runRiskLab`은 손대지 않음 |
| `src/main/java/com/trading/backtest/BacktestReportWriter.java` | `writeCostLabReport` 신규(기준선 yml 미기록) + `writeComparison`의 비용 문구를 파라미터화 |
| `src/main/java/com/trading/backtest/WalkForwardEngine.java` | 윈도우 완료 로그에 진행률(`3/10`)과 경과 초 추가 — **로그 문자열만** 변경 |
| `src/test/java/com/trading/backtest/BacktestCostPropertiesTest.java` (신규) | 역산·하한 거부·리셋·오버로드 6케이스 |
| `src/test/java/com/trading/backtest/DailyBarSimulatorTest.java` | `BacktestOrderClient` 생성자 인자 1개 추가에 따른 조립부 수정 (1줄) |

## 기존 모드 무영향 보장 근거

1. `BacktestCostProperties.slippageRate` 기본값 = `BacktestCosts.SLIPPAGE_RATE` (= 0.001).
   `buyFillPrice(raw, 0.001)`은 기존 `buyFillPrice(raw)`와 **같은 수식·같은 부동소수 연산**
   (`rawPrice * (1 + slippageRate)`)이라 비트 단위로 동일하다.
2. 값을 바꾸는 유일한 경로는 `runCostLab()` 안의 `costProperties.setRoundTripCost(...)`이며,
   루프 종료 직후 `costProperties.resetDefaults()`로 복원한다. 다른 모드는 setter를 호출하지 않는다.
3. `ROUND_TRIP_COST` 상수는 그대로 남았다 → `EventBacktestPipeline`의 CANDIDATE 문턱값 불변
   (events 모드 무영향).
4. `buyCashOut`/`sellCashIn`(수수료·제세)은 손대지 않았다.
5. `writeComparison`의 비용 문구는 파라미터가 됐지만 exit-lab/risk-lab은 기존 문자열과
   **글자 단위로 동일한** `DEFAULT_COST_LINE`을 넘긴다.
6. 실증: 기본값을 0.002로 일부러 틀었더니 `DailyBarSimulatorTest` 10개 중 5개가 깨졌고,
   0.001로 되돌리자 전부 통과 (아래 Red-Green 증거) — 기본 경로 동일성이 실제로 검증됨.

## 명세 대비 의도적 편차 1건

명세 2)는 "기존 4개 메서드(buyFillPrice/sellFillPrice/**buyCashOut/sellCashIn**)에 슬리피지
오버로드 추가"를 요구했으나, `buyCashOut`/`sellCashIn`은 슬리피지를 **전혀 쓰지 않는다**
(슬리피지는 이미 체결가에 반영됨, 이 둘은 수수료·제세만 곱한다). 인자를 받아 무시하는
오버로드는 "수수료도 비용 스윕에 따라 변한다"는 잘못된 신호를 주므로 만들지 않았다.
대신 `BacktestCosts`에 그 이유를 주석으로 남겼다. 따라서 `BacktestOrderClient`에서
오버로드로 교체된 호출부는 4개가 아니라 **2개**(buyFillPrice/sellFillPrice)다.

## 실행 커맨드

```cmd
.\gradlew.bat bootRun --args="--spring.profiles.active=backtest --backtest.mode=cost-lab"
```

Git Bash에서 `gradlew.bat`이 걸리면:

```bash
TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build \
  "/c/Users/SAMSUNG/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gvueggl8a5cioxuftxrik/gradle-9.2.0/bin/gradle" \
  bootRun --args="--spring.profiles.active=backtest --backtest.mode=cost-lab" --console=plain
```

산출물: `logs/backtest/REPORT-COSTLAB-MA-P3-RR1-{yyyyMMdd-HHmm}.md` (기준선 yml은 쓰지 않음)

## 검증 증거

```
[검증 증거 1 — 전체 테스트]
명령: gradle test --console=plain  (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build)
종료 코드: 0 (BUILD SUCCESSFUL in 21s)
결과: 361개 중 361개 통과, 실패 0, 에러 0, 스킵 0
→ 판정: 통과
```

```
[검증 증거 2 — 신규 테스트 클래스]
BacktestCostPropertiesTest: tests 6 / failures 0 / errors 0
  PASS 기본 왕복비용은 기존 상수 0.41%와 같다 (다른 모드 회귀 방지)
  PASS 목표 왕복 0.60% → 편도 슬리피지 0.195%로 역산
  PASS 목표 왕복 0.80% → 편도 슬리피지 0.295%로 역산
  PASS 법정 확정비용(수수료×2+제세=0.21%) 하한 미만 목표는 거부한다
  PASS resetDefaults()는 왕복 0.41%로 되돌린다 (모드 간 누출 방지)
  PASS buyFillPrice 오버로드는 지정 슬리피지를 쓰고, 기본 시그니처는 0.1%를 유지한다
```

```
[검증 증거 3 — Red-Green (주입 배선이 실제로 살아있는지)]
1) 기본 슬리피지를 0.001 → 0.002로 일부러 변조
   명령: gradle test --tests "com.trading.backtest.DailyBarSimulatorTest"
   종료 코드: 1 — 10 tests completed, 5 failed
     - 장중 돌파: 진입가 = 이분탐색 복원 돌파가 + 슬리피지 FAILED
     - 장중 손절 / 갭다운 / 이월 트레일 갭 관통 / 이월 트레일 장중 터치 FAILED
2) 0.001로 복구 → 전체 361개 전부 통과 (증거 1)
→ 주입된 슬리피지가 실제 체결가를 결정하며, 기본값 동일성이 회귀를 막고 있음이 증명됨
```

**백테스트는 실행하지 않았다** (지시대로 컴파일 + 단위 테스트까지만. 실행·판정은 strategy-quant).

## 미해결 / 우려

1. **C0 회귀 앵커는 사람이 확인해야 한다.** 코드로는 §14.1 RR1(트레이드 780·PF 1.96·MDD 9.9%)
   재현 여부를 자동 검사하지 않는다. 리포트 판독 지침에 "C0가 재현 안 되면 실행 무효"로 명시했다.
2. **cost-lab은 리스크 룰·paper 기본값에 손대지 않지만 런타임 중 전역 상태를 바꾼다**
   (`riskLimits`, `filters`, `exitLab`, `costProperties`). 기존 risk-lab과 동일한 구조적 한계이며,
   루프 종료 시 전부 복원한다. 단, 도중에 예외가 나면 복원 전에 종료된다 — 백테스트는
   실행 후 JVM을 죽이므로(`System.exit`) 실무상 누출은 없다. 개선하려면 try/finally가 맞다(범위 밖).
3. `SLIPPAGE_RATE`가 여전히 "실전 실측값으로 교체 예정" 상태다. cost-lab이 답하는 것은
   "얼마까지 버티나"이지 "실제 슬리피지가 얼마인가"가 아니다. 후자는 모의투자 체결 로그
   실측이 필요하다(범위 밖, 백로그 후보).
4. 총 실행 시간: 프로필 5개 × 윈도우 N개. risk-lab과 같은 규모이므로 수십 분 예상.

---

# 보완 1 — F-C1 후속: 리포트에 "실제 적용된 비용" 노출

## 배경 (risk-auditor 감사, HIGH 1건)

`setRoundTripCost`의 역산에서 `/2`가 빠져 슬리피지가 의도의 2배로 걸리는 버그가 있었고
감사 중 수정됐다. **위험했던 이유는 버그 자체가 아니라 침묵이다** — 리포트 표에는 여전히
"C0 기준 0.41%"라고 찍히므로 결과만 봐서는 아무도 어긋남을 알 수 없다.
라벨은 사람이 적은 *의도값*이고 실제 적용값은 코드가 정한다. 둘을 나란히 찍게 만들었다.

## 바꾼 파일 (보완분)

| 파일 | 이유 |
|---|---|
| `src/main/java/com/trading/backtest/BacktestReportWriter.java` | `AppliedCost(slippageRate, roundTripCost)` 레코드 신설 + `ExitLabRow`에 nullable 4번째 성분 추가(기존 3-인자 생성자는 null 위임) + 비교 표에 `편도 슬리피지`·`실제 왕복` 조건부 컬럼 + `appliedCostCells` 헬퍼 + cost-lab 판독 지침 3줄 추가 |
| `src/main/java/com/trading/backtest/BacktestOrchestrator.java` | `runCostProfile`이 세팅 **직후 홀더에서 다시 읽은** 값으로 `AppliedCost`를 만들어 행에 싣고, 시작 로그도 그 값을 찍는다 |

`BacktestCostProperties`/`BacktestCosts`/`BacktestOrderClient`/`WalkForwardEngine`/테스트는
이번 보완에서 손대지 않았다.

## 핵심 설계 — 표가 실제로 검증 능력을 갖는 이유

`AppliedCost`는 목표값 `cp.roundTrip()`을 옮겨 적지 않는다. `costProperties.setRoundTripCost(target)`
호출 **직후** `getSlippageRate()` / `roundTripCost()`를 다시 읽는다. 그리고 `roundTripCost()`는
저장된 `slippageRate` 필드로부터 `BacktestCosts.roundTripCost(slippageRate)`로 **재계산**된다
(목표값을 캐시하지 않는다). 따라서 역산 수식이 다시 깨지면:

- 라벨: `C0 기준 0.41%` (그대로)
- 실제 왕복 컬럼: `0.790%` (어긋난 값이 그대로 드러남)

즉 표가 스스로 모순을 보여준다. 목표값을 옮겨 적었다면 검증 능력이 0이 됐을 지점이다.

## 기존 리포트(exit-lab / risk-lab) 출력 불변 근거

컬럼은 `rows.stream().anyMatch(r -> r.appliedCost() != null)`일 때만 나온다. exit-lab/risk-lab은
3-인자 생성자를 쓰므로 전부 null → `showApplied=false` → 삽입 문자열이 전부 `""`.
문자열 연결/포맷 결과를 기계적으로 대조해 **헤더·구분선·데이터행 3종이 모두 글자 단위로
동일**함을 확인했다 (아래 검증 증거 3). 값이 있는 표에서 일부 행만 null인 경우는 `-`로 찍는다
(현재 cost-lab에서는 발생하지 않지만 방어).

## 테스트 — 신규 케이스 만들지 않음 (중복 회피)

요청된 성질("세팅 후 **다시 읽은** `roundTripCost()`가 목표와 1e-9 이내 일치")은 기존 2개
케이스가 이미 정확히 덮는다. 확인 결과:

- `setRoundTripCost60bp`: `setRoundTripCost(0.006)` → `assertThat(costs.roundTripCost()).isCloseTo(0.006, within(1e-9))`
  \+ `getSlippageRate()` = 0.00195
- `setRoundTripCost80bp`: `setRoundTripCost(0.008)` → `roundTripCost()` = 0.008 (1e-9), 슬리피지 0.00295

두 케이스가 검사하는 게터 2개가 곧 표에 찍히는 값 2개다 → 중복 케이스를 추가하지 않았다.

## 검증 증거 (보완분 — 이번에 새로 실행)

```
[검증 증거 1 — 전체 테스트 재실행]
명령: gradle test --console=plain  (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build)
종료 코드: 0 (BUILD SUCCESSFUL in 21s)
결과: 361개 중 361개 통과, 실패 0, 에러 0, 스킵 0
→ 판정: 통과
```

```
[검증 증거 2 — 컴파일]
compileJava / compileTestJava 에러 0 (BUILD SUCCESSFUL) —
ExitLabRow 4-성분 확장에도 exit-lab/risk-lab 기존 3-인자 호출부가 그대로 컴파일됨
```

```
[검증 증거 3 — 기존 리포트 출력 불변 (문자열 대조)]
showApplied=false 경로의 헤더/구분선/데이터행을 변경 전 리터럴과 대조:
  header identical: True
  separator identical: True
  row identical: True
  샘플 행: '| P3 | 120 | 1.96 | 2.10 | 1.300% | 9.9% | 4.2일 | OK |'
```

**백테스트는 실행하지 않았다** (지시대로. 실행·판정은 strategy-quant).

## 보고 정확성 정정 (지적 2건 반영)

1. `WalkForwardEngine`을 "로그 문자열만 변경"이라고 쓴 것은 **부정확했다**. 실제 diff는
   루프 첫 줄에 `long windowStart = System.nanoTime();` **실행문 1줄이 추가**되고 기존
   `log.info(...)` 호출의 인자·포맷이 바뀐 것이다. 동작(체결·집계·판정)에는 영향이 없지만
   서술은 diff와 1:1이어야 한다. 정정한다.
2. `docs/BACKTEST-DESIGN.md`는 **이번 작업에서 편집하지 않았다.** 세션 시작 시점의
   git status에 이미 `M docs/BACKTEST-DESIGN.md`로 잡혀 있던 선행 변경분이며 내 diff가 아니다.
   (§14.1 문서 갱신이 필요하다면 별도 작업으로 요청 필요 — 이번 범위에 넣지 않았다.)
