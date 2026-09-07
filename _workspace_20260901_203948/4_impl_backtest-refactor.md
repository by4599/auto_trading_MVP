# 4_impl_backtest-refactor — com.trading.backtest 5개 파일 책임 분리 (동작 불변)

작업일: 2026-08-14 · 담당: trading-implementer
브랜치: `backtest/regime-filter-and-validation`

## [릴리즈 갭 체크]

- ⚠ 강제청산: 코드 완료, 모의계좌 리허설 깨끗한 1회 성공 미완 (오늘 라이브 리허설 진행 중)
- ☐ 모의투자 5거래일 연속 무중단: 기록 장치만 완료, 실적 미달
- ☑ 킬스위치 / 상태 불일치 복구 / 문서
→ **이번 작업은 릴리즈 체크리스트 항목이 아니다** (코딩 지침 준수 목적의 순수 리팩터링).
  릴리즈 경로(`order`·`risk`·`control`·`market`)는 지시대로 건드리지 않았다.

## 1. 원칙 (지킨 것)

- **동작 불변**: 로직 순서·계산식·조건문·로그 문자열을 그대로 옮겼다. 바꾼 것은 "어느 클래스에
  사는가"와 "인자를 어떻게 묶는가"뿐이다.
- 새 파일은 전부 같은 `com.trading.backtest` 패키지 (import 경로 파급 없음).
- `@Profile("backtest")` + `@Component` 생성자 주입 스타일 유지.
- 판정 기준(PF/MDD/기대값 임계, 워크포워드, 프로필 값)은 **한 글자도 안 건드렸다**.

## 2. 파일별 분리 결과

### 2-1. `BacktestOrchestrator.java` 1349줄 → 45줄

| 새 파일 | 줄수 | 책임 |
|---|---|---|
| `BacktestOrchestrator` | 45 | CommandLineRunner 껍데기 — 실행·예외·JVM 종료만 |
| `BacktestModeRunner` | 135 | 백필 → 유니버스 확정 → 모드 분기(데이터/전략 모드) → full 폴백 |
| `CandidateLabRouter` | 247 | 후보 유니버스(54종목) 랩 17개 모드 라우팅 + `prepareCandidateUniverse` |
| `StrategyToggles` | 120 | 진입 전략 5종 on/off 스위치 (원본 호출 조합과 1:1) |
| `ExecutionKnobs` | 120 | 필터·손절배수·사이징·출구·비용 세팅/복원 시퀀스 |
| `FullBacktestLab` | 146 | full 모드(K 민감도 → WF 기준선 → 필터 A/B → 리포트) |
| `SingleStrategyLab` | 121 | ma-breakout / scalping 모드 + 스캘핑 ±20% 민감도 |
| `ExitLab` | 53 | 출구 프로필 P0~P4 스윕 |
| `RiskLab` | 62 | 사이징·동시보유 RR0~RR4 스윕 |
| `CostLab` | 110 | MA+P3+RR1 왕복비용 상향 스윕 + 배너/요약 |
| `RegimeLab` | 222 | 지수 추세 필터 A/B(G0~G2) + MA기간 민감도(S0~S3) + 2022창 로그 |
| `DonchianLab` | 207 | 돈치안 파라미터/사이징/추세지도/비용 4개 랩 |
| `VbFilterLab` | 118 | B동 진입 필터 A/B + 비용 하향 스윕 |
| `CrashVolLab` | 62 | 급락 후 저변동성 측정 실행·로그 |
| `LabExecutor` | 48 | (반복 제거) WF 실행 + ReportData 조립 + §4 판정 3단계 — 10곳에서 중복이던 것 |
| `LabScope` / `ExitProfile` / `CostProfile` | 18/18/19 | 인자 묶음·프로필 값 |
| `ExitLabRow` / `AppliedCost` | 14/11 | 랩 결과 레코드 (`BacktestReportWriter`에서 최상위로 승격) |
| `LabLog` / `ReportFormat` | 29/51 | 요약표 로그 · 서식(PF/백분율/판정 문구) |

중복 제거 근거(2회 이상 반복): `walkForward→judge` 3단계(10회), 프로필 요약표 로그(3회),
`"✅ 합격"/"❌ 불합격"` 삼항(12회), `new ExitProfile("P3", 1.0, false, 20, true, 0.01, 0.03)`(4회),
`pct`/`elapsedSec`/`pfText`(각 다수).

### 2-2. `BacktestReportWriter.java` 551줄 → 212줄

| 새 파일 | 줄수 | 책임 |
|---|---|---|
| `BacktestReportWriter` | 212 | §4 판정(`judge`) + B-3 리포트 + 거버넌스 기준선 yml |
| `LabComparisonReportWriter` | 177 | 랩 비교 리포트 표 렌더링(Exit/Risk/Cost/Regime 공통) |
| `LabReportTemplates` | 114 | 랩별 고정 문구(제목·스윕 설명·판독 지침) |
| `ComparisonTemplate` | 12 | 위 문구 5덩어리 레코드 |
| `CrashVolReportWriter` | 114 | 약세장 방어 측정 리포트 |
| `ReportFormat` | 51 | 서식 공통 (signedPct·lowMinusHighText 포함) |

`writeReport`는 절 단위(민감도/워크포워드/변형/한계)로 쪼개 함수 50줄 이하로 맞췄다.
마크다운 출력 문자열은 이전과 동일하다.

### 2-3. `EventBacktestPipeline.java` 401줄 → 113줄

| 새 파일 | 줄수 | 책임 |
|---|---|---|
| `EventBacktestPipeline` | 113 | ①~④ 오케스트레이션만 |
| `DisclosureBackfiller` | 99 | DART 공시 소급 백필 + 공급계약 크기 보강 |
| `MarketCapGroupStats` | 86 | LARGE/MIDSMALL 태깅·재집계 |
| `EventRegistryUpdater` | 73 | 레지스트리 갱신(승격은 사람만 — 불변) |
| `EventReportWriter` + `EventReportData` | 152/15 | B-4 리포트 |
| `EventCandidateCriteria` | 23 | CANDIDATE 기준(표본≥30 & D+5 p25 > 왕복비용) 단일 정의 |

### 2-4. `LowVolCrashBacktester.java` 390줄 → 228줄

| 새 파일 | 줄수 | 책임 |
|---|---|---|
| `LowVolCrashBacktester` | 228 | 캔들 조회·배너·리포트 타입(records) |
| `CrashVolCalculations` | 202 | 순수 계산(변동성·앵커·버킷·전방수익률·표본 접기) |
| `CrashVolScope` | 16 | `aggregate` 7인자 → (앵커목록, scope) 2인자 |

`aggregate` 53줄 → `aggregate`(20줄) + `accumulateAnchor`(38줄)로 분리.

### 2-5. `DailyBarSimulator.java` 301줄 → 184줄

| 새 파일 | 줄수 | 책임 |
|---|---|---|
| `DailyBarSimulator` | 184 | 하루 시퀀스 순서 + 진입(이분탐색 돌파가·RSI 종가 진입) |
| `DailyBarExitSimulator` | 183 | 청산 판정 4종 + 단일 청산 경로 `exitAt` |
| `HeldPositions` | 23 | "보유 = 수량>0" 단일 정의 (진입부·청산부 공용) |

## 3. 범위 밖이지만 고칠 수밖에 없었던 것 (보고)

- **`BacktestRunner.java`** — `exitAt`이 `DailyBarExitSimulator`로 옮겨져 호출부가 깨졌다.
  필드/생성자 인자 1개 추가 + 호출 2곳(`EXIT_MAX_HOLD`·`EXIT_TIMECUT`)을 새 대상으로 바꿨다.
  **로직·인자·순서 무변경.** (세션 중단 시점에 이 배선이 미완이라 컴파일이 깨져 있었고, 재개하며 해소)
- `com.trading.order`·`risk`·`control`·`market`은 **한 줄도 건드리지 않았다.**
  (작업 중 `TradingController`/`OrderEngine`/그 테스트에 다른 작업의 미커밋 변경이 보였으나 내 것이 아님)

## 4. 테스트 삭제/리네임 근거 (기대값 조작 없음)

| 변경 | 근거 | 검증 |
|---|---|---|
| `BacktestOrchestratorRegimeProfilesTest` 삭제 → `RegimeLabProfilesTest` 신설 | 검사 대상 상수(`REGIME_PROFILES`/`REGIME_SENS_PROFILES`)가 `RegimeLab`으로 이동 | 옛 파일에 클래스명만 치환해 diff → **차이 0줄**. 테스트 2건·단언 14건 그대로 |
| `EventBacktestPipelineGroupTest` → `MarketCapGroupStatsTest` (git mv) | `buildGroupTags`가 `MarketCapGroupStats`로 이동 | 클래스명 치환 후 diff → **차이 0줄**. 테스트 4건 그대로 |
| `LowVolCrashBacktesterTest` 수정 | 정적 계산 헬퍼가 `CrashVolCalculations`로, 서식이 `ReportFormat`으로 이동 + `aggregate` 인자 묶음 | diff 전량이 **호출 대상 이름 변경뿐** — 기대값(0.05·0.20·"+3.00%" 등) 무변경 |
| `DailyBarSimulatorTest` 수정 | 생성자 분리(진입/청산) | 배선 8줄만 변경, 시나리오·단언 무변경 |

## 5. 검증 증거 (이번에 직접 실행)

```
[검증 증거 1 — 컴파일]
명령: gradle compileJava compileTestJava --console=plain
종료 코드: 0
결과: error 0건 (BUILD SUCCESSFUL in 7s)
→ 판정: 통과

[검증 증거 2 — 전체 테스트]
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build gradle test --console=plain
      (test-results 디렉터리 삭제 후 전체 재실행)
종료 코드: 0
결과: 515개 중 515개 통과, 실패 0, 에러 0
      그중 com.trading.backtest 패키지: 113개 통과, 실패 0 (파일 16개)
→ 판정: 통과

[기준선 비교]
리팩터링 착수 전 baseline(같은 명령, backtest 패키지): 113개 통과, 실패 0
리팩터링 후: 113개 통과, 실패 0 → 회귀 없음
```

메서드 길이 검사(스크립트): backtest 패키지에서 50줄 초과 메서드는 2개뿐이며
둘 다 **이번 분리 대상이 아닌 기존 파일**(`EventStatsBacktester.compute` 105줄,
`BacktestRunner.run` 52줄)이다. 내가 만든 파일은 전부 50줄 이하.

## 6. 남긴 한계 · 판단이 필요한 것

1. **로거 이름이 바뀐다** — 메시지 본문(`[RegimeLab] ...` 등 대괄호 태그)은 글자 그대로
   같지만, 로그 앞머리의 클래스명이 `BacktestOrchestrator` → `RegimeLab`/`CostLab` 등으로
   갈린다. 리포트 파일(.md/.yml) 내용에는 영향 없음. 로그를 태그로 grep하는 기존 관행은 유지됨.
2. **DI 생성자 인자 5개 초과는 유지** — "파라미터 5개 초과 시 객체로 묶기"는 호출부가 있는
   업무 메서드에 적용했고(예: `aggregate` 7→2, `writeComparison` 10→5, 랩 메서드 6→2~3),
   스프링 주입 생성자는 예외로 뒀다(사람이 호출하지 않는 목록이고, 억지로 묶으면 프로필별
   구현체 교체 구조가 흐려진다). 이 해석이 지침과 다르면 알려달라 — 되돌릴 수 있다.
3. **런타임 배선(스프링 컨텍스트)은 컴파일·정적 확인까지만 했다.** 백테스트 프로필 컨텍스트를
   로드하는 테스트가 원래 없고(`@SpringBootTest`는 CommandLineRunner를 실제로 실행해버려
   백테스트가 돌아간다), 새 빈은 전부 타입이 유일해 모호성이 없음을 눈으로 확인했다.
   **실제 모드 1회 실행(예: `--backtest.mode=smoke`) 검증은 paper-ops-verifier 몫**으로 남긴다.
4. `RegimeLab.runSensitivity`가 `LabScope`를 내부에서 만들고 `run`은 밖에서 받는 비대칭이
   남아 있다 — 원본의 호출 형태(진입 람다 유무)를 그대로 보존하려는 선택이었다.
