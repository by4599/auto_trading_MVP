# 8_impl_anchor-sot-and-coverage — 회귀 앵커 정본화 + 백필 슬랙/커버리지 결함 수정

작업일: 2026-08-17 · 담당: trading-implementer
브랜치: `backtest/regime-filter-and-validation` (HEAD `62f6976` + 미커밋)
선행 문서: `_workspace/7_quant_baseline-drift.md` (원인 규명 — 이 작업의 전제)
전 변경은 `com.trading.backtest`(@Profile("backtest")) 안에서 끝난다 — 라이브 매매 경로 무수정.

---

## 결론 (한 줄)

**기준선 yml을 실행이 스스로 읽어 대조하게 만들고(작업 A), 판정 창 끝까지 캔들을 채우도록
백필을 고친 뒤 채점 전에 커버리지를 강제 검사하게 했다(작업 B).**
테스트 543/543 통과(종료 0), risk-lab 5개 프로필 전 지표 원 단위 재현, 기준선 yml md5 불변.

---

## 1. 작업 A — 회귀 앵커를 "기준 파일만 고치면 되도록"

### 설계 판단

| 결정 | 내용 | 근거 |
|---|---|---|
| 정본 | `docs/BACKTEST-BASELINE-{slug}.yml` 하나 | 쓰기는 여전히 `BacktestReportWriter.writeBaseline`만 한다("자동 생성 — 수동 편집 금지" 유지). **읽기 경로만 추가**했다 |
| 대조 시점 | 랩 리포트를 쓰기 직전(= 기준선 덮어쓰기 **전**) | 갱신 실행에서도 "직전 앵커 대비 무엇이 달라졌나"가 남는다 |
| 대조 대상 프로필 | 첫 합격 프로필 | 기준선을 기록할 때와 **같은 규칙**(`writeBaselineIfRequested`)이라 "찍었다면 들어갔을 값"과 정확히 대응 |
| 비교 해상도 | yml에 적힌 **문자열 그대로**(PF 3자리, 나머지 4자리) | 앵커의 해상도가 곧 파일 자릿수다. double 재비교는 epsilon 논쟁을 부르고, 라이브러리 파싱은 `0.0130`→`0.013`으로 자릿수를 잃는다 |
| 창이 다르면 | 대조 **생략**(리포트에 사유 기재) | §14.3 약세장 창처럼 일부러 창을 바꾸는 정상 사용이 있다 |
| 드리프트 시 | 경고 + 리포트 표 기재. **실행은 실패시키지 않는다** | 지시대로 — 이 단계 책임은 경고·기록까지 |
| 앵커 파일 없음 | 조용히 건너뜀(로그 1줄) | 첫 기록 실행 |
| YAML 라이브러리 | 쓰지 않음(최소 라인 파서) | ① 문자열 자릿수 보존이 요구사항, ② 우리가 만든 고정 13줄 포맷이라 일반 YAML 문법이 필요 없다. (snakeyaml이 의존성에 있긴 하나 ①이 결정적) |

### 실제 동작 (오늘 risk-lab 실행 로그·리포트)

```
[Baseline] ✅ 앵커 재현 확인 — RR1 하프(0.5R·동시5) (기준선 yml과 5지표 전부 일치)
```

리포트에 새로 붙는 절:

```
## 회귀 앵커 대조 — docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml

- ✅ 앵커와 일치 (대조 프로필: RR1 하프(0.5R·동시5))
| 지표 | 앵커 | 이번 실행 | 판정 |
| 트레이드 | 781 | 781 | ✅ |
| PF | 1.959 | 1.959 | ✅ |
| 기대값 | 0.0130 | 0.0130 | ✅ |
| MDD | 0.0987 | 0.0987 | ✅ |
| 승률 | 0.5186 | 0.5186 | ✅ |
```

드리프트가 있으면 `⚠ **앵커 드리프트 2건** … 트레이드 781→779, PF 1.959→1.940`처럼
**어느 지표가 얼마나** 달라졌는지 로그·리포트 양쪽에 찍힌다(단위 테스트로 고정).

---

## 2. 작업 B — 백필 7일 슬랙 결함

### 2-1. 백필이 판정 창 끝까지 채우게 (1겹)

**슬랙은 버리지 않았다.** 슬랙의 목적(매일 실행 시 최근 며칠 때문에 API를 매번 때리지 않기)은
여전히 유효하다. 대신 **고정 판정 창의 끝만 슬랙의 예외**로 뒀다:

```java
static boolean needsTailFill(LocalDate storedTo, LocalDate to,
                             LocalDate mustCoverThrough, int slackDays) {
    if (!storedTo.isBefore(to)) return false;                      // 상한까지 이미 저장
    if (storedTo.isBefore(to.minusDays(slackDays))) return true;   // 기존 7일 슬랙
    return mustCoverThrough != null
            && !mustCoverThrough.isAfter(to)                       // 아직 못 받는 미래 구간은 제외
            && storedTo.isBefore(mustCoverThrough);                // 판정 창 끝은 예외 없이 채운다
}
```

- `mustCoverThrough` = `BacktestDataProperties.requiredCoverageThrough()`
  = **max(candidateTo, crashVolTo, stressTo)**.
  세 창의 **독립성은 그대로다** — max는 "어느 창을 돌리든 그 끝까지 캔들이 있어야 한다"는
  *저장 요구*일 뿐, 한 창의 설정이 다른 창의 *판정*에 끼어들지 않는다(독립성 회귀 테스트 추가).
- 비용: 한 번 채우고 나면 `storedTo ≥ mustCoverThrough`가 되어 추가 호출이 생기지 않는다.
- 창 끝이 `to`(실행일−1)보다 미래면 예외를 적용하지 않는다 — 못 받는 구간을 매번 시도할
  이유가 없고, 그 상태는 아래 커버리지 검사가 잡는다.

### 2-2. 채점 전 커버리지 강제 검사 (2겹)

신규 `CandleCoverageChecker`가 후보 랩의 단일 관문(`CandidateLabRouter.prepareCandidateUniverse`,
백필 직후·채점 전)에서 검사한다.

| 항목 | 결정 | 근거 |
|---|---|---|
| 판정 기준 | **꼬리 지연**: 창 끝 이전 마지막 캔들 이후 창 끝까지 거래일이 1일이라도 남으면 미달 | 지시의 "마지막 캔들이 창 끝에서 N거래일 뒤처지면 실패"를 N=0으로 |
| 거래일 판정 | `MarketCalendarService`(market-calendar.yml) | 주말·공휴일을 결측으로 오판하지 않기 위해 |
| 구간 **안쪽** 구멍 | 검사하지 않음 | 캘린더 목록이 완전하지 않다(전 종목 캔들 0건인 2026-07-17이 yml에 없다). 안쪽까지 보면 **헛경보**가 난다. 꼬리만 보면 07-17은 프런티어 이전이라 무해 |
| 종목 단위 | 전 종목을 통틀어 가장 늦은 캔들(프런티어) | 개별 거래정지·상장폐지 종목 때문에 헛경보가 나지 않게 |
| 미달 시 | `write-baseline=true` → **IllegalStateException으로 중단**, 그 외 → WARN + **리포트에 기재** | 오염된 기준선이 굳는 게 최악이라는 지시 그대로 |
| 조회 범위 | 창 끝에서 소급 14일 | 슬랙(7일)보다 넉넉 |

리포트에 붙는 절: `## 데이터 커버리지 (채점 전 검사)` — 충족이든 미달이든 항상 못 박는다.

---

## 3. 변경 파일

### 신규 (모두 `com.trading.backtest`)

| 파일 | 이유 |
|---|---|
| `BaselineSnapshot.java` (51줄) | 앵커 값 묶음 + **기록 포맷 상수의 유일한 집** — 기록부와 판독부가 어긋날 수 없게 |
| `BaselineStore.java` (101줄) | 기준선 yml 읽기/파일명 규칙. 자동 생성 포맷 전용 최소 파서 |
| `BaselineDriftReporter.java` (96줄) | 앵커 대조 판정 + 로그 + 마크다운 절 생성 |
| `CandleCoverage.java` (36줄) | 커버리지 검사 결과값 + 리포트 문장 |
| `CandleCoverageChecker.java` (112줄) | 채점 전 검사·중단 판단·마지막 결과 보관 |

### 수정

| 파일 | 변경 | 이유 |
|---|---|---|
| `CandleBackfillService.java` | `needsTailFill` 추출 + 슬랙 상수화 | 7일 슬랙이 판정 창 끝을 삼키던 결함 |
| `BacktestDataProperties.java` | `requiredCoverageThrough()` 추가 | 커버리지 하한 = 세 창 끝의 max (판정 독립성 유지) |
| `CandidateLabRouter.java` | 커버리지 검사 호출 1줄 + 생성자 인자 1개 | 후보 랩 전체가 지나는 단일 관문 |
| `LabComparisonReportWriter.java` | 커버리지·앵커 대조 절 추가(생성자 인자 2개) | exit/risk/cost/regime/donchian/vb 랩 리포트 전부에 적용 |
| `BacktestReportWriter.java` | 기록 시 `BaselineSnapshot`/`BaselineStore.fileName` 사용 | 포맷·파일명을 판독부와 공유(출력은 종전과 **동일**) |

### 테스트 (신규 3 + 보강 2, 총 +24건)

`BaselineStoreTest`(6) · `BaselineDriftReporterTest`(7) · `CandleCoverageCheckerTest`(6) ·
`CandleBackfillServiceTest`(+3) · `BacktestDataPropertiesTest`(+2).
Java 25 인라인 목 제약 준수 — 목은 `CandleHistoryRepository`(인터페이스)뿐이고,
`MarketCalendarService`는 실객체, `BaselineStore`는 **익명 하위 클래스**로 값 주입.

---

## 4. 실행 증거

### 4-1. 전체 테스트

```
[검증 증거]
명령: gradle test --rerun-tasks --console=plain (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build)
종료 코드: 0
결과: 543개 중 543개 통과, 실패 0 (XML 집계: tests 543 skipped 0 failures 0 errors 0)
→ 판정: 통과   (작업 전 519 + 신규 24)
```

### 4-2. Red-Green (백필 슬랙 수정이 실제로 그 버그를 잡는가)

| 단계 | 상태 | 결과 |
|---|---|---|
| 1 | 수정 적용 | `CandleBackfillServiceTest` 14건 통과 (종료 0) |
| 2 | **수정 되돌림**(옛 슬랙 규칙) | **2건 실패** — `판정 창 끝이 아직 안 채워졌으면…`, `needsTailFill…` (종료 1) |
| 3 | 수정 복구 | 14건 통과 (종료 0) |

### 4-3. risk-lab 재현 (`--backtest.mode=risk-lab --backtest.write-baseline=false`)

종료 코드 0, 로그 `logs/backtest/impl8-risklab-verify.log`,
리포트 `logs/backtest/REPORT-RISKLAB-MA-P3-20260817-1652.md`.

| 프로필 | 요구 기준값 | 이번 실행 | 일치 |
|---|---|---|---|
| RR0 | 666건 | 666건 · PF1.53 · MDD20.96% · final 22,838,900 | ✅ |
| RR1 | 781건·PF1.96·기대값1.296%·MDD9.87%·총수익124.28%·final 22,427,654 | 781 · 1.96 · 1.296% · 9.87% · 124.28% · **22,427,654** | ✅ |
| RR2 | 508건 | 508건 · final 16,214,652 | ✅ |
| RR3 | 732건 | 732건 · final 13,839,334 | ✅ |
| RR4 | 322건 | 322건 · final 12,214,431 | ✅ |

리포트 대조(오늘 06:27 실행분 vs 이번 실행):
```
diff REPORT-...-0627.md REPORT-...-1652.md
→ 실행 시각 1줄 + 신규 2개 절(데이터 커버리지 / 회귀 앵커 대조)만 추가. 지표 diff 0줄
```

### 4-4. 커버리지 관문이 실제로 막는가 (라이브 확인)

DB를 건드리지 않고 확인하기 위해 **기준선을 절대 쓰지 않는 모드**(cost-lab)에
미래 창을 줘서 strict 경로를 태웠다:
`--backtest.mode=cost-lab --backtest.candidate-to=2026-12-31 --backtest.write-baseline=true`

```
종료 코드: 1
java.lang.IllegalStateException: [Coverage] cost-lab — 판정 창 끝(2026-12-31)까지 캔들 부족 —
 마지막 캔들 없음(최근 14일), 결측 거래일 9일 [2026-12-17 … 2026-12-30] … write-baseline=true
 실행이므로 중단한다. 백필로 채운 뒤 재실행할 것.
```
→ 채점 전에 중단됐고, **12-25(성탄절)·12-31(임시휴장)은 결측에서 제외**돼 캘린더 연동도 확인됐다.

### 4-5. 기준선 무오염

| 시점 | md5 (`docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml`) |
|---|---|
| 작업 시작 전 | `c2d8460d4874b99c4eaecc5e76d0b800` |
| risk-lab 재현 실행 후 | `c2d8460d4874b99c4eaecc5e76d0b800` |
| 커버리지 관문 확인 실행 후 | `c2d8460d4874b99c4eaecc5e76d0b800` |

`docs/` 하위 파일·문서는 **한 글자도 수정하지 않았다**. 다른 두 트랙
(`order/OrderEngine`·`control/TradingController`·`position/*`·`*.ps1`) 미변경, 커밋도 하지 않았다.

---

## 5. 문서가 앞으로 무엇을 참조하면 되는지 (리더용 재료 — 문서는 내가 안 고쳤다)

1. **앵커 수치를 문서에 다시 적지 않는다.** `BACKTEST-DESIGN.md` §14.1·§14.2와
   `CLAUDE.md`의 "781·PF 1.96·MDD 9.9%"는
   → *"회귀 앵커의 정본은 `docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml`이다.
   risk-lab을 돌리면 리포트 `## 회귀 앵커 대조` 절이 자동으로 대조 결과를 찍는다"* 로 바꾸면
   숫자가 한 곳에만 남는다. (읽는 사람을 위한 요약값이 꼭 필요하면 "출처: 위 yml, 자동 대조됨"을
   함께 적어 재측정 없이 고친 값이 아님을 표시)
2. **§14.2 cost-lab 표의 옛 780 주석**은 "재측정 대기" 대신
   *"cost-lab을 다시 돌리면 리포트가 정본과 자동 대조한다"*로 대체 가능하다.
3. **§4 절차 문서**에 한 줄 추가 후보: *"기준선을 새로 찍는 실행(`--backtest.write-baseline=true`)은
   판정 창 끝까지 캔들이 없으면 자동 중단된다"* — 사람이 지켜야 할 수칙에서 코드가 강제하는
   불변식으로 옮겨졌다는 사실 기록.
4. `BACKLOG.md`에 등재된 "7일 슬랙" 항목은 **해소**로 옮길 수 있다(근거: 본 문서 4-2·4-4).

---

## 6. 남긴 한계 / 하지 않은 것

1. **다른 모드(exit-lab·regime-lab·donchian-*·crash-vol)의 기준선·앵커가 같은 슬랙에 걸렸는지는
   점검하지 않았다 — 범위에 넣지 않았다.** 다만 두 안전장치는 후보 랩 전체에 이미 걸린다:
   ① 백필 예외는 세 창 끝 전부(max)를 보장하고, ② 커버리지 검사는 `prepareCandidateUniverse`를
   지나는 **모든** 후보 랩(risk/cost/crash-vol/regime/donchian/vb)에 적용된다.
   과거에 기록된 다른 리포트 수치의 소급 재측정은 quant 판단 사항이다.
2. **부동 창 모드(full·smoke·ma-breakout·scalping·exit-lab)에는 커버리지 검사를 걸지 않았다.**
   이들은 창 끝이 `rangeTo()`(실행일−1)라 "고정 창 vs 움직이는 상한" 어긋남이 구조적으로 없다.
3. **구간 안쪽 구멍은 검사하지 않는다**(꼬리만 본다). 이유는 2-2 표 참고 — market-calendar.yml의
   휴장일 목록이 불완전해(2026-07-17 미등재) 안쪽까지 보면 헛경보가 난다. 캘린더를 보강하면
   안쪽 검사로 확장할 수 있으나, 캘린더 파일은 paper 운영이 함께 쓰므로 이번 범위 밖으로 뒀다.
4. **거래일 판정의 정확도는 캘린더에 종속**된다. 목록에 없는 휴장일이 창 **끝자락**에 있으면
   과탐(헛실패) 방향으로 틀린다 — 안전한 방향이고, 로그에 결측 날짜가 그대로 찍혀 사람이 즉시
   분간할 수 있다.
5. **crash-vol 리포트(`CrashVolReportWriter`)에는 커버리지 절이 안 붙는다** — 검사·중단·로그는
   동일하게 적용되지만 리포트 문장은 `LabComparisonReportWriter` 계열에만 넣었다.
6. `CandleCoverageChecker`는 마지막 검사 결과를 필드에 들고 있다(리포트가 읽어 감).
   백테스트가 단일 스레드 배치 실행이라 안전하며, 같은 패키지의 `ExecutionKnobs`와 같은 방식이다.
7. **paper/real 경로·리스크 룰 8종·지갑 칸은 한 줄도 건드리지 않았다.**
