# 7_review_backtest-refactor — com.trading.backtest 리팩터링 코드 품질 리뷰

리뷰일: 2026-08-17 · 담당: code-reviewer
대상: `_workspace/4_impl_backtest-refactor.md`의 변경 전량 (미커밋 워킹트리)
브랜치: `backtest/regime-filter-and-validation`
방식: **읽기 전용 정적 리뷰** — 빌드·테스트 재실행 안 함(5_verify·6_audit에서 이미 증거 확보).
       `git show HEAD:<파일>`로 리팩터 전 원본과 대조는 수행.

---

## 결론 한 줄

**커밋 가능** — 코딩 지침(파일 300줄·함수 50줄·중첩 3단계·파라미터 5개)을 이번에 만든 34개
파일 전부가 실제로 지켰고(직접 계수 확인), 죽은 코드·안 쓰는 import·중복 계산·순환 의존이
0건이며, 남은 지적은 전부 LOW(스타일·문서 일관성)와 MEDIUM 2건(중복 상수 1개, 테스트 공백 1개)뿐이다.

---

## Findings 요약

**리뷰한 파일:** 41 (수정 6 + 신규 34 + 테스트 4)
**총 이슈:** 11

| 심각도 | 건수 |
|---|---|
| CRITICAL | 0 |
| HIGH | 0 |
| MEDIUM | 2 (고치는 걸 권함) |
| LOW | 9 (선택) |

---

## MEDIUM — 지금 고치는 걸 권함 (둘 다 동작 불변)

### [MEDIUM-1] 같은 상수 `FIXED_K`가 두 클래스에 복제됐다

- 위치: `src/main/java/com/trading/backtest/LabExecutor.java:20`
        · `src/main/java/com/trading/backtest/SingleStrategyLab.java:26`
- 근거: 두 곳 모두 `private static final List<Double> FIXED_K = List.of(0.5);` 이고,
  주석의 근거("MA·스캘핑은 K를 안 쓰니 3배 반복 회피")까지 사실상 같은 문장이다.
  이번 리팩터의 명시적 목표가 "2회 이상 반복 제거"였는데(4_impl §2-1) 이 한 쌍만 남았다.
- 왜 문제인가: 한쪽만 바꾸면 **§13 단일 전략 재생과 §14 랩 실행이 서로 다른 K로 돌아간다.**
  두 결과를 나란히 비교하는 게 이 엔진의 용도인데, 어긋나도 컴파일·테스트가 아무 말을 안 한다.
- 고치는 법: `LabExecutor.FIXED_K`의 `private`을 떼고(패키지 가시성) `SingleStrategyLab`이
  그것을 참조한다. 값은 그대로 0.5 — 계산 결과 무변경.
- 참고: `SingleStrategyLab`이 `LabExecutor.evaluate()`를 통째로 못 쓰는 건 정당하다
  (리포트 경로가 `writeReport`+`writeBaseline`로 달라서). 상수만 공유하면 된다.

### [MEDIUM-2] `EventCandidateCriteria`에 직접 테스트가 없다

- 위치: `src/main/java/com/trading/backtest/EventCandidateCriteria.java:11,21-27`
- 근거: 신규 34개 클래스 중 **순수 판정 로직인데 직접·간접 테스트가 모두 없는 유일한 클래스**다.
  (`CrashVolCalculations`·`ReportFormat`·`MarketCapGroupStats`·`RegimeLab` 프로필표는
  `LowVolCrashBacktesterTest`/`MarketCapGroupStatsTest`/`RegimeLabProfilesTest`가 직접 덮고,
  `HeldPositions`·`DailyBarExitSimulator`는 `DailyBarSimulatorTest` 10건이 시나리오로 덮는다.)
- 왜 문제인가: 이 클래스가 정하는 게 **CANDIDATE(후보 표기) 경계**다 —
  `EventRegistryUpdater:40`(DB 레지스트리 기록)과 `EventReportWriter:89,108`(리포트 표기)이
  같은 기준을 보게 하려고 일부러 하나로 뽑은 자리인데, 그 "하나"가 무방비다.
  경계값(표본 29/30, p25가 왕복비용 0.41%의 바로 아래/위)이 뒤집혀도 아무도 못 잡는다.
- 고치는 법: 3~4줄짜리 단위 테스트 하나. 목(mock) 필요 없음 — `meets(int, Quantiles)` 오버로드가
  이미 값만 받는다. 예: 표본 29→false, 30+p25>0.0041→true, 30+p25<0.0041→false.
- 참고: 이 로직 자체는 리팩터 전에도 테스트가 없었다(원본 `EventBacktestPipeline` 인라인).
  **리팩터가 만든 결함이 아니라, 리팩터가 처음으로 테스트 가능하게 만든 자리**다.

---

## LOW — 고쳐도 좋고 안 고쳐도 되는 것

| # | 위치 | 내용 | 권고 |
|---|---|---|---|
| L-1 | `DailyBarSimulator.java:181` | 반환형을 `com.trading.position.Position` 전체 경로로 썼다. 원본(`HEAD:DailyBarSimulator.java:5`)은 `import com.trading.position.Position`을 갖고 있었고, 형제 클래스 `DailyBarExitSimulator.java:5`는 지금도 import 한다. 분리하다 import가 떨어져 나간 자국 | 지금 고쳐도 무해 (import 1줄 복구) |
| L-2 | `LabScope.java:10` · `ReportFormat.java:9` · `LabLog.java:11` · `ExitProfile.java:6` · `LabReportTemplates.java:9` · `CrashVolCalculations.java:22` · `BacktestModeRunner.java:15` | "값 자체는 이전과 같다" / "서식 문자열은 이전 각 클래스의 것과 동일하다" 류의 **이사 기록 주석**. 커밋되는 순간 "이전"이 무엇인지 알 수 없어지고, 그 정보는 git이 갖고 있다 | 커밋 전 한 줄씩 삭제 권장. **단 아래는 남길 것** — 제약을 적은 좋은 주석이다: `StrategyToggles.java:14-17`(합치면 yml에서 켠 전략이 조용히 꺼진다), `ExecutionKnobs.java:12-13`(복원 누락=다음 프로필 오염이라 순서 고정), `LabExecutor.java:13-14`(K 단일값 근거) |
| L-3 | `src/test/java/com/trading/backtest/LowVolCrashBacktesterTest.java` | 이제 단언 대상이 `CrashVolCalculations`·`ReportFormat`뿐인데 클래스명은 그대로다. 같은 상황이던 `EventBacktestPipelineGroupTest`는 `MarketCapGroupStatsTest`로 git mv 했으므로 **처리가 서로 다르다** | 다음 손볼 때 `CrashVolCalculationsTest`로. 지금 하면 diff만 커진다 |
| L-4 | `ExitLabRow.java:4` · `AppliedCost.java:12` | 이 둘만 `public record`이고, 같은 성격의 신규 레코드 6개(`LabScope`·`ExitProfile`·`CostProfile`·`ComparisonTemplate`·`EventReportData`·`CrashVolScope`)는 패키지 전용이다. 패키지 밖 사용처는 없다(전수 확인) | `public` 제거 가능. 원래 `BacktestReportWriter`의 중첩 public 레코드였던 흔적이라 남은 것 |
| L-5 | `BacktestReportWriter.java:105-116` · `LabComparisonReportWriter.java:137-149` · `CrashVolReportWriter.java:101-113` · `EventReportWriter.java:136-147` | 리포트 저장 블록(`createDirectories` → 타임스탬프 파일명 → `writeString` → 로그)이 네 곳에 거의 동일. 게다가 실패 처리가 갈린다 — 앞 셋은 `UncheckedIOException`을 던지고 `EventReportWriter:145`는 **로그만 찍고 삼킨다**(리포트가 안 써져도 실행이 성공한 것처럼 끝난다) | **리팩터가 만든 중복이 아니다** — 원본에도 4곳이었다(`HEAD:BacktestReportWriter` 3 + `HEAD:EventBacktestPipeline` 1). 지금 손대면 "동작 불변" 원칙을 깬다. 별건으로 BACKLOG |
| L-6 | `DonchianLab.java:118,145,172` · `VbFilterLab.java:85` | 파라미터 민감도·추세지도·사이징·진입필터 랩이 전부 `writeRiskLabReport`를 쓴다 → 리포트 제목이 "Risk Lab — 리스크 축소 프로필 비교", 설명이 "사이징(1R 비율)·동시보유 종목수만 스윕"으로 **사실과 다르게** 찍힌다(`LabReportTemplates.java:38-46`) | **원본에도 같은 배선**(`HEAD:BacktestOrchestrator.java:1118,1163,1199,1291`). 고치면 리포트 출력이 바뀌므로 **이번 커밋에서는 손대지 말 것**. 사람이 리포트를 오독할 여지라 BACKLOG 등록 권장 |
| L-7 | `RegimeLab.java:68` vs `RegimeLab.java:91` | 4_impl §6-4가 자진 신고한 비대칭 — `run`은 `LabScope`를 밖에서 받고 `runSensitivity`는 안에서 만든다 | **비일관 아님 — 고치지 말 것.** 패키지 전체의 규칙은 일관된다: 호출자마다 라벨/슬러그가 달라지는 랩은 `LabScope`를 받고(`ExitLab`·`RiskLab`·`RegimeLab.run`·`SingleStrategyLab.runSingle`), 정체성이 하나인 랩은 스스로 만든다(`CostLab`·`DonchianLab`·`VbFilterLab`·`RegimeLab.runSensitivity`). `RegimeLab`은 두 종류를 다 가진 유일한 클래스라 눈에 띌 뿐이다. 필요하면 `runSensitivity`에 한 줄 주석만 |
| L-8 | `ExecutionKnobs.java:35-37` | `filters()`가 원본 `FilterProperties`를 그대로 내주고, `FullBacktestLab.java:90-111`·`VbFilterLab.java:75,83`이 그 안을 직접 세팅한다. "손잡이를 한곳에 모았다"는 이 클래스의 취지가 두 곳에서 새어 나간다 | 세터 6종을 더 만들면 클래스가 부풀고 값이 늘어난다. **현행 유지 권장** — 다만 `filters()` Javadoc에 "직접 세팅은 A/B 랩 전용"이 이미 적혀 있어 의도는 읽힌다 |
| L-9 | `BacktestModeRunner.java:37-61` | 신규 클래스 중 유일하게 생성자 인자가 12개다(백필·유동성·시계·분봉이관·이벤트·러너·토글 + 랩 4개 + 라우터). 데이터 모드(`import-minutes`/`events`/`smoke`)와 전략 모드가 한 클래스에 같이 산다 | 지금 쪼개면 20줄짜리 클래스가 하나 더 생겨 과분할이 된다. **현행 유지.** 모드가 더 늘면 그때 `runDataMode` 쪽을 분리 |

---

## 1. 코딩 지침 준수 — 정량 검증 (직접 계수)

측정 방법: 문자열·주석을 제거한 뒤 중괄호 매칭으로 메서드 범위를 잡는 파서를 돌렸다
(선언줄~닫는 중괄호). 대상 = 이번 리뷰 범위의 41개 파일 전부.

### 1-1. 파일 300줄 이하 — ✅ 위반 0건

이번 범위 상위 12개 (전부 통과, 최댓값 247):

| 줄수 | 파일 | 비고 |
|---|---|---|
| 247 | `CandidateLabRouter.java` | 신규 |
| 228 | `LowVolCrashBacktester.java` | 390 → 228 |
| 222 | `RegimeLab.java` | 신규 |
| 212 | `BacktestReportWriter.java` | 551 → 212 |
| 207 | `DonchianLab.java` | 신규 |
| 204 | `BacktestRunner.java` | 배선 7줄만 변경 |
| 202 | `CrashVolCalculations.java` | 신규 |
| 184 | `DailyBarSimulator.java` | 301 → 184 |
| 183 | `DailyBarExitSimulator.java` | 신규 |
| 177 | `LabComparisonReportWriter.java` | 신규 |
| 152 | `EventReportWriter.java` | 신규 |
| 146 | `FullBacktestLab.java` | 신규 |

참고: `BacktestOrchestrator` 1349 → **45**.

### 1-2. 함수 50줄 이하 — ✅ 신규·분리 파일 위반 0건

패키지 전체에서 50줄을 넘는 메서드는 6개이며 **전부 이번 분리 대상이 아니다**:

| 줄수 | 위치 | 이번 리뷰 범위? |
|---|---|---|
| 105 | `EventStatsBacktester.compute` (61-165) | ✗ 기존 파일, 무수정 |
| 75 | `SpilloverStatsBacktester.computeTheme` (96-170) | ✗ 기존 파일, 무수정 |
| 72 | `EntryTriggerBacktester.computeTheme` (98-169) | ✗ 기존 파일, 무수정 |
| 67 | `BacktestMetrics` 생성자 (14-80) | ✗ 기존 파일, 무수정 |
| 65 | `EntryTriggerBacktester.simulate` (175-239) | ✗ 기존 파일, 무수정 |
| 52 | `BacktestRunner.run` (84-135) | △ 파일은 범위 안이지만 **리팩터 전에도 52줄**(`HEAD` 기준 81-132) — 이번에 늘리지 않았다 |

이번 범위 파일들의 **최장 메서드 상위 8개** (전부 50줄 미만):

| 줄수 | 메서드 |
|---|---|
| 43 | `CandidateLabRouter.routeSizingAndCostLabs` (65-107) |
| 42 | `LowVolCrashBacktester.compute` (106-147) |
| 38 | `CrashVolCalculations.accumulateAnchor` (138-175) |
| 36 | `EventBacktestPipeline.run` (63-98) |
| 31 | `DonchianLab.runSensitivity` (94-124) |
| 29 | `VbFilterLab.runFilterLab` / `BacktestReportWriter.writeReport` / `BacktestModeRunner.runStrategyMode` |
| 28 | `FullBacktestLab.runFilterVariants` (87-114) |
| 27 | `SingleStrategyLab.runScalping` (49-75) |

→ **구현자의 "내가 만든 파일은 전부 50줄 이하" 주장은 사실이다.**

### 1-3. 중첩 3단계 이하 — ✅ 위반 0건

이번 범위 파일의 **최대 중첩은 2단계**(메서드 본문 안쪽 기준). 3단계 초과는 패키지 전체에서
4곳뿐이고 전부 기존·무수정 파일이다(`WalkForwardEngine.run`, `EventStatsBacktester.compute`,
`EntryTriggerBacktester.simulate`·`computeTheme` — 각 4단계).

### 1-4. 파라미터 5개 이하 — ✅ 업무 메서드 위반 0건 / △ DI 생성자·레코드는 초과

5개를 넘는 곳은 **①스프링 주입 생성자 ②값 묶음 레코드** 두 종류뿐이고, 사람이 호출하는
업무 메서드 중 6개 이상은 **한 건도 없다**.

| 인자 수 | 위치 | 종류 |
|---|---|---|
| 13 | `BacktestRunner` 생성자 | DI (이번에 12→13, `DailyBarExitSimulator` 1개 추가) |
| 12 | `BacktestModeRunner` 생성자 | DI (신규, L-9 참고) |
| 10 | `EventBacktestPipeline` / `DailyBarSimulator` 생성자 | DI (`DailyBarSimulator`는 11→10으로 **감소**) |
| 9 | `DailyBarExitSimulator` / `CandidateLabRouter` 생성자 | DI (신규) |
| 9 | `LowVolCrashBacktester.CrashVolReport` | 레코드(리포트 입력 묶음, 기존) |
| 8 | `BacktestReportWriter.ReportData` | 레코드(기존) |
| 7 | `ExitProfile` / `EventReportData` | 레코드(값 묶음 그 자체) |
| 6 | `CrashVolScope` | 레코드 — **원래 `aggregate` 7인자를 2인자로 줄인 결과물** |

반대로 이번에 줄어든 것: `aggregate` 7→2, `writeComparison` 10→5, 랩 메서드 6→2~3.

### 1-5. "DI 생성자는 5개 초과 예외" 판단에 대한 의견 — **타당하다 (조건부)**

**타당한 이유 3가지:**

1. 지침의 목적은 **호출부 가독성**이다("객체로 묶기"). 스프링 주입 생성자는 사람이 호출하는
   자리가 없다 — 컨테이너가 채운다. 묶어도 읽는 사람이 편해지지 않는다.
2. 억지로 묶으면 CLAUDE.md 규칙 4(프로필별 구현체 교체)를 흐린다. `MarketDataService`·
   `KisOrderClient`를 홀더 객체에 넣으면 "이 클래스가 무엇에 의존하는가"가 생성자에서
   사라지고, `paper`/`real`/`backtest` 갈아끼우는 지점이 한 겹 가려진다.
3. 기존 패키지 관행과 같다 — 리팩터 이전에도 `BacktestRunner`(12) ·
   `BacktestStateReset`(10) · `BacktestOrderClient`(9)가 이 모양이었다. 이번에 새로 만든
   나쁜 습관이 아니라 유지된 관행이다.

**조건(무시하면 안 되는 것):** 생성자 인자 수는 여전히 **응집도 신호**다. 지침의 문자를
면제받았다고 신호까지 사라지지는 않는다. 지금 감시 대상은 두 개:
`BacktestRunner`(13) — 이번에 1개 늘었다, `BacktestModeRunner`(12) — 신규.
둘 다 지금 쪼개면 과분할이므로 **이번엔 손대지 말고**, 다음에 인자가 하나라도 더 붙는 순간
"모드가 늘어난 게 아니라 책임이 늘어난 것"으로 보고 분리 판단을 하면 된다.

---

## 2. 과분할 여부 — **과분할 없음 (합칠 후보 0건)**

"20줄로 끝나는 작업을 3개 파일로 쪼개지 않는다"에 걸리는 파일이 있는지, 신규 34개 전부에 대해
**다른 파일에서 참조하는 개수**를 세어 판정했다.

작은 파일이 존재를 정당화하는 근거는 셋 중 하나여야 한다고 봤다 —
(가) 참조처 3곳 이상 (나) 지침의 "파라미터 5개 초과 → 객체로 묶기"의 산물 (다) 두 곳이
반드시 같은 정의를 봐야 하는 안전장치.

| 파일 | 줄수 | 참조 파일 수 | 정당화 |
|---|---|---|---|
| `AppliedCost` | 11 | 7 | (가) |
| `ComparisonTemplate` | 12 | 2 | (나) `writeComparison` 10인자 → 5인자 |
| `ExitLabRow` | 14 | 9 | (가) |
| `EventReportData` | 15 | 2 | (나) 리포트 입력 7값 묶음 |
| `CrashVolScope` | 16 | 2 | (나) `aggregate` 7인자 → 2인자 |
| `LabScope` | 18 | 11 | (가) 최다 참조 |
| `ExitProfile` | 18 | 4 | (가) — P3 조합이 원본에 4번 인라인이었다 |
| `CostProfile` | 19 | 2 | (가 준함) 스윕 상수표 자체 |
| `HeldPositions` | 23 | 2 | (다) 진입부·청산부가 "보유"를 다르게 세면 중복 진입/헛매도 |
| `EventCandidateCriteria` | 23 | 2 | (다) 레지스트리 기록과 리포트 표기가 같은 기준을 봐야 함 |
| `LabLog` | 29 | 2 (호출 3회) | (가) |
| `LabExecutor` | 48 | 6 | (가) 원본 10곳 중복 제거 |
| `ReportFormat` | 51 | 11 | (가) |

→ 30줄 미만 파일 11개 중 **근거가 약해 합치는 게 나은 것은 없다.**
   가장 애매한 건 `HeldPositions`(23줄·정적 메서드 1개·참조 2곳)인데, 이건 "수량 0 잔재를
   보유로 세면 헛매도" 라는 실패 모드를 막는 단일 정의라 남길 값어치가 있다고 본다.

**반대로 더 쪼개야 할 파일도 없다.** 후보로 본 3개 판단:
- `CandidateLabRouter`(247) — 라우팅 + `prepareCandidateUniverse`(백필 부수효과). 뽑아내면
  10줄짜리 파일이 하나 더 생긴다 → 과분할. 유지.
- `DonchianLab`(207) — 랩 4개가 한 클래스에 있지만 `applyFixedWithIndexFilter` 고정 조건을
  공유하고 전부 "돈치안 강건성" 한 주제다. 유지.
- `LowVolCrashBacktester`(228) — 조회·배너 + 레코드 6개(약 50줄). 300줄 아래이고 레코드는
  이 클래스의 반환 타입이라 함께 사는 게 자연스럽다. 유지.

---

## 3. 응집도 · 명명 · 의존 방향

### 3-1. 순환 의존 — **없음 (0건)**

의존 방향이 한 방향으로 흐른다:

```
BacktestOrchestrator → BacktestModeRunner → CandidateLabRouter → 각 Lab
                                          ↘ FullBacktestLab / SingleStrategyLab / ExitLab
각 Lab → LabExecutor · ExecutionKnobs · StrategyToggles · LabComparisonReportWriter
       → (값) LabScope · ExitProfile · CostProfile · ExitLabRow · AppliedCost · ReportFormat
```

- `CandidateLabRouter` ↔ Lab 은 **단방향**이다 — 어떤 Lab도 라우터를 알지 못한다(전수 확인).
- 역방향으로 보이던 `LabReportTemplates → RegimeLab` 한 건은 **오탐**이었다:
  `LabReportTemplates.java:84`의 판독 지침 문자열 안에 `` `[RegimeLab] 2022 창` ``이라는
  **로그 태그 안내 문구**가 들어 있을 뿐, 타입 참조가 아니다.
- `LabComparisonReportWriter → BacktestReportWriter`(기준선 기록·`Judgment` 타입)는 단방향이고,
  반대쪽 `BacktestReportWriter.java:24-25`의 언급은 Javadoc `{@link}`뿐이다.

### 3-2. 명명 — 대체로 뜻이 통한다

| 이름 | 판단 |
|---|---|
| `ExecutionKnobs` | ○ "실행 중 돌리는 손잡이(필터·손절배수·사이징·출구·비용)" — Javadoc 첫 줄이 무엇을 담는지 나열해 오해 여지가 없다 |
| `StrategyToggles` | ○ on/off 스위치 묶음. 메서드명(`enableVbOnly` / `enableVbOnlyResetAll`)이 **"무엇을 안 건드리는가"까지** 이름에 담아 위험한 차이를 드러낸다 — 잘한 명명 |
| `LabScope` | △ 다소 추상적이다(라벨+슬러그+유니버스+기간). 다만 `withNames()`가 있어 "표기 이름만 바꾼 같은 범위"라는 용법이 코드에서 읽힌다. 참조 11곳에서 혼동 사례 없음 |
| `HeldPositions` | ○ 복수형 클래스에 단수 조회(`of`) 하나뿐이라 살짝 어긋나지만, 호출부 `HeldPositions.of(repo, code)`가 자연스럽다 |
| `LabExecutor` | ○ 다만 `@Component`이면서 `public static windowCount()`도 갖는 혼합형. 배너용 유틸이라 실용적, 문제 아님 |
| `CandidateLabRouter` | ○ "후보 유니버스를 쓰는 랩들의 라우터" — 클래스 Javadoc이 candidate/stress/crash-vol 세 창을 구분해 적어 둬 정확 |

**같은 개념이 두 이름으로 갈린 곳 / 다른 개념이 한 이름을 쓴 곳:** 없음.
다만 리포트 쪽에 L-6(랩 성격과 다른 "Risk Lab" 제목)이 있는데, 이는 원본에서 넘어온 것이다.

### 3-3. 패키지 스타일 일관성 — ✅ 전수 통과

- 신규 스프링 빈 21개 = `@Component` 21개 = `@Profile("backtest")` 21개 (**하나도 빠짐없음**).
- 나머지 14개는 빈이 아닌 값 타입(record 8) / 정적 유틸(final class + private 생성자 6)로,
  어노테이션을 붙이지 않은 게 맞다.
- 전부 생성자 주입, 필드 `private final`. 세터 주입·필드 주입 0건.
- `System.out` / `System.err` / `printStackTrace` / `TODO` / `FIXME`: **0건**.

---

## 4. 리팩터링이 남긴 냄새

| 항목 | 결과 |
|---|---|
| 안 쓰는 import | **0건** (신규·수정 41파일 전수 검사). 패키지 전체에서 2건이 나왔지만 둘 다 무수정 기존 파일(`BacktestClockConfig:8`, `EntryTriggerBacktester:16`) |
| 죽은 코드(안 쓰는 메서드·필드) | **0건**. 신규 헬퍼 24종(`applyExitProfile`·`restoreRegimeDefaults`·`withNames`·`verdict`·`pf`·`pct` 등) 전부 선언 외 참조 1회 이상 |
| 이동 중 생긴 계산 중복 | **없음.** 유일하게 남은 복제는 상수 `FIXED_K` 1건(MEDIUM-1). `adopted = PF↑ && MDD↓` 판정이 `SingleStrategyLab:87`·`FullBacktestLab:125` 두 곳에 있지만 **원본에도 두 곳이었고**, 지침의 추상화 기준("3번째 중복부터")에 아직 못 미친다 |
| 리포트 저장 블록 4중복 | 리팩터 산물 아님 — 리팩터 전 4곳, 후 4곳(L-5) |
| 주석 품질 | 제약·이유를 적은 좋은 주석이 다수(`StrategyToggles:14-17`, `ExecutionKnobs:12-13`, `AppliedCost` 전문, `MarketCapGroupStats:21-26` 과거 사고 기록). 반면 **이사 기록 주석 7건**은 커밋되면 의미를 잃는다(L-2) |
| `RegimeLab.runSensitivity` 비대칭(§6-4) | **비일관 아님 — 고치지 말 것** (판단 근거는 L-7) |

---

## 5. 테스트 변경의 적정성

### 5-1. 삭제 → 신설 대체가 커버리지를 줄였나 — **줄지 않았다 (직접 검증)**

구현자 주장("클래스명 치환 후 diff 0줄")을 **이번 세션에서 재현했다**:

```
git show HEAD:...BacktestOrchestratorRegimeProfilesTest.java
  | sed 's/BacktestOrchestratorRegimeProfilesTest/RegimeLabProfilesTest/; s/BacktestOrchestrator/RegimeLab/'
  | diff - src/test/java/com/trading/backtest/RegimeLabProfilesTest.java
결과: 차이 0줄
```

`MarketCapGroupStatsTest`(git mv)도 diff 전량이 `EventBacktestPipeline.buildGroupTags` →
`MarketCapGroupStats.buildGroupTags` 호출 대상 치환 3곳 + `@DisplayName`/클래스명뿐이고,
단언 4건과 기대값은 그대로다.
`LowVolCrashBacktesterTest`도 diff 전량이 호출 대상 치환 + `aggregate` 인자 묶음이며,
기대값(0.05 · 0.20 · -0.10 · "+3.00%" · "-")은 한 글자도 안 바뀌었다.

### 5-2. 신규 34개 중 테스트가 필요한데 없는 것 — **1개 (MEDIUM-2)**

| 클래스 | 커버리지 | 판단 |
|---|---|---|
| `CrashVolCalculations` | **직접** — `LowVolCrashBacktesterTest`(단언 다수) | 충분 |
| `ReportFormat` | **직접** — 같은 테스트가 `signedPct`(빈 표본 `-` 포함)·`lowMinusHighText` 검증 | 충분. `pf`의 무한대 분기는 미검증이나 원래도 그랬다 |
| `MarketCapGroupStats` | **직접** — `MarketCapGroupStatsTest` 4건 | 충분 |
| `RegimeLab`(프로필 상수) | **직접** — `RegimeLabProfilesTest` 2건·단언 14건 | 충분 |
| `HeldPositions` · `DailyBarExitSimulator` | **간접** — `DailyBarSimulatorTest` 10건이 갭다운/장중손절/손절선없음/익절 ON·OFF/이월 트레일 갭·터치를 시나리오로 통과 | 충분(단위 테스트를 또 만들 이유 없음) |
| `EventCandidateCriteria` | **없음** | **MEDIUM-2** |
| 나머지 랩·라우터·리포트 라이터 | 없음 | 적정 — DB·워크포워드 엔진이 필요해 단위 테스트 비용이 크고, 오케스트레이션이라 5_verify의 실기동 + 리팩터 전후 산출물 원단위 대조가 더 강한 증거다 |

**부수 관찰(LOW, 지금 하지 말 것):** 프로필 상수표를 지키는 가드 테스트는 레짐(`RegimeLab`)에만
있다. 이제 `ExitLab.EXIT_PROFILES` · `RiskLab.RISK_PROFILES` · `CostProfile.SWEEP` ·
`DonchianLab`의 3개 표도 각자 클래스에 모여 있어 같은 형태의 가드를 붙이기 쉬워졌다.
회귀 앵커 수치가 이 표들에 걸려 있으므로 값어치는 있지만, 이번 커밋 범위는 아니다.

---

## 6. "지금 고칠 것" vs "남겨둘 것"

### 지금 고칠 것 (커밋 전, 전부 동작 불변)

1. **MEDIUM-1** `FIXED_K` 중복 제거 (`SingleStrategyLab:26` → `LabExecutor.FIXED_K` 참조)
2. **MEDIUM-2** `EventCandidateCriteria` 단위 테스트 3~4줄 추가
3. **L-1** `DailyBarSimulator`에 `import com.trading.position.Position` 복구 (1줄)
4. **L-2** 이사 기록 주석 7줄 삭제 — 단, 제약을 적은 주석 3곳은 **반드시 남길 것**

### 남겨둘 것 (이번 커밋에서 손대지 말 것)

- **L-5 / L-6** — 원본에서 그대로 넘어온 것이고, 손대면 리포트 출력이 바뀐다.
  이 리팩터의 대원칙이 "동작 불변"이므로 **별건 BACKLOG**로 넘긴다.
  특히 L-6(랩 성격과 다른 "Risk Lab" 제목)은 사람이 리포트를 오독할 수 있어 등록 권장.
- **L-7** `RegimeLab` 비대칭 — 패키지 규칙과 어긋나지 않는다. 고치면 오히려 규칙이 깨진다.
- **L-3 / L-4 / L-8 / L-9** — 취향·구조 다듬기. diff만 커지고 얻는 게 적다.
- `BacktestRunner.run`(52줄)·`EventStatsBacktester.compute`(105줄) 등 **기존 파일의 긴 메서드**
  — 이번 지시 범위 밖이고, 건드리면 "동작 불변" 검증(5_verify §3-2 원단위 대조)의 전제가 깨진다.

---

## 7. 이번 리뷰에서 확인하지 않은 것 (추측 안 함)

- 빌드·테스트 재실행: 하지 않았다(지시). 515/515 · backtest 113/113 통과와 리팩터 전후
  원단위 일치는 `5_verify` / `6_audit`의 증거를 인용만 하고 다시 검증하지 않았다.
- 계산식·판정 임계·리터럴 전수 대조: `6_audit`이 이미 수행(CRITICAL 0·HIGH 0). 중복 감사하지 않았다.
- `5_verify` §3-3의 별건(§14.1 기준선 780→781 불일치): 이번 리팩터 무관으로 이미 증명됐고
  코드 품질 사안이 아니라 이 리뷰에서 다루지 않았다. **quant/risk-auditor 추적 대상으로 남아 있다.**
- 범위 밖 미커밋 변경(`order`·`control`·`position`·`RiskMonitorTest`·`*.ps1`): 지시대로
  읽지도 리뷰하지도 않았다.
