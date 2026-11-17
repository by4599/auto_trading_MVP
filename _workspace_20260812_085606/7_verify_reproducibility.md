# 7. 백테스트 재현성 결함 3건 수정 — 독립 재검증

검증자: paper-ops-verifier | 일시: 2026-07-23 08:57~08:58 KST
대상: trading-implementer 완료 보고 ("BacktestDataPropertiesTest 7개 통과, 종료 코드 0, 컴파일 성공")

## 실행 명령 (이번 메시지에서 직접 실행)

전체 테스트:
```
TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build \
  /c/Users/SAMSUNG/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gvueggl8a5cioxuftxrik/gradle-9.2.0/bin/gradle \
  test --console=plain
```
신규 테스트 개별:
```
... gradle test --tests "com.trading.backtest.BacktestDataPropertiesTest" --console=plain -i
```

## 출력 발췌 (원문)

- 전체 test: `BUILD SUCCESSFUL in 20s`, 종료 코드 0
- 개별 test: `BUILD SUCCESSFUL in 7s`, 종료 코드 0
- 콘솔이 개수 요약을 안 찍어 `%TRADING_BUILD_DIR%/test-results/test/*.xml` 파싱 (XML 58개, 타임스탬프 이번 실행)
  - **총 tests=365, failures=0, errors=0, skipped=0**
- 직전 세션 361 → 이번 365 (+4). 감소 없음 — 정상 방향.

## 신규/변경 테스트 개별 개수 (XML 파싱)

| 테스트 클래스 | tests | fail | err |
|---|---|---|---|
| BacktestDataPropertiesTest (신규) | 7 | 0 | 0 |
| BacktestCostPropertiesTest (신규) | 6 | 0 | 0 |
| BacktestMetricsTest | 8 | 0 | 0 |
| DailyBarSimulatorTest | 10 | 0 | 0 |
| MovingAverageBreakoutStrategyTest | 6 | 0 | 0 |
| VolatilityBreakoutStrategyTest | 2 | 0 | 0 |

BacktestDataPropertiesTest 7건 개별 실행 전부 PASSED (§14.1 표본 54종목, 이벤트 테마 평면화, 전역 symbols 회귀, kosdaq 부분집합, 고정 판정창 2023-07-22~2026-07-21, write-baseline 기본 false, event-themes 반도체 앵커2+체인19).

## 기준선 파일 무변경 확인

`docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml` (Read):
- trades: 780 ✅
- profit-factor: 1.959 ✅
- period: 2023-07-22 ~ 2026-07-21 ✅
- expectancy-pct 0.0130 / max-drawdown 0.0987 / win-rate 0.5179 그대로. 무변경.

## 보고 대조

| 주장 | 검증 명령 | 결과 | 판정 |
|---|---|---|---|
| BacktestDataPropertiesTest 7개 통과 | test --tests + XML | 7/7, fail 0 | ✅ 일치 |
| 종료 코드 0 | gradle test | exit 0 | ✅ 일치 |
| 컴파일 성공 | gradle test (compileJava/TestJava) | BUILD SUCCESSFUL | ✅ 일치 |
| 전체 회귀 무손실 | 전체 test XML | 365/365, fail 0 | ✅ (직전 361 → 365, 신규분만큼 증가) |
| 기준선 무변경 | Read yml | 780/1.959/기간 동일 | ✅ 불변 |

## 참고 (범위 밖 — 판정 무관)

- 실제 diff는 보고된 "5개"보다 많다 (소스 20 + 테스트 5 + 문서 등, git diff --stat 24개 tracked + 신규 untracked 6개). 이 검증 임무는 빌드·테스트 통과와 기준선 무변경만 판정 대상. 재현성 3결함의 코드-대-요구 대조는 risk-auditor/scope-guardian 몫.
- compileJava는 첫 전체 실행 시 UP-TO-DATE(implementer 세션에서 이미 컴파일됨)였으나 :test는 실행됨(XML 타임스탬프 이번 실행). 개별 --tests 재실행으로 신규 테스트가 실제 구동됨을 독립 확인.

## 최종 판정

**통과 — 365/365, 실패 0, 종료 코드 0. 기준선 파일 무변경. 보고 전 항목 일치.**
빌드·테스트 관점에서 완료. (백테스트 재현성 자체 재실행은 strategy-quant 담당, 범위 밖.)
