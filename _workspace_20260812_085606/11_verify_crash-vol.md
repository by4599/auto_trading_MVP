# 11_verify_crash-vol — crash-vol 구현 독립 재검증

검증 일시: 2026-07-24 22:27~22:31 (KST) · 검증자: paper-ops-verifier
대상 주장: `trading-implementer` 9_impl_crash-vol.md — "신규 테스트 10개 + 전체 375개 통과, 종료 코드 0"

## 판정 요약

| 주장 | 검증 명령 | 결과 (이번 실행 원문) | 판정 |
|---|---|---|---|
| 신규 테스트 10개 통과 | `gradle test --tests com.trading.backtest.LowVolCrashBacktesterTest` | XML `tests="10" skipped="0" failures="0" errors="0"`, 종료 코드 0 | ✅ |
| 전체 375개 통과 | `gradle test --console=plain` | 59개 클래스 / 375개 / 실패 0 / 오류 0 / 스킵 0, 종료 코드 0 | ✅ |
| 변경 파일 4개 | `git status` + `find src -newermt 2026-07-24` | 정확히 4개 (아래) | ✅ |
| 표본 접기 테스트가 공허하지 않음 | Red-Green (운영코드 변조) | 변조 시 실패(exit 1), 원복 시 통과(exit 0) | ✅ |
| 기준선 yml 무변경 | Read + mtime | trades 780 / PF 1.959 / 2023-07-22~2026-07-21, mtime 07-23 01:20 | ✅ |

→ **미완료 0건 — 보고 내용과 실제가 일치한다.**

## 1. 변경 파일 대조

`find src -newermt "2026-07-24"` 결과 = 오늘 손댄 소스 정확히 4개, 보고 표와 일치:

```
src/main/java/com/trading/backtest/LowVolCrashBacktester.java      (?? 신규)
src/test/java/com/trading/backtest/LowVolCrashBacktesterTest.java  (?? 신규)
src/main/java/com/trading/backtest/BacktestOrchestrator.java       (M 기존 미커밋분에 가산)
src/main/java/com/trading/backtest/BacktestReportWriter.java       (M 기존 미커밋분에 가산)
```

`git diff --stat`의 Orchestrator +420 / ReportWriter +306은 세션 이전 미커밋분을 포함한 누적치라
이 작업분만 분리되지 않는다(보고서 16~18행의 해명과 일치). 모드 배선은 실물 확인:
`BacktestOrchestrator.java:184 "crash-vol".equalsIgnoreCase(...)` → `:591 runCrashVol(...)` →
`BacktestReportWriter.java:291 writeLowVolCrashReport(...)`.

기준선 yml(`docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml`)은 오늘 변경 목록에 없음.

## 2. 개별 테스트 실행 (LowVolCrashBacktesterTest)

```
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build \
      /c/Users/SAMSUNG/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gvueggl8a5cioxuftxrik/gradle-9.2.0/bin/gradle \
      test --tests "com.trading.backtest.LowVolCrashBacktesterTest" --console=plain
출력: > Task :test / BUILD SUCCESSFUL in 10s
종료 코드: 0
XML(TEST-com.trading.backtest.LowVolCrashBacktesterTest.xml, 22:27:18 생성):
  tests="10" skipped="0" failures="0" errors="0" time="0.181"
→ 판정: 통과 (10개 중 10개, 실패 0)
```

콘솔이 건수 요약을 찍지 않아 `%TRADING_BUILD_DIR%/test-results/test/*.xml`에서 원문 속성값을 읽었다.

## 3. 전체 회귀 실행

```
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build <cached gradle 9.2.0> test --console=plain
출력: > Task :test / BUILD SUCCESSFUL in 22s
종료 코드: 0
XML 집계(59개 파일 전부 22:28:03 생성 — 이번 실행분):
  classes=59 tests=375 failures=0 errors=0 skipped=0
→ 판정: 통과 (375개 중 375개, 실패 0)
```

직전 세션 365개 → 이번 375개 = +10 (신규 클래스 10개와 정확히 일치). 감소 없음.

## 4. Red-Green — 표본 접기 테스트가 진짜 접기를 검증하는가

검증 대상 테스트: `aggregate_foldsPerEvent_notPerStock`
("한 이벤트의 9종목 → 버킷 중앙값 1개로 접힘 (n=이벤트수 1, 종목수 9 아님)")

변조(운영코드 `LowVolCrashBacktester.aggregate`, 이벤트 중앙값 접기 → 종목별 그대로 집계):

```java
- lowMed.get(h).add(Quantiles.of(lowR).median());
- highMed.get(h).add(Quantiles.of(highR).median());
+ lowMed.get(h).addAll(lowR);
+ highMed.get(h).addAll(highR);
```

RED (변조 상태):
```
> Task :test FAILED
LowVolCrashBacktester ... > 한 이벤트의 9종목 → 버킷 중앙값 1개로 접힘 ... FAILED
    org.opentest4j.AssertionFailedError at LowVolCrashBacktesterTest.java:149
10 tests completed, 1 failed
BUILD FAILED · 종료 코드: 1
XML: tests=10 failures=1 / 메시지: expected: 1 but was: 3   (events() — 이벤트 수 대신 종목 수)
```

GREEN (원복 후 전체 재실행):
```
BUILD SUCCESSFUL in 25s · 종료 코드: 0
XML 집계: classes=59 tests=375 failures=0 errors=0 skipped=0 (전부 22:30:45 생성)
LowVolCrash: tests=10 failures=0 errors=0 skipped=0
```

→ 이 테스트는 접기 로직이 사라지면 실제로 실패한다. **공허하지 않다.**

### 원복 증명

```
변조 전 md5 : d27a52f00b4975a3aa7862dfef1d4d0c  LowVolCrashBacktester.java
원복 후 md5 : d27a52f00b4975a3aa7862dfef1d4d0c  LowVolCrashBacktester.java
diff -q (원본 백업 대비) : IDENTICAL_TO_BACKUP
git status : 검증 시작 시점과 동일 (LowVolCrashBacktester.java는 여전히 `??` 신규, 잔여 변조 없음)
```

## 5. 기준선 무변경 확인 (Read 원문)

```yaml
generated-at: 2026-07-22
period: 2023-07-22 ~ 2026-07-21
validation:
  trades: 780
  profit-factor: 1.959
  expectancy-pct: 0.0130
  max-drawdown: 0.0987
  win-rate: 0.5179
```
mtime 2026-07-23 01:20:07 — 오늘 작업으로 건드려지지 않았다.

## 6. 범위 밖 (하지 않은 것)

- 백테스트 실행(`--backtest.mode=crash-vol`) 미실행 — strategy-quant 담당. 따라서
  **crash-vol의 실제 측정 결과·이벤트 수·해석은 이 문서로 검증되지 않았다.**
  구현이 컴파일되고 단위 테스트를 통과한다는 것까지만 증명됨.
- 모의투자 앱 미기동 — 운영 점검(RUNNING/SAFE_MODE·재가동 게이트·데드맨 스위치·
  run-streak·청산 리허설)은 이번 임무 범위 밖이며 **점검하지 않음**(추정 통과 표시 없음).
