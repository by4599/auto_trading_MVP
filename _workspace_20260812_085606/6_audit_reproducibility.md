# 6. 재현성 결함 3건 수정 — 안전장치 감사

> 감사: `risk-auditor`. 리더가 에이전트 보고를 옮겨 기록(환경 제약으로 에이전트가 파일 생성 불가).

## 결론: CRITICAL 0 · HIGH 0 — 진행 가능

## 항목별 판정 (전부 CLEAN)

| 항목 | 위치 | 근거 |
|---|---|---|
| VB/타 모드 결과 불변 | `BacktestOrchestrator.java:115-116` | `symbols = liquidityScreener.filter(targetSymbols(), ...)` 그대로. risk-lab/cost-lab은 이 symbols를 안 씀 |
| candidate 격리 | `prepareCandidateUniverse` | 54종목은 `backfillExtra()`로만 candle DB 적재. `targetSymbols()`(universe ∪ symbols 6종목)는 candidateSymbols 미포함 |
| 기존 필드 기본값 불변 | `BacktestDataProperties.java:20-27` | symbols 6종목 그대로. 테스트 `globalSymbols_remainSeparateFromCandidates` 회귀 고정 |
| defect#3 게이팅(writeBaseline 기본 false) | `BacktestDataProperties` + `application-backtest.yml` | 양쪽 false. 테스트 `writeBaseline_defaultsFalse` |
| 게이팅 전달 경로 | `runRiskLab → writeRiskLabReport(..., isWriteBaseline()) → writeComparison(baselineOnPass)` | false면 "기준선 미기록" 로그만. true일 때만 yml 기록 |
| cost-lab·exit-lab 기준선 차단 | `writeCostLabReport`/`writeExitLabReport` | 둘 다 `baselineOnPass=false` 하드코딩 |
| 프로필 격리 | `@Profile("backtest")` (ExitLab/BacktestCost), `@ConfigurationProperties(prefix="backtest")` | paper/real 누출 없음 |
| 거버넌스 기준선 파일 안전 | `docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml` | 780/1.959/0.0130/0.0987·period 2023-07-22~2026-07-21 그대로. 이번 diff 미변경 |
| 비밀키 | `application-backtest.yml` | `${KIS_*}`/`${DART_API_KEY:}` 환경변수 참조, 텔레그램 빈 문자열 |

## MEDIUM 이하 참고 (배포 차단 아님)

- **MEDIUM — 공유 리스크 빈 런타임 변형**: `runRiskLab`/`runCostLab`이 공유 `RiskLimitsProperties`
  (라이브 룰이 쓰는 빈)의 값을 스윕 중 변경. 단 **backtest JVM 전용**(오케스트레이터 `@Profile("backtest")`)
  이고 실행 후 `RiskLimits` 상수로 복원 → paper/real 누출 불가. exit-lab 선행 패턴, 재현성 변경의
  신규 리스크 아님. 예외 시 복원문 미도달 가능성만(단발 배치라 영향 미미).
- **LOW — 거버넌스 기준선 미커밋**: `docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml`이 git untracked(`??`).
  강등 판정 분모가 버전관리 밖 — 선행 이슈, 이번 변경과 무관하나 커밋 권장.

## 범위 밖(플래그)

이번 감사는 재현성 표면만 봤다. Exit/Risk/Cost Lab **기계류 자체**(선행 미커밋 변경 —
`ExitLabProperties`·`BacktestCostProperties`·`WalkForwardEngine` 다일 이월·전략 3종 등)는
별도 감사 이력이 없으면 커밋 전 별도 감사 필요.

## 검증
`gradle test --tests BacktestDataPropertiesTest --tests BacktestCostPropertiesTest` → BUILD SUCCESSFUL.
