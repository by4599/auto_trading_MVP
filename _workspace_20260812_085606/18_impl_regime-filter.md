# 18_impl_regime-filter — 지수 하락추세 진입금지 필터 + regime-lab 모드 (§14.4)

측정용 A/B 구현(채택 아님). **백테스트는 돌리지 않았다** — 실행·판정은 strategy-quant.
컴파일 + 단위 테스트까지가 이 작업의 범위다.

기본 OFF 신설 필터라 paper/real 동작·리스크 룰 기본값·지갑 칸·기존 백테스트 모드
(full/smoke/ma-breakout/scalping/events/exit-lab/risk-lab/cost-lab/crash-vol) 결과는 전부 불변.
`docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml` 무변경(md5 `852a29da9fd5d40d8be9b5c2f2904ba0`,
mtime 2026-07-23 01:20 — 실행 후 재확인).

## 바꾼/추가 파일 (git diff와 1:1)

| 파일 | 성격 | 내용 |
|---|---|---|
| `src/main/java/com/trading/strategy/FilterProperties.java` | 수정(가산) | `IndexTrend` 내부 클래스(enabled=false, maPeriod=200) + 필드/`getIndexTrend()` 3줄. **기존 5개 필터 무수정** |
| `src/main/java/com/trading/risk/IndexRegimeSource.java` | 수정(가산) | `default Optional<Boolean> isBelowTrend(int maPeriod) { return Optional.empty(); }` 추가. **기존 `isBearishRegime()` 시그니처·의미 무수정** |
| `src/main/java/com/trading/risk/IndexTrendRule.java` | **신규** | `RiskRule` 구현 + `@Component` (CLAUDE.md 규칙 3 — RiskEngine 무수정) |
| `src/main/java/com/trading/backtest/BacktestIndexRegimeSource.java` | 수정 | `isBelowTrend` 구현 + 순수 판정 `static belowTrend(...)` + 지수 종가 캐시. 생성자에 `CandleHistoryRepository` 1개 추가. **`isBearishRegime()` 본문 무수정** |
| `src/main/java/com/trading/backtest/BacktestDataProperties.java` | 수정(가산) | `stressFrom`(2020-01-01)·`stressTo`(2026-07-21) 필드 + getter/setter. **candidate/crashVol 무수정** |
| `src/main/resources/application-backtest.yml` | 수정(가산) | `stress-from`/`stress-to` 명시(재현성) |
| `src/main/java/com/trading/backtest/BacktestOrchestrator.java` | 수정(가산) | `regime-lab` 분기 + `runRegimeLab`/`runRegimeProfile`/`logCrisisWindows`/배너·요약 로그. **기존 모드 분기·`runRiskLab`·`allFiltersOff`·`applyExitProfile` 무수정** |
| `src/main/java/com/trading/backtest/BacktestReportWriter.java` | 수정(가산) | `writeRegimeLabReport(...)` 1개 추가. **`writeComparison`·`judge`·`writeReport`·`writeBaseline` 무수정** |
| `src/test/java/com/trading/backtest/BacktestIndexRegimeSourceTest.java` | **신규** | 순수 단위 6케이스 (Mockito 없음) |
| `src/test/java/com/trading/risk/FilterRulesTest.java` | 테스트(가산) | `@Nested IndexTrend` 6케이스 추가. **기존 8케이스 단언 무변경** |
| `src/test/java/com/trading/backtest/BacktestDataPropertiesTest.java` | 테스트(가산) | stress 창 3케이스 추가 |

> `git diff --stat`의 큰 숫자(Orchestrator 566·ReportWriter 368 등)는 **세션 시작 시 이미
> `M` 상태였던 이전 작업의 미커밋 변경분**을 포함한다. 이번 추가분은 위 표대로다.

## 선견편향을 어떻게 차단했나 (감사 최우선 항목)

판정 함수는 `BacktestIndexRegimeSource.belowTrend(closes, simDate, maPeriod)` 한 곳뿐이다.

```java
List<Double> past = new ArrayList<>(closes.headMap(simDate, false).values()); // ← 차단선
if (past.size() < maPeriod) return Optional.empty();
List<Double> window = past.subList(past.size() - maPeriod, past.size());
double movingAverage = sum(window) / maPeriod;
double previousClose = window.get(window.size() - 1);
return Optional.of(previousClose < movingAverage);
```

| 항목 | 정확한 범위 |
|---|---|
| 사용하는 날짜 | `date < simDate` — `headMap(simDate, **false**)`(상한 **미포함**) |
| 이동평균 표본 | 그중 **마지막 N개** = 거래일 `[simDate-N, simDate-1]` (인덱스 `past.size()-N` ~ `past.size()-1`) |
| 비교 종가 | 같은 구간의 **마지막 원소 = 전일 종가** (`window.get(size-1)`) |
| 당일(simDate) 봉 | **한 번도 읽지 않는다** — 시가·고가·저가·종가 전부 미사용 |
| 표본 부족 | `past.size() < maPeriod` → `Optional.empty()` = 판단 불가 → **차단하지 않음**(보수적, 기존 동작 유지) |
| 경계 | `previousClose == movingAverage`면 `false`(= '아래' 아님) |

진입 판단은 장중(일봉 근사 10:00)에 일어나므로 당일 종가·당일 종가를 포함한 MA는 미래
정보다. Red-Green으로 실제 검증했다(아래 증거) — `headMap(simDate, true)`로 바꾸면
"당일 종가 극단값" 테스트가 **실패**한다.

## ⚠ 구현 중 발견 — 기존 갭다운 필터는 지금 엔진에서 **아무것도 막지 못한다** (고치지 않고 보고만)

`BacktestIndexRegimeSource.isBearishRegime()`은 `market.dayBar("KOSPI", …)`를 쓰는데,
`BacktestMarketDataService.loadSeries()`는 **`RunConfig.symbols`(매매 종목)만** 메모리에
올린다(`BacktestRunner:83`). KOSPI는 그 목록에 없다 → `dayBar`가 항상 `null` →
`isBearishRegime()`은 **항상 `Optional.empty()`** → `IndexRegimeRule`은 항상 통과.
즉 과거 VB A/B의 "지수 레짐 필터 개선 없음"은 **필터가 실제로 켜진 적이 없었다**는
뜻일 수 있다. 지시대로 **기존 필터는 손대지 않았다**(다른 실험의 회귀 기준).

→ 그래서 새 추세 판정은 `loadSeries`가 아니라 **`CandleHistoryRepository`에서 지수 일봉을
직접 1회 읽어 캐시**한다(`EventStatsBacktester`·`SpilloverStatsBacktester`·
`LowVolCrashBacktester`가 쓰는 기존 패턴 그대로). 명세의 "`BacktestMarketDataService`
조회 메서드 재사용"에서 **의도적으로 벗어난 유일한 지점**이며, 그대로 따랐다면 G1·G2가
G0와 완전히 같은 숫자를 내는 **조용한 무효 실험**이 됐을 것이다.
KOSPI를 `loadSeries`에 넣는 대안은 러너가 지수를 매매 종목처럼 돌리게 되어 기각했다.
캐시는 백필 완료 후 1회 적재라 실행 중 변하지 않는다(결정성 유지). 적재 건수는
`[IndexTrend] 지수 추세 판정용 KOSPI 일봉 캐시 적재: N건 (…~…)` 로그로 찍히며,
**0건이면 필터가 아무것도 막지 않는다는 경고 문구가 같이 나온다** — 실행자는 이 줄을
먼저 확인할 것.

## 적용 지점 — 기존 IndexRegime 필터와 같은 자리(RiskEngine 룰)

`isBearishRegime()` 호출부는 `IndexRegimeRule.validate()` 하나뿐이다. 그 **옆에**
동일 패턴의 `IndexTrendRule`을 새로 두었다 — 기존 룰 본문을 고치면 "갭다운 필터의 의미를
바꾸지 말라"는 제약을 어기게 되고, CLAUDE.md 규칙 3이 새 리스크 룰은 `RiskRule` 구현 +
`@Component`로 넣으라고 못박기 때문이다. `RiskEngine`은 무수정(룰 자동 주입).

- 흐름 불변: `Strategy → Signal → RiskEngine → OrderEngine`. 새 경로 없음, Strategy는 주문 미실행.
- 두 필터는 독립: `RiskEngine.check()`가 룰을 순회하며 하나라도 거부하면 거부(= OR).
- paper/real: `NoOpIndexRegimeSource`는 `isBelowTrend` 기본 구현(empty)을 상속 → 필터를
  켜도 아무것도 막지 않는다. 게다가 기본값이 OFF다.

## 새 모드 `regime-lab`

고정 조건은 §14.3 스트레스 실행과 동일: 진입=MA 정배열 / 출구=P3(ATR1.0·타임컷OFF·20일·
arm1%/trail3%) / 사이징=RR1(0.005·동시5) / 유니버스=`candidateSymbols`(54) /
창=`stress-from`~`stress-to`(2020-01-01~2026-07-21, 6/3/3 워크포워드 24창).

| 라벨 | indexTrend |
|---|---|
| `G0 필터OFF(회귀 앵커)` | OFF |
| `G1 지수 MA120 이탈 시 진입금지` | ON, maPeriod=120 |
| `G2 지수 MA200 이탈 시 진입금지` | ON, maPeriod=200 |

- 각 프로필: 설정 → `walkForwardEngine.run(symbols, from, to, List.of(0.5), null)` →
  `reportWriter.judge(...)` → `ExitLabRow` 수집.
- 루프 후 복원: `indexTrend` OFF·maPeriod 200, `resetExitProfile()`,
  `riskLimits` 상수(`RISK_FRACTION_PER_TRADE`/`MAX_POSITION_COUNT`) 복원.
- 백필·로그는 기존 `prepareCandidateUniverse("regime-lab", stressFrom, stressTo)` 재사용.
- 리포트: `writeRegimeLabReport` → `writeComparison` 재사용, **기준선 yml 미기록**
  (`baselineOnPass=false`). 파일: `logs/backtest/REPORT-REGIMELAB-MA-P3-RR1-{일시}.md`.
- 판독 지침에 회귀 앵커(§14.3 RR1 1591건·PF 1.26·MDD 33.5%)·MDD와 2022 창 우선·
  **과필터 경고**·선견편향 차단·"합격해도 실전 아님(ADR-001 + 게이트 G2)"를 넣었다.

### 로그 (`[RegimeLab]`, 한국어)
시작 배너(고정 조건·창과 candidate 창 대비·윈도우 수·종목 수·프로필 목록·총 런 수·
회귀 앵커 경고) → 프로필별 시작(실제 적용된 필터 상태를 홀더에서 다시 읽어 출력) →
완료(경과초 + `summaryLine()` + 합격/불합격 + 불합격 사유) → **2022 검증 창별 PF·수익률·건수**
→ 종료 요약 표 + 과필터 경고 + 리포트 절대경로.

> 2022 창 로그를 추가한 이유: `writeComparison`은 **합격** 프로필의 창별 표만 싣는데
> (§14.3 판정에서 지적된 공백), 이 실험의 핵심 판정 칸이 바로 2022 창이다.
> 리포트 파일은 무수정으로 두고 로그로만 보완했다.

### 실행 커맨드
```
.\gradlew.bat bootRun --args="--spring.profiles.active=backtest --backtest.mode=regime-lab"
```
(창을 바꾸려면 `--backtest.stress-from=…`/`--backtest.stress-to=…`. 기본값이 §14.3과
같으므로 오버라이드 없이 실행하면 G0가 곧바로 회귀 앵커가 된다.)

## 검증 증거 (이번 세션 직접 실행, 2026-07-25)

```
[검증 증거] — 컴파일
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build gradle compileJava compileTestJava --console=plain
종료 코드: 0
결과: BUILD SUCCESSFUL (에러 0)

[검증 증거] — Red 확인 (선견편향 차단선을 되돌렸을 때 테스트가 실제로 잡는가)
명령: gradle test --tests com.trading.backtest.BacktestIndexRegimeSourceTest --console=plain
      (belowTrend의 headMap(simDate, false) → headMap(simDate, true)로 당일 포함)
종료 코드: 1
결과: 6개 중 2개 통과, 실패 4
  - 선견편향 차단 — 당일 종가를 극단값으로 바꿔도 판정이 변하지 않는다 FAILED (line 49)
  - 표본이 MA 기간보다 적으면 empty FAILED
  - 전일 종가가 MA 아래면 true, 위면 false FAILED
  - MA 기간은 최근 N개만 본다 FAILED
→ 판정: Red 확인 — 테스트가 선견편향을 실제로 잡는다

[검증 증거] — Green (차단선 복구 후)
명령: gradle test --tests com.trading.backtest.BacktestIndexRegimeSourceTest
      --tests com.trading.risk.FilterRulesTest --tests com.trading.backtest.BacktestDataPropertiesTest
종료 코드: 0
결과: BacktestIndexRegimeSourceTest tests=6 failures=0 errors=0
      FilterRulesTest$IndexTrend tests=6 failures=0 errors=0
      FilterRulesTest$IndexRegime tests=2 / $EntryWindow tests=2 / $Trailing tests=4 (전부 0 실패)
      BacktestDataPropertiesTest tests=15 failures=0 errors=0

[검증 증거] — 전체 회귀
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build gradle test --console=plain
종료 코드: 0
결과: BUILD SUCCESSFUL · 61개 테스트 클래스 406개 중 406개 통과, 실패 0, 에러 0, 스킵 0
      (기존 391 + 신규 15 = 406 — 기존 테스트 회귀 없음)

파일: docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml md5 852a29da9fd5d40d8be9b5c2f2904ba0 (무변경)
```

신규 15케이스: 선견편향 차단(당일 극단값 무시 + 하루 뒤로 밀면 뒤집히는 대조), 표본 부족
empty, 경계(종가==MA는 아래 아님), 추세 위/아래, MA4 vs MA6 판정 분기, 비정상 입력 /
IndexTrend 기본값(OFF·200), OFF 통과, ON 거부·미판정 통과, maPeriod 전달, 갭다운과 독립,
`isBelowTrend` 기본 구현 empty / stress 창 기본값·독립성·yml 바인딩.

## 미해결 / 우려

1. **기존 갭다운 필터 무력화(위 발견)** — 고치지 않았다. `IndexRegimeRule`을 실제로 쓰려면
   지수 시계열 경로를 새 방식으로 바꿔야 하지만, 그건 다른 실험의 회귀 기준을 건드리는
   일이라 **사람 판단 대상**이다.
2. **필터가 얼마나 막을지는 실행 전엔 모른다.** MA200/MA120 이탈 구간이 길면 트레이드
   수가 크게 줄어 상승장 수익까지 잘릴 수 있다(과필터). 로그·리포트 양쪽에 경고를 넣었지만
   판단은 숫자를 본 뒤에.
3. **MA 워밍업 구간**: `candle_history` 저장 하한이 2019-04-01이라 2020-01 시작 시점의
   MA200은 표본이 모자랄 수 있다(그 구간은 '판단 불가'로 **막지 않음** = G0와 동일 동작).
   즉 창 초반은 필터 효과가 약하게 나올 수 있다 — 결과 해석 시 유의.
4. **지수 캐시는 KOSPI 하나만** 본다(KOSDAQ 종목도 KOSPI 추세로 판정). 단순화이며,
   KOSDAQ 분리는 이번 범위 밖.
5. **`BacktestOrchestrator`가 764줄**로 800줄 상한에 가까워졌다. 다음에 모드를 더 붙이려면
   랩별 클래스 분리가 필요하다(이번엔 범위 밖이라 손대지 않았다).
6. 리포트 실물 미확인 — 백테스트를 돌리지 않았으므로 `REPORT-REGIMELAB-*.md` 렌더링은
   기존 `writeComparison` 경로 재사용이라는 근거로만 담보된다.
