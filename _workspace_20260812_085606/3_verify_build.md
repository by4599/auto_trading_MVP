# 3. 빌드·테스트 독립 재검증 — cost-lab

> 작성: 리더(trading-orchestrator)가 `paper-ops-verifier`의 보고를 옮겨 기록.
> 검증 주체는 paper-ops-verifier이며, 아래 명령·출력은 그 에이전트가 직접 실행한 결과다.
> (에이전트가 파일 생성을 하지 않아 리더가 대신 기록 — 내용 개변 없음)

## 실행 환경

- `TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build` (미설정 시 한글 경로 폴백으로 전 테스트 사망)
- `./gradlew.bat` 대신 캐시 배포본 직접 호출:
  `/c/Users/SAMSUNG/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gvueggl8a5cioxuftxrik/gradle-9.2.0/bin/gradle`

## 실행한 명령과 출력

| # | 명령 | 출력 원문 | 종료 코드 |
|---|---|---|---|
| 1 | `gradle test --rerun --console=plain` | `BUILD SUCCESSFUL in 19s` | 0 |
| 2 | `gradle test --tests "com.trading.backtest.BacktestCostPropertiesTest" --rerun` | `BUILD SUCCESSFUL in 7s` | 0 |
| 3 | (RED) 위 테스트, 운영코드 변조 상태 | `6 tests completed, 2 failed` / `BUILD FAILED` | 미포착 |
| 4 | (GREEN) `gradle test --rerun --console=plain` | `BUILD SUCCESSFUL in 21s` | 0 |
| 5 | `gradle build --console=plain` (1차) | `Task :bootJar FAILED` | **1** |
| 6 | `gradle build --console=plain` (2차) | `BUILD SUCCESSFUL in 9s` | 0 |

콘솔이 요약을 찍지 않아 JUnit XML(`%TRADING_BUILD_DIR%/test-results/test/*.xml`) 58개를 파싱해 집계:

```
CLASS FILES: 58
TESTS: 361  FAILURES: 0  ERRORS: 0  SKIPPED: 0
newest result file: 2026-07-23 00:40:33 (실행 시각 기준 10초 전 — 이번 실행분 확인)
```

`BacktestCostPropertiesTest` 개별 확인 — **6개 실행, 실패 0**:
`defaultMatchesLegacyConstant` / `setRoundTripCost60bp` / `setRoundTripCost80bp` /
`rejectsTargetBelowStatutoryFloor` / `resetDefaultsRestoresBaseline` / `fillPriceOverloadUsesGivenSlippage`

## Red-Green — 테스트가 공허하지 않은가

구현자가 변조한 지점과 **다른 곳**을 독립적으로 변조 (`setRoundTripCost` 역산의 나눗셈 제거):

```java
double slippage = (target - fixed) / 2;   // 원본
double slippage = (target - fixed);       // 변조
```

- RED: `6 tests completed, 2 failed` — 실패 지점(`:36`, `:45`)이 변조 지점과 정확히 일치.
  하한 거부·리셋 케이스는 변조와 무관하므로 통과 → 테스트가 수식을 실제로 검증한다.
- 복구 후 MD5 동일 확인 (`b669ae1ffc2965efe32de4bce1aec620`) — 저장소에 변조 흔적 없음.
- GREEN: 361/361 재통과.

## 보고 대조 (구현자 보고 8개 파일 vs 실제 diff)

8개 파일 모두 실제 존재·변경 확인. 단 **서술 부정확 2건**:

1. `WalkForwardEngine`을 "로그 문자열만 변경"이라 했으나 실제로는 실행문
   `long windowStart = System.nanoTime();`이 추가됨. 동작 영향은 없으나 서술이 부정확.
2. `docs/BACKTEST-DESIGN.md`(+146줄)가 변경 파일 목록에 없음 — 세션 전부터 M 상태라
   이번 작업분인지 git만으로는 분리 불가.

`BacktestOrchestrator`/`BacktestReportWriter`/`DailyBarSimulatorTest`/`BACKTEST-DESIGN.md`는
exit-lab·risk-lab 작업 때부터 이미 M 상태 → diffstat 숫자는 누적치이며 내용 대조로 확인함.

## 남은 공백 (판정에 반영할 것)

- **신규 테스트 6개는 전부 `BacktestCostProperties`(61줄 홀더)만 검증한다.**
  `BacktestOrchestrator`의 `runCostLab` 계열과 `BacktestReportWriter.writeCostLabReport`에는
  전용 테스트가 0개다. 즉 "361개 통과"는 **비용 역산 산술**을 증명할 뿐,
  cost-lab 모드가 올바르게 도는지는 증명하지 않는다 → 실제 실행 리포트에서
  **C0 회귀 앵커(780건·PF 1.96·MDD 9.9%) 재현을 사람이 확인**해야 메워진다.
- `gradle build`가 2회 중 1회 `bootJar` 단계에서 실패했다(`resources/main/static/index.html`
  입력 스트림 열기 실패). 재실행 시 성공했고 java.exe 5개 상주 — 파일 잠금/실시간 검사
  간섭으로 보이는 **환경성 실패**로 판단. 코드 결함 근거는 없으나 "빌드 성공"이 항상
  재현되지는 않는다는 사실은 기록해 둔다.

## 판정

**빌드·테스트 관문 통과 — 테스트 361/361, 종료 코드 0.** 차단 사유 없음.
백테스트 실행·코드 수정·모의투자 앱 기동은 하지 않았다(지시 범위 준수).
