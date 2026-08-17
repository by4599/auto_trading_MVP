# 9_audit_anchor-sot-and-coverage — 앵커 정본화 + 커버리지 관문 안전장치 감사

감사일: 2026-08-17 · 담당: risk-auditor (정적 감사 — 코드 직독 + `git diff`/`git show`, 빌드·테스트 미실행)
대상: `_workspace/8_impl_anchor-sot-and-coverage.md`의 변경 전량 (미커밋)
브랜치: `backtest/regime-filter-and-validation` (HEAD `62f6976`)

> **기록 경위**: `risk-auditor` 에이전트가 감사를 완료하고 결과를 보고했으나 이 파일을
> 생성하지 않은 채 종료했다. 아래 내용은 **에이전트의 보고 원문을 리더가 옮겨 적은 것**이며,
> 리더가 판정을 추가·수정하지 않았다. (`10_verify` §7에 적힌 대로, 리더는 이 지적들을
> 실증 재현하지 않았다 — 정적 지적으로 접수해 BACKLOG에 등재했다.)

## 결론 한 줄

**CRITICAL 0 · HIGH 0** — 라이브 경로 무접촉 · 판정 독립성 유지 · 선견편향 유입 없음을 코드로 확인했다.
다만 **"커버리지 관문은 후보 랩 단일 관문"이라는 구현자 주장에는 사각지대가 있다**: 기준선을 쓰는
3개 경로 중 2개(`FullBacktestLab`·`SingleStrategyLab`)가 관문도 `write-baseline` opt-in도 지나지 않으며,
구현자가 §6.2에서 든 면제 근거("부동 창 모드는 어긋남이 구조적으로 없다")는 성립하지 않는다.

## 심각도별 표

| 심각도 | 항목 | 위치 | 내용 | 근거 |
|---|---|---|---|---|
| MEDIUM | 관문 우회 기준선 기록 경로 2곳 | `FullBacktestLab.java:138-141`, `SingleStrategyLab.java:112-114` | 두 곳 모두 `judgment.pass()`만 참이면 `properties.isWriteBaseline()` 확인 없이, 커버리지 검사 없이 `docs/BACKTEST-BASELINE(.yml/-MA/-SCALPING)`을 덮어쓴다. 관문은 `CandidateLabRouter.prepareCandidateUniverse`에만 걸려 있다 | `CandidateLabRouter.java:250` vs `BacktestModeRunner.java:76-80`(full/ma-breakout/scalping은 라우터를 안 지남). **pre-existing** |
| MEDIUM | 면제 근거 오류 — 부동 창 모드에도 §14.1과 같은 꼬리 결측이 성립 | `CandleBackfillService.java:222-229`, `BacktestModeRunner.java:68-69` | 부동 모드의 판정 창 끝 = `rangeTo()` = 실행일−1. `needsTailFill`은 `mustCoverThrough`가 이미 채워졌으면 슬랙 안에서 `false`를 반환하므로 **`storedTo`가 `to`보다 최대 7달력일(≈5거래일) 뒤처진 채 채점**될 수 있다. 그 상태로 위 2경로가 기준선을 쓴다 | 구현 보고 §6.2 반증. 7_quant §5와 동일한 결함 구조 |
| MEDIUM | 커버리지 관문이 **부분** 결측을 못 본다 | `CandleCoverageChecker.java:91-102` + `CandleBackfillService.java:101-113` | 프런티어를 전 종목 **max**로 잡으므로 54종목 중 1종목만 최신이면 `sufficient=true`. 그런데 `backfillExtra`는 종목별 예외를 `catch` 후 warn만 남기고 계속한다 → 백필이 30종목에서 실패해도 strict 실행이 통과해 오염된 기준선이 굳는다 | 구현 보고에 이 실패 모드 기재 없음(전 종목 동일 결측인 §14.1만 상정) |
| MEDIUM | 소스 트리 오염 — 커밋 전 제거 필요 | `src/main/java/com/trading/backtest/.claude/agent-memory/code-reviewer/*.md` (4개, untracked) | `.gitignore`에 `.claude` 항목이 없어 `git add -A`로 커밋하면 자바 패키지 안에 에이전트 메모리가 딸려 들어간다. **비밀키는 없음**(내용 확인). 어느 트랙이 만든 것인지 불명 — 판단 보류, 근거만 병기 | `git ls-files --others --exclude-standard -- src/`, `.gitignore` 미등재 |
| LOW | "한 번 채우면 추가 호출 없음" 주장 정정 필요 | `CandleBackfillService.java:212-214`(주석) | 거래정지·상장폐지로 `storedTo`가 영구히 `mustCoverThrough` 이전인 종목은 **매 실행마다** `[storedTo+1, to]`를 재요청(0건 저장, 무한 반복). 호출량은 종목당 1회/실행으로 유한하고 `KisRateLimiter` 경유라 위험 낮음 | `needsTailFill` 3항 조건 |
| LOW | 앵커 대조 절 적용 범위가 보고서 기술과 다름 | `BaselineDriftReporter.java:43`, `LabComparisonReportWriter.java:43,47,53,57,61` | `baselineSlug==null`이면 빈 문자열이고 슬러그를 넘기는 건 risk-lab 계열뿐 → **앵커 대조 절은 risk-lab 계열에서만** 나온다(커버리지 절은 전부에 붙는 게 맞다). 기능 결함 아님, 구현 보고 §3의 "랩 리포트 전부에 적용" 문구가 과대 | 코드 직독 |
| LOW | `lastReport` 단일 필드의 창 혼선 여지 | `CandleCoverageChecker.java:46` | 현재는 모든 모드가 실행당 창 하나라 무해. 한 실행에서 서로 다른 창 둘을 채점하는 모드가 생기면 리포트에 다른 창의 커버리지가 붙는다(문장에 `windowEnd`가 찍혀 분간 가능) | 구현 보고 §6.6이 인지한 설계 선택 |

## 문제 없음으로 판정한 것 (지시 항목별)

1. **프로필 격리 ✅** — 신규 빈 3개 전부 `@Profile("backtest")`(`BaselineStore:29-31`,
   `BaselineDriftReporter:23-25`, `CandleCoverageChecker:33-35`). 나머지 2개(`BaselineSnapshot`·
   `CandleCoverage`)는 `record`로 빈이 아니다. `git diff --stat -- src/` 독립 확인 결과 이번 트랙
   변경은 **전부 `com.trading.backtest` 안**이며 `order`/`risk`/`control`/`market`/`position`·
   `src/main/resources/`에 한 줄도 없다. 유일한 라이브 패키지 접점은 `CandleCoverageChecker`가
   `MarketCalendarService.isTradingDay()`를 **읽기 전용**으로 쓰는 것뿐. 지갑 칸·텔레그램 격리 무변.
2. **판정 창 독립성 ✅ (구현자 주장 사실로 확인)** — `requiredCoverageThrough()`의 소비처는
   `CandleBackfillService.missingRanges`(=저장) **한 곳뿐**이고(`:200`), 커버리지 검사에는 max가 아니라
   **그 모드가 실제로 채점하는 `to`** 가 전달된다(`CandidateLabRouter:250`). 재생 경로는
   `BacktestMarketDataService.loadSeries`가 `[from−260, to]`로 잘라 적재하므로(`:63-64`) 창 밖 캔들이
   시뮬에 들어갈 수 없다. → 한 창의 설정을 바꿔도 다른 창의 *판정 결과*가 달라지는 경로는 없다.
   세 창 setter 상호 무간섭도 테스트로 고정됨(`BacktestDataPropertiesTest:196-206`).
3. **선견편향 유입 없음 ✅** — ① `loadSeries` 상한 클립, ② 지수 시계열은 넓게 캐시하지만
   `belowTrend`가 `headMap(simDate, false)`로 당일 이후를 배제(`BacktestIndexRegimeSource:89`).
   백필 **상한**은 종전과 동일한 `rangeTo()`이고 이번에 바뀐 건 "언제 채우느냐"뿐이라 저장 범위가
   미래로 넓어지지도 않는다.
4. **대조의 무해성 ✅** — `judge`·지표 계산 경로 미변경. `appendCoverage`/`appendBaselineDrift`는
   append 전용이고(`LabComparisonReportWriter:73-74`), 앵커 **읽기(74행)가 덮어쓰기
   (`writeBaselineIfRequested`, 80행)보다 앞선다**. `writeBaseline` 출력은 `%.3f`/`%.4f` 포맷을
   `BaselineSnapshot:27-30` 상수로 옮긴 것뿐이라 문자열이 동일(삭제분 8줄 전수 확인).
5. **관문 분기 ✅** — `strict=true` 예외 중단 / 그 외 WARN+리포트 기재가 코드대로다
   (`CandleCoverageChecker:63-76`), 검사는 채점 **전**(scope 생성 시점)에 돈다.
6. **비밀키·외부 호출 ✅** — 신규 5개 파일 하드코딩 키 0건. KIS 호출 신설 없음(백필은 기존
   `CandleHistoryClient` 경유), `KisRateLimiter` 우회 경로 없음. `CandleCoverageChecker`는 DB만 읽는다.
7. **테스트 기대값 조작 없음 ✅** — `git diff --numstat`: `BacktestDataPropertiesTest` 27/**0**,
   `CandleBackfillServiceTest` 60/**0** — 삭제 0줄, 순수 추가. 신규 `CandleCoverageCheckerTest`는
   strict 중단·비strict 기록·휴장일 헛경보 방지·데이터 전무를 모두 단언하며 Java 25 목킹 제약
   (인터페이스만 목)도 준수.

## 판정

**커밋 가능 — 단, 커밋 전 필수 조치 1건.**

- **필수(커밋 전)**: `src/main/java/com/trading/backtest/.claude/`를 커밋 대상에서 제외
  (삭제 또는 `.gitignore` 등재). 파일 단위로 `git add` 하면 회피 가능.
- **조건(커밋 후 즉시)**: ① 구현 보고 §6.2의 "부동 창 모드는 어긋남이 구조적으로 없다" 문구 정정,
  ② MEDIUM 1·2·3을 `BACKLOG.md`에 등재. 셋 다 **이번 변경이 만든 결함이 아니라 남긴 사각지대**이므로
  이 변경 자체를 막을 사유는 아니다.
- 전부 `@Profile("backtest")` 안에서 끝나 모의·실계좌 경로에 미치는 영향 0.
  **실전 전환 판단과는 무관하며, 어떤 경우에도 게이트 G2(사람 승인) 선행이다.**

## 리더 후속 조치 기록 (감사 지적에 대한 처리)

| 지적 | 처리 |
|---|---|
| MEDIUM 4 (`.claude` 오염) | 커밋 시 **경로 명시 스테이징**으로 제외 확인(금지 항목 grep 0건). 파일 자체는 리더가 만든 것이 아니라 **삭제하지 않고 사용자 판단으로 남김** |
| MEDIUM 1·2·3 | `BACKLOG.md`에 3건 개별 등재 (2026-08-17) |
| 구현 보고 §6.2 문구 오류 | 보고서를 고쳐 쓰지 않고 `10_verify` §8에 **상충 사실을 병기**(오케스트레이터 규약: 산출물 상충은 출처 병기 후 사용자 판단) |
| LOW 2건 | 등재하지 않음 — 기능 결함 아님. 이 문서에 기록으로 남김 |
