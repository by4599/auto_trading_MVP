# 6_audit — com.trading.backtest 리팩터링 "동작 불변" 감사

감사일: 2026-08-14 · 담당: risk-auditor · 대상: `_workspace/4_impl_backtest-refactor.md`의 변경 전량
브랜치: `backtest/regime-filter-and-validation` (미커밋 워킹트리)

## 결론

**CRITICAL 0건 · HIGH 0건.** 청산 판정·리포트 계산·판정 임계·모드 라우팅에서 로직 변경을 찾지 못했다.
MEDIUM 0건, LOW 2건(로거 이름 변경, 로그 패턴 문자열 파라미터화 — 둘 다 렌더링 결과는 동일).

> 리스크 관점 요약: 이번 변경은 `@Profile("backtest")` 안에서만 일어났고, 실계좌·모의계좌 주문 경로
> (`Strategy → Signal → RiskEngine → OrderEngine`), 8개 `RiskRule`, `LiquidationService` 상태머신,
> 텔레그램 격리에 닿지 않는다. 실전(`real`) 전환은 이 감사와 무관하게 **게이트 G2(사람) 선행**이다.

## 이전 감사 CRITICAL·HIGH 현황

`_workspace/` 잔존 감사 파일(`2_audit_peak-equity-fix.md`, `2_audit_manual-buy-drill.md`)은 이번 작업과
다른 트랙이며, 이번 범위에서 재발한 항목 없음. (과거 crash-vol/regime 감사 파일들은 워킹트리에서
삭제된 상태 — git 이력에 남아 있음)

## 1. `DailyBarSimulator.exitAt` 이동 (지시 1)

| 심각도 | 항목 | 위치 | 내용 | 근거 |
|---|---|---|---|---|
| — | 청산 4종 이동 | `DailyBarExitSimulator.java:74-161` | `checkCarriedStop`·`checkSameDayStop`·`checkTakeProfit`·`checkTrailingStop` 본문이 조건식·비교부호·체결가 선택·`clock.setTo` 시각(9:05/10:00/14:00/14:15/14:30)까지 원본과 동일 | 원본 `git show HEAD:...DailyBarSimulator.java` 대조 |
| — | 단일 청산 경로 | `DailyBarExitSimulator.java:165-178` | `exitAt`이 `setSimPrice → setExitContext → Signal.sell → riskEngine.check → orderEngine.execute` 순서 그대로. 거부 로그 문자열·`trailingStopTracker.clear` 조건 동일 | CLAUDE.md 규칙 2(흐름 고정) 유지 |
| — | 하루 시퀀스 순서 | `DailyBarSimulator.java:78-96` | `carriedAtOpen` 계산 → 이월손절 → (RSI 분기: 트레일 → 종가진입 `return`) → 진입 → 당일손절 → 익절 → 트레일. 원본과 호출 순서 일치 | 선견편향 차단 규약 유지 |
| — | 트레일 추출 | `DailyBarExitSimulator.java:127-161` | `checkCarriedTrailingStop`으로 뽑았으나 제어흐름 등가(각 청산 후 `return`, 미청산 시에만 `updateHigh`) | — |
| — | 보유 판정 | `HeldPositions.java:18-22` | `findByStockCode().filter(quantity>0).orElse(null)` — 원본 인라인과 동일 | — |

**문자열 리터럴 전수 대조**: 원본 `DailyBarSimulator` 13개 리터럴이 신규 3파일에 100% 존재(누락 0).
**숫자 리터럴 전수 대조**(주석 제외): 손실·증가 0개.

## 2. `BacktestRunner` 143·153행 (지시 2)

| 심각도 | 항목 | 위치 | 내용 |
|---|---|---|---|
| — | 호출 대상 교체만 | `BacktestRunner.java:146,156` | `simulator.exitAt(...)` → `exitSimulator.exitAt(...)`. 인자 3개(`pos.getStockCode(), close, EXIT_MAX_HOLD` / `EXIT_TIMECUT`) 순서·값 동일 |
| — | 배선 | `BacktestRunner.java:44,58,71` | 필드·생성자 인자 1개 추가뿐. `maxHoldDays` 비교식, `closeOf` null 이월 처리 등 나머지 로직 무변경 |

`git diff --stat`: `BacktestRunner.java | 7 +-` (추가 5 / 변경 2) — 지시 범위와 일치.

## 3. 다른 판정 로직 (지시 3)

| 심각도 | 항목 | 위치 | 내용 |
|---|---|---|---|
| — | §4 합격 판정 | `BacktestReportWriter.judge` | 원본과 **텍스트 완전 일치**(스크립트 diff 결과 0줄). 임계 상수 `MIN_TRADES/MIN_PF/MIN_EXPECTANCY/MAX_MDD/MIN_ADJACENT_PF` 무변경 |
| — | PF 서식 | `ReportFormat.pf` | `Double.isInfinite ? "inf" : "%.2f"` — 원본 `pfText`와 동일. `metricsRow`의 `%.1f%%`·`%.3f%%` 서식 그대로 |
| — | 비교 리포트 | `LabComparisonReportWriter` | 절 순서(헤더→프로필표→불합격사유→레짐일관성→판독지침→저장→기준선)와 표 서식(`%.3f%%`,`%.1f%%`,`%.1f일`) 원본과 동일. `appliedCostCells` 로직 동일 |
| — | 랩 문구 | `LabReportTemplates` | 5개 랩 제목·스윕설명·비용문구·판독지침 문자열 전부 원본과 동일(리터럴 233개 중 누락 0) |
| — | 급락 계산 | `CrashVolCalculations.java:35-185` | `logReturnStdev`(모표준편차, N분모)·`detectAnchorIndices`(쿨다운 비교 `i-lastAccepted>=cooldownDays`)·`terciles`(`edge=n/3`)·`forwardReturn`·`aggregate`(이벤트 중앙값 접기, 저·고·KOSPI 동시 커버리지 조건) 전부 원본과 동일. `continue`→`return`은 등가 |
| — | 인자 묶음 | `CrashVolScope.java:13-16` | 필드 순서가 원본 `aggregate` 인자 순서(closes, dates, universe, window, minCoverage, horizons)와 동일 — 뒤바뀜 없음 |
| — | 비용 역산 방어(F-C1) | `ExecutionKnobs.appliedCost()` + `CostLab.java:68-71` | "세팅 직후 홀더에서 다시 읽기"가 유지됨. 목표값(`cp.roundTrip()`) 복사 아님 |
| — | 랩 실행 3단계 | `LabExecutor.java:35-42` | `walkForward.run(symbols, from, to, List.of(0.5), null)` → ReportData(`Map.of()`, `List.of()`) → `judge` — 원본 10곳과 동일 |
| — | 프로필 값 | `ExitProfile.P3`=(1.0,false,20,true,0.01,0.03) / `ExitLab` P0~P4 / `RiskLab` RR0~RR4 / `RegimeLab` G0~G2·S0~S3 / `CostProfile` C0~C4 | 전부 원본 수치와 일치 |
| — | 모드 라우팅 | `BacktestModeRunner` + `CandidateLabRouter` | 21개 모드 문자열·창(candidate/stress/crash-vol)·`prepareCandidateUniverse` 호출 매핑 동일. 조건이 상호배타적 문자열 비교라 검사 순서 재배치는 결과 불변 |
| — | full 모드 | `FullBacktestLab.java:52-146` | K민감도 → `allFiltersOff` → 기준선 → 필터변형 6개(순서·파라미터 동일) → `allFiltersOff` → judge/report/baseline 분기 동일 |
| — | 전략 토글 | `StrategyToggles.java:42-104` | 돈치안·RSI를 **건드리는 조합과 안 건드리는 조합을 분리 보존**(`enableVbOnly` vs `enableVbOnlyResetAll` 등) — 합쳤다면 yml에서 켠 전략이 조용히 꺼졌을 자리다. 원본 호출과 1:1 |
| — | B-4 CANDIDATE 기준 | `EventCandidateCriteria.java:11,21` | `표본≥30 && D+5 p25 > ROUND_TRIP_COST` 동일. PROMOTED 승격 경로 신설 없음(게이트 G2 유지) |

숫자 리터럴 대조에서 `BacktestOrchestrator` 그룹의 감소분(0.5×11, P3 세트 등)은 전부 **중복 제거**
(`LabExecutor.FIXED_K`, `ExitProfile.P3`, `ExecutionKnobs.applyP3ExitWithHalfRisk`)로 추적되며,
값이 바뀐 곳은 없다. 리터럴 비교에서 "누락"으로 뜬 나머지 2건은 한글 주석 안 따옴표로 인한
정규식 오탐(`BacktestOrchestrator:719`, `EventBacktestPipeline:107`)으로 확인됨.

## 4. 패키지 이동 (지시 4)

- 신규 `.java` 32개 전부 `package com.trading.backtest;` — 패키지 이탈 0건 (전수 검사).
- 신규 테스트 `RegimeLabProfilesTest`도 동일 패키지.
- `com.trading.backtest` 밖에서 이 패키지를 import 하는 코드 변경 없음.
- 신규 `@Component` 전부 `@Profile("backtest")` 부착 — **백테스트 빈이 paper/real 컨텍스트로 새지 않는다**(전수 검사).
- 신규 파일 중 텔레그램·`trading.bucket`·paper/real 프로필 빈을 참조하는 것 없음 (프로필 격리 유지).

## 5. 범위 외 패키지 무결성 (지시 5)

| 패키지 | 상태 | 판단 |
|---|---|---|
| `com.trading.market` | 변경 0 | 이번 작업 무관 |
| `com.trading.risk` (main) | 변경 0 | 리스크 룰 8종·`RiskEngine`·`LiquidationService` 무수정 (CLAUDE.md 규칙 3·5 위반 없음) |
| `com.trading.order/OrderEngine.java` | 변경 있음(+47) | **이번 작업 산물 아님** — `_workspace/1_impl_manual-buy-drill.md`(2026-08-12) 수동 매수 리허설 트랙 |
| `com.trading.control/TradingController.java` | 변경 있음(+71) | 위와 동일 트랙 |
| `com.trading.position/*` + `RiskMonitorTest` | 변경 있음 | **이번 작업 산물 아님** — 세션 시작 시점 `git status` 스냅샷에 이미 존재(peak-equity-fix 트랙) |

근거: 위 diff 전량에 `backtest`·`DailyBar*`·`LabScope` 등 이번 리팩터링 심볼이 **한 건도 등장하지 않는다**(grep 0건).
→ 구현자의 "안 건드렸다" 보고는 사실로 확인.

## 6. 테스트 변경 검증 (기대값 조작 여부)

| 파일 | 판정 | 근거 |
|---|---|---|
| `RegimeLabProfilesTest`(신규) ↔ 삭제된 `BacktestOrchestratorRegimeProfilesTest` | 클래스명 치환 후 **diff 0줄** | 스크립트 대조 |
| `MarketCapGroupStatsTest`(git mv) | 호출 대상 이름만 변경, 단언 4건·기대값 동일 | diff |
| `LowVolCrashBacktesterTest` | 호출 대상(`CrashVolCalculations`/`ReportFormat`)·`aggregate` 인자 묶음만 변경. 기대값(0.05·0.20·-0.10·"+3.00%"·앵커 인덱스 30/55/95) 무변경 | diff |
| `DailyBarSimulatorTest` | 생성자 분리 배선 8줄만 변경, 시나리오·단언 무변경 | diff |

## 7. 실행 증거 (감사자가 이번에 직접 실행)

```
명령: gradle test --rerun-tasks --console=plain  (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build)
종료: BUILD SUCCESSFUL in 26s · 4 actionable tasks: 4 executed  (캐시 아님)
결과(test-results XML 78개 집계): tests 515 · failures 0 · errors 0 · skipped 0
그중 com.trading.backtest: 파일 16개 · tests 113 · 실패 0
```

## 8. LOW / 판단 보류

| 심각도 | 항목 | 위치 | 내용 |
|---|---|---|---|
| LOW | 로거 이름 변경 | `RegimeLab:27`, `CostLab:24` 등 | 로그 앞머리 클래스명이 `BacktestOrchestrator`→각 랩으로 갈린다. 메시지 본문·대괄호 태그는 동일해 태그 grep 관행은 유지. 리포트 파일 내용 영향 없음 |
| LOW | 로그 패턴 파라미터화 | `LabLog.java:19-27` | `"[CostLab] 프로필 \| ..."` → `"[{}] 프로필 \| ..."` + tag. **렌더링 결과 문자열은 동일**하나, SLF4J 패턴 문자열 자체로 로그를 검색하는 도구가 있다면 영향. 사람이 보는 출력은 불변 |
| 보류 | 스프링 런타임 배선 | 신규 빈 32개 | 정적으로 타입 유일·프로필 일치까지 확인했으나, 백테스트 컨텍스트를 실제로 띄우는 테스트는 원래 없다. **모드 1회 실행(예: `--backtest.mode=smoke`) 증거는 `paper-ops-verifier` 몫** — 그 전까지 "런타임 무결" 단정 불가 |

## 9. 배포/승격 판단

- 이 변경은 백테스트 전용이므로 모의투자 운영·릴리즈 체크리스트에 영향 없음.
- **실전(`real`) 전환과 무관** — 어떤 백테스트 결과도 이 리팩터링으로 재판정되지 않으며,
  실전 승격은 여전히 ADR-001 재논의 + **게이트 G2(사람) 선행**이다.
- CRITICAL·HIGH 0건이므로 이 감사 기준으로는 진행 가능. 단 §8의 "런타임 1회 실행" 확인 전에는
  "완료"가 아니라 "정적 감사 통과"로 표기할 것.
