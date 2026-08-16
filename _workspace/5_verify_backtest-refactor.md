# 5_verify_backtest-refactor — com.trading.backtest 리팩터링 독립 검증

검증일: 2026-08-14 · 담당: paper-ops-verifier
대상: `_workspace/4_impl_backtest-refactor.md` (구현자 자체 보고)
브랜치: `backtest/regime-filter-and-validation`
원칙: **이번 세션에서 직접 실행한 명령의 결과만 적는다.** 구현자 보고는 재인용하지 않았다.

## 판정 요약

| 주장 | 검증 명령 | 결과 | 판정 |
|---|---|---|---|
| 컴파일 에러 0건 | `gradle clean compileJava compileTestJava test --rerun-tasks` | 종료 코드 0, 컴파일 에러 0 | ✅ |
| 전체 테스트 515/515 | 같은 명령 + XML 집계 | 78클래스 515개 중 515개 통과, 실패 0·에러 0·스킵 0 | ✅ |
| backtest 패키지 113개 통과 | 같은 XML 집계(패키지 필터) | 16클래스 113개 중 113개 통과, 실패 0 | ✅ |
| 스프링 런타임 배선 성공 (구현자 미검증분) | `bootRun --args="--spring.profiles.active=backtest --backtest.mode=smoke"` | 종료 코드 0, 컨텍스트 3.726초 기동, Bean 예외 0건, 러너 정상 종료 | ✅ |
| 계산 결과 불변("동작 불변") | 리팩터 전/후 코드로 `--backtest.mode=risk-lab` 각각 실행 후 대조 | 5개 프로필 전 지표가 **원 단위까지 동일**, 리포트 파일 diff 0줄 | ✅ |
| order·risk·control·market 무수정 | `git diff --stat` + 파일 수정시각 | 이번 리팩터로 인한 변경 0줄 (control·order 변경은 08-12 다른 세션 산물) | ✅ |
| (부수 발견) §14.1 거버넌스 기준선 재현 | risk-lab RR1 vs `docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml` | 트레이드 780→**781**, 총수익 129.36%→**124.28%** | ⚠ 불일치 — **원인은 이번 리팩터가 아님**(아래 3-3) |

→ **이번 리팩터링에 대한 미검증·실패 항목 0건.** 단 ⚠ 1건은 별건으로 추적 필요.

---

## 1. 빌드 · 테스트 (독립 재실행)

### 1-0. 1차 시도는 증거가 아니었다 (기록)

```
[검증 증거 0 — 무효]
명령: gradle compileJava compileTestJava test --console=plain
종료 코드: 0
결과: > Task :test UP-TO-DATE  ← 테스트가 실제로 돌지 않았다
→ 판정: 증거 불성립. clean + --rerun-tasks로 강제 재실행함
```

### 1-1. 강제 전량 재실행

```
[검증 증거 1 — 컴파일·전체 테스트]
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build \
      gradle clean compileJava compileTestJava test --console=plain --rerun-tasks
종료 코드: 0
결과: BUILD SUCCESSFUL in 24s · 5 actionable tasks: 5 executed (UP-TO-DATE 없음)
      컴파일 에러 0건 (경고 2건: OrderLifecycleTest 폐기 API, TradingParamServiceTest unchecked — 기존)
      테스트 XML 집계: 78개 클래스 / 515개 중 515개 통과, 실패 0, 에러 0, 스킵 0
      그중 com.trading.backtest: 16개 클래스 / 113개 통과, 실패+에러 0
→ 판정: 통과
```

집계 근거: `C:/Users/SAMSUNG/auto_trading-build/test-results/test/TEST-*.xml` 78개를
파싱해 `tests/failures/errors/skipped` 속성 합산 (파일명 접두사 `TEST-com.trading.backtest.`로 패키지 분리).
XML의 `name` 속성은 `@DisplayName`(한글)이라 패키지 판별에 쓸 수 없다 — 파일명 기준으로 셌다.

backtest 테스트 클래스 16개(소스 16개 = 컴파일 16개 = 실행 16개, 누락 0):
BacktestCostProperties / BacktestData / BacktestIndexRegimeSource / BacktestMetrics /
CandleBackfillService / DailyBarSimulator / EntryTriggerBacktester / EventStatsBacktester /
LiquidityScreener / LowVolCrashBacktester / **MarketCapGroupStats(리네임)** / MinuteCandleImporter /
**RegimeLabProfiles(신설)** / SpilloverStatsBacktester / TradeRecorder / WalkForwardEngine.

---

## 2. backtest 프로필 실제 기동 (구현자 보고 6-3 "런타임 배선 미검증" 해소)

### 2-0. 포트 충돌 사전 확인

```
[검증 증거 2-0]
명령: curl -s -m 5 http://localhost:8080/api/status  /  netstat -ano | grep :8080
결과: curl 종료 코드 7(연결 거부), netstat 출력 없음 → 8080 사용 프로세스 없음
      추가로 application-backtest.yml은 spring.main.web-application-type: none —
      백테스트는 애초에 포트를 열지 않는다
→ 판정: 포트 충돌 없음. paper 앱은 실행 중이 아니었다
```

### 2-1. 스모크 기동

```
[검증 증거 2-1]
명령: gradle bootRun --args="--spring.profiles.active=backtest --backtest.mode=smoke" --console=plain
종료 코드: 0 (BUILD SUCCESSFUL in 29s)
결과:
  - Started TradingApplication in 3.726 seconds (프로필 "backtest" 1개 활성)
  - DataSource: jdbc:h2:file:./backtest-db  ← 운영 trading-db 아님 (격리 확인)
  - [RiskEngine] Loaded 13 risk rules (BucketBudget·ConsecutiveLoss·DailyLoss·DisclosureCooldown·
    EntryTimeWindow·GlobalEquityStop·IndexRegime·IndexTrend·MarketClose·MaxPositionCount·
    OrderFailureCooldown·PendingOrder·PositionLimit)
  - CommandLineRunner 정상 진입: [Orchestrator] candle_history 백필 검사 시작 →
    KIS OAuth 토큰 발급 완료 → 종목별 일봉 9건씩 저장 → 스모크 재생 → 정상 종료
  - 스모크 결과: trades=89 win=29.2% PF=0.34 MDD=11.90% return=-11.90% final=8,809,826
  - 로그 298줄에서 BeanCreationException / NoUniqueBeanDefinitionException /
    NoSuchBeanDefinitionException / UnsatisfiedDependency / Exception / ERROR: **0건**
  - 새로 갈라진 클래스가 실제로 동작한 로그 확인: BacktestModeRunner, DailyBarExitSimulator
→ 판정: 통과 — 30여 개 신설 클래스의 스프링 배선이 런타임에서 성공
```

---

## 3. 계산 결과 대조 (선택 항목 — 수행함)

`--backtest.mode=risk-lab`은 고정 판정 창(2023-07-22~2026-07-21)·고정 후보 54종목을 써서
재현 가능하다. `--backtest.write-baseline=false`를 명시해 거버넌스 기준선 yml은 건드리지 않았다
(실행 전후 md5 `852a29da9fd5d40d8be9b5c2f2904ba0` 동일 — 확인함).

### 3-1. 결정성 확인 (현재 코드 2회)

```
[검증 증거 3-1]
명령: gradle bootRun --args="--spring.profiles.active=backtest --backtest.mode=risk-lab --backtest.write-baseline=false"  (2회)
종료 코드: 0 / 0  (각 5분 29초)
결과: 두 리포트(REPORT-RISKLAB-MA-P3-20260814-1907.md / -1913.md) diff = "실행 시각" 한 줄뿐
→ 판정: 현재 코드의 risk-lab은 결정적 (DB 상태 차이에도 동일)
```

### 3-2. 리팩터 전 코드와 직접 대조 (핵심 증거)

방법: `git worktree add --detach <HEAD>`로 **리팩터 이전 backtest 패키지**(BacktestOrchestrator 1349줄)를
별도 폴더에 꺼내고, 리팩터와 무관한 다른 세션의 미커밋 변경(position 6파일·control·order)만
현재 작업트리에서 덮어써 **차이를 backtest 패키지 하나로 좁혔다.** 백테스트 DB는 같은 시점 스냅샷을
복사해 두 실행의 시작 상태를 맞췄고, 빌드 출력도 분리했다(`auto_trading-build-preref`).

```
[검증 증거 3-2]
명령: (리팩터 전 코드) gradle bootRun --args="... --backtest.mode=risk-lab --backtest.write-baseline=false"
종료 코드: 0
결과 — 프로필 5개 전부 리팩터 후와 완전 동일 (트레이드·PF·payoff·기대값·MDD·보유일·수익률·최종자산):
  RR0 1.0R·동시5   : trades=666 PF=1.53 기대값 0.933% MDD=20.96% final=22,838,900  (전=후)
  RR1 0.5R·동시5   : trades=781 PF=1.96 기대값 1.296% MDD= 9.87% final=22,427,654  (전=후)
  RR2 0.5R·동시3   : trades=508 PF=1.92 기대값 1.156% MDD= 6.63% final=16,214,652  (전=후)
  RR3 0.25R·동시5  : trades=732 PF=1.83 기대값 1.107% MDD= 4.63% final=13,839,334  (전=후)
  RR4 0.5R·동시2   : trades=322 PF=1.53 기대값 0.933% MDD= 8.58% final=12,214,431  (전=후)
  리포트 파일 diff: "실행 시각" 줄 제외 **0줄**
→ 판정: 통과 — 이번 리팩터링은 계산 결과를 바꾸지 않았다 (원 단위 일치)
```

검증 후 정리: worktree 제거(`git worktree remove --force`), 임시 빌드 폴더 삭제, 작업트리 소스 무변경 확인.

### 3-3. ⚠ 별건 발견 — §14.1 거버넌스 기준선이 오늘 코드로 정확히 재현되지 않는다

| 지표 (RR1 하프 0.5R·동시5) | 기준선 yml / 2026-07-24 앵커 로그 | 오늘 실측 (리팩터 전·후 동일) |
|---|---|---|
| 트레이드 | 780 | **781** |
| PF | 1.959 | 1.96 |
| 기대값 | 1.298% | 1.296% |
| MDD | 9.87% | 9.87% |
| 총수익 / 최종자산 | 129.36% / 22,936,111 | **124.28% / 22,427,654** |

- RR0(첫 프로필)은 최종자산까지 **완전 일치**하고, RR1~RR4만 어긋난다.
- **이번 리팩터의 책임이 아님이 증명됐다** — 3-2에서 리팩터 전 코드도 오늘 똑같이 781/124.28%를 낸다.
- 따라서 원인은 2026-07-24 앵커 실행 이후 ~ 이번 리팩터 착수(08-14) 사이의 다른 변경
  (지수 추세 필터·돈치안/RSI 진입 경로·peakEquity 보정 등) 또는 백필된 데이터 변화 중 하나다.
  **이 검증의 범위 밖이라 원인 특정은 하지 않았다.**
- 영향: 거버넌스 강등 판정의 분모(`docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml`)와 회귀 앵커
  "780·1.96·9.9%"를 앞으로 그대로 쓰면 미세 불일치가 계속 난다 → **risk-auditor/quant 확인 필요.**

---

## 4. Phase B 예고 점검 (릴리즈 경로 무오염)

```
[검증 증거 4]
명령: git diff --stat -- src/main/java/com/trading/{order,risk,control,market}
결과: control/TradingController.java 71줄 · order/OrderEngine.java 47줄 변경 (risk·market 0)
명령: git diff --stat -- src/test/java/com/trading/{order,risk,control,market}
결과: TradingControllerTest 131줄 · OrderEngineTest 46줄 · RiskMonitorTest 5줄
```

이 변경들이 **이번 리팩터 산물인지** 수정시각으로 분리했다:

| 파일 | 수정시각 | 귀속 |
|---|---|---|
| TradingController.java / OrderEngine.java | 08-12 21:32 | `_workspace/1_impl_manual-buy-drill.md` 세션(문서 08-12 21:34) — 수동 매수 리허설 기능 |
| RiskMonitorTest.java / position 3파일 | 08-12 09:20~12:34 | peak-equity-fix 세션 |
| backtest 신설 파일(RiskLab·LabExecutor·BacktestOrchestrator 등) | **08-14 08:36~08:48** | 이번 리팩터 |

diff 내용도 확인: control/order 변경은 전부 `manual-buy-drill` 엔드포인트와 `executeManualBuy`
관련이며 backtest와 무관.

→ **판정: 통과 — 이번 리팩터링이 order·risk·control·market에 넣은 변경은 0줄.**
   (단, 다른 세션의 미커밋 변경이 이 패키지들에 남아 있다는 사실 자체는 별도로 인지할 것)

---

## 5. 이번에 실행하지 않은 것 (추측하지 않음)

- **운영 점검(모의투자 앱 상태·재가동 게이트·데드맨·텔레그램 도달)**: paper 앱이 떠 있지
  않아 **점검 불가**. `curl localhost:8080/api/status`·`/api/trading/run-streak` 모두 연결 거부(7).
- **릴리즈 갭 3종**: 강제청산 리허설·5거래일 연속 가동은 이번 작업 범위 밖이며 상태 변화 없음.
  (리허설은 돈이 움직이는 조작 — 검증관이 직접 실행하지 않는다)
- Red-Green 회귀 확인: 이번 작업은 버그 수정이 아니라 순수 이동이라 대상 없음. 대신 3-2의
  **리팩터 전/후 산출물 원 단위 대조**로 동등성을 확인했다.
- events / exit-lab / full 등 나머지 모드의 실행은 하지 않았다(스모크 + risk-lab 2모드만 기동).
