# 5_impl_reproducibility — 백테스트 재현성 결함 3건 해소

BACKLOG.md [2026-07-23] "백테스트 재현성 결함 3건" 해소. `--backtest.mode=risk-lab`
한 줄로 §14.1 후보 검증이 항상 똑같이 재현되게 만드는 것이 목표.

## 바꾼 파일 (git diff 기준)

1. `src/main/java/com/trading/backtest/BacktestDataProperties.java`
   - `import java.time.LocalDate` 추가.
   - 필드 4개 신설 + getter/setter: `candidateSymbols`(54종목 고정 유니버스),
     `candidateFrom`(2023-07-22), `candidateTo`(2026-07-21), `writeBaseline`(기본 false).
   - 이유: 후보 표본·판정 창을 저장소에 못 박아 부동 날짜(defect #1)·표본 부재(defect #2) 제거.

2. `src/main/resources/application-backtest.yml`
   - `backtest:` 블록에 `candidate-symbols`(54개 나열), `candidate-from`, `candidate-to`,
     `write-baseline: false`를 POJO 기본값과 동일하게 명시 + 각 항목 주석.
   - 다른 `backtest.*` 키(years/initial-cash/symbols 등)는 손대지 않음.
   - 이유: 저장소만 보고도 후보 표본·기간을 알 수 있게 문서화.

3. `src/main/java/com/trading/backtest/BacktestOrchestrator.java`
   - risk-lab / cost-lab 분기: `symbols/from/to`(전역, 부동) 대신
     `prepareCandidateUniverse(...)`가 반환한 candidate 심볼과 `getCandidateFrom()`/`getCandidateTo()`를 넘김.
   - `prepareCandidateUniverse(String mode)` 헬퍼 신설: `backfillService.backfillExtra(candidateSymbols)`를
     **실행문으로 선행 호출**(idempotent, 적재 건수 로그) + "후보 고정 설정 사용(...개, 기간 X~Y) — 재현성 실행" 로그.
   - `runRiskLab` 끝의 `writeRiskLabReport(...)` 호출에 `properties.isWriteBaseline()` 인자 추가.
   - exit-lab/full/smoke/ma-breakout/scalping/events 분기는 **미변경**.

4. `src/main/java/com/trading/backtest/BacktestReportWriter.java`
   - `writeRiskLabReport(...)`에 `boolean writeBaseline` 파라미터 추가 → 내부 `writeComparison(...)`의
     `baselineOnPass` 자리에 하드코딩 `true` 대신 이 값을 전달(defect #3).
   - `writeComparison(...)`: 기준선 기록 시 `log.info("[Report] 기준선 대상 프로필: {}", ...)` 추가,
     미기록 시 `log.info("[Report] write-baseline=false — 기준선 미기록(대조/점검 실행)")` 추가.
   - `writeCostLabReport`/`writeExitLabReport`는 이미 `baselineOnPass=false` — 변경 없음(확인).

5. `src/test/java/com/trading/backtest/BacktestDataPropertiesTest.java`
   - `import java.time.LocalDate` + 테스트 4개 추가(순수 POJO): candidate 54종목·첫/끝 원소,
     candidate-from/to 고정 창, write-baseline 기본 false, 전역 symbols 6종목 불변 회귀.

## "다른 모드 무영향" 보장 근거

- candidate 설정(`candidateSymbols/From/To`, `writeBaseline`)은 **risk-lab·cost-lab 두 분기에서만** 읽힌다.
  exit-lab/full/smoke/ma-breakout/scalping/events는 기존 전역 `symbols`(6종목 기본, liquidityScreener 통과분)
  과 `from = now(clock).minusYears(years)` / `to = backfillService.rangeTo()`를 그대로 사용 — 코드 미변경.
- `targetSymbols()`는 건드리지 않았다. 후보 54종목은 `backfillExtra()`로만 별도 적재 → VB 유니버스 미오염.
- `application-backtest.yml`의 candidate-from/to는 **전역 from/to 키가 아니다** — Orchestrator가
  전역 기간을 계산하는 코드는 `LocalDate.now(clock)` 그대로라 다른 모드 기간에 영향 없음.
- `writeRiskLabReport` 시그니처 변경의 유일한 호출부는 BacktestOrchestrator 하나(Grep 확인).
  `writeCostLabReport`/`writeExitLabReport`는 시그니처·`baselineOnPass=false` 불변.

## 재현 커맨드

이제 아래 한 줄로 §14.1(MA+P3+RR1) 후보 검증이 고정 표본·고정 기간으로 결정적으로 재현된다:

```
.\gradlew.bat bootRun --args="--spring.profiles.active=backtest --backtest.mode=risk-lab"
```

- 기준선 yml을 **새로 찍을 때만** `--backtest.write-baseline=true`를 추가한다(명시적 opt-in).
- 대조/점검 실행은 write-baseline 없이 → 기준선 미기록(로그로 확인 가능).

## 검증 증거

```
[검증 증거]
명령: gradle test --tests "com.trading.backtest.BacktestDataPropertiesTest" --console=plain
종료 코드: 0 (BUILD SUCCESSFUL, compileJava·compileTestJava 포함 4 tasks executed)
결과(XML): tests="7" skipped="0" failures="0" errors="0"
→ 판정: 통과
```

- 컴파일 성공(compileJava·compileTestJava)이 곧 `writeRiskLabReport` 시그니처 변경의 호출부 무결성 증거.
- **백테스트 자체는 실행하지 않음**(실행·판정은 strategy-quant 담당). 컴파일 + 단위 테스트까지만 수행.

## 미해결 / 우려

- `writeComparison`의 `else` 분기 로그("write-baseline=false — 기준선 미기록")는 exit-lab·cost-lab
  리포트 생성 시에도 찍힌다(이 두 랩은 설계상 항상 baselineOnPass=false). 리포트 파일 내용·숫자는
  불변이고 로그 한 줄만 추가되므로 무해하나, "대조/점검 실행"이라는 문구가 exit-lab에는 약간 부정확할 수 있음.
- `docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml`은 건드리지 않음(지시대로).
- defect #3의 findFirst→RR1 안정성은 기간 고정에 의존한다(RR0가 MDD 21%로 결정적 불합격).
  이는 §14.1 기록에 근거한 가정이며, 실제 결정성은 strategy-quant의 실행으로 확인 필요.
