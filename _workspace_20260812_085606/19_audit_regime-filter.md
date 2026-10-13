# 19_audit_regime-filter — 지수 추세 레짐 필터(IndexTrendRule + regime-lab) 감사

감사일 2026-07-25 · 대상: 세션 변경분(mtime 00:27~00:37) · **소스만 읽음**
(다른 에이전트가 regime-lab 실행 중 — TRADING_BUILD_DIR 공유 때문에 Gradle 미실행.
선견편향은 알고리즘 원문을 독립 복사해 별도 JVM에서 검산했다.)

## 결론 (한 줄)

**선견편향 없음**(검산 9종 통과) · **기존 갭다운 필터 무력화 = 사실**(첫 B-3 실행부터 줄곧) —
CRITICAL 0건 · HIGH 1건(과거 판정 근거 무효, 신규 실험과는 무관) → **regime-lab 결과는 해석 가능**.
단 실행 로그의 `[IndexTrend] … 캐시 적재: N건`을 리포트보다 먼저 볼 것(N=0이면 G1·G2는 G0의 복사본).

## 이전 감사(1~18)의 CRITICAL·HIGH 현재 상태

| 출처 | 항목 | 상태 |
|---|---|---|
| 6_audit_reproducibility | 부동 날짜 판정 창 | 해소 유지 (candidate-from/to 고정, 이번에도 무변경) |
| 6_audit_reproducibility | 기준선 yml 조용한 덮어쓰기 | 해소 유지 (write-baseline=false, regime-lab은 baselineOnPass=false) |
| 2_audit_cost-lab (F-C1) | 라벨과 실제 적용값 괴리 | 해소 유지 — regime-lab도 홀더 재독 패턴 채택(runRegimeProfile 로그) |

---

## 1. 선견편향 검산 — 통과

판정 함수는 `BacktestIndexRegimeSource.belowTrend()` 한 곳뿐이고(`:85-98`),
`isBelowTrend()`(`:75-77`)가 유일한 호출자다.

- ① `:89` `closes.headMap(simDate, false)` — 상한 **미포함** 확인.
- ② 표본 `:92` `past.subList(size-maPeriod, size)`(past는 이미 date<simDate) ·
  비교 종가 `:96` `window.get(size-1)` = **전일 종가**. 당일 봉의 시/고/저/종은 이 함수에서 미참조.
- ③ 하루 밀림 없음: `DailyBarSimulator.java:112` `clock.setTo(date,10:00)` → `:124` `riskEngine.check(buy,…)`.
  룰이 읽는 `LocalDate.now(clock)`은 판정 대상 봉의 그 날짜. `BacktestRunner.java:98`도 동일 날짜 고정.
- ④ 캐시는 1990~2999 전 구간(미래 포함) 적재 — 잘라내기 책임은 `belowTrend` 단일 지점.
  필드는 private·unmodifiableNavigableMap·유일 호출자 → 현재 안전(LOW-1로 기록).
- ⑤ 테스트 동어반복 아님: `BacktestIndexRegimeSourceTest.java:44-52`(당일만 극단값 → 불변) +
  `:56-57`(하루 뒤를 simDate로 주면 그 폭락일이 '전일'이 되어 true로 뒤집힘) 대조군 존재.

### 독립 검산 (원문 복사 후 별도 JVM 실행)

| # | 입력 | 기대 | 실측 |
|---|---|---|---|
| 1 | 종가 0,10,…,140 / simDate=D10 / MA5 (표본 D5~D9, MA=70, 전일=90) | false | Optional[false] |
| 2 | 당일 D10만 −999999 | 불변 | Optional[false] |
| 3 | 당일 D10만 +999999 | 불변 | Optional[false] |
| 4 | 미래 D11~D14 전부 −999999 | 불변 | Optional[false] |
| 5 | 전일 D9=0 | 뒤집힘 | Optional[true] |
| 6 | 당일 봉 없음(휴장) | 검산1과 동일 | Optional[false] |
| 7 | 표본 5개 / 4개 (MA5) | present / empty | Optional[false] / empty |
| 8 | 전일종가 == MA | false | Optional[false] |
| 9 | **대조군** headMap(…, true) | 뒤집혀야 | 전일종가 −999999 · MA −199939.8 → below=**true** |

→ 2·3·4 통과 + 9(대조군) 뒤집힘 = `headMap(simDate,false)`가 실제로 하중을 받는 차단선.
**판정: 선견편향 없음.**

---

## 2. 구현자의 '발견' 검증 — **사실**. 근거 체인 전부 확인

| 단계 | 근거 |
|---|---|
| 인메모리 시리즈는 loadSeries에서만 채워짐 | `BacktestMarketDataService.java:48`(필드) · `:59-69`(유일 쓰기, 첫 줄 series.clear()) |
| loadSeries에 넘기는 건 매매 종목뿐 | `BacktestRunner.java:83` market.loadSeries(config.symbols(), …) |
| symbols는 전 경로에서 KOSPI를 못 얻음 | `BacktestOrchestrator.java:118-119`(targetSymbols→유동성필터) · `:129` · `:215` · `WalkForwardEngine.java:96,109`(그대로 전달) · lab 모드는 candidateSymbols 54종목(KOSPI 없음) |
| targetSymbols에도 없음 | `CandleBackfillService.java:57-61` = 유니버스 활성 ∪ backtest.symbols(기본 6종목). KOSPI는 별도로 `:92-94`에서 **DB에만** 적재 |
| dayBar("KOSPI",…)는 항상 null | `BacktestMarketDataService.java:95-98` series.get(…)==null → null |
| 갭다운 판정은 항상 empty | `BacktestIndexRegimeSource.java:61-63` today==null → empty |
| 룰은 항상 통과 | `IndexRegimeRule.java:29-32` .orElse(RiskResult.pass()) |

**언제부터인가(추가 검증)**: `BacktestIndexRegimeSource`는 커밋 e1d96aa 1회가 전부이고,
`BacktestRunner`의 모든 과거 버전(e1d96aa·02b0f8b)에서 loadSeries(config.symbols(), …) 동일,
`targetSymbols()`도 과거 버전 모두 동일 → **B-3 최초 실행(2026-07-11)부터 줄곧 무발동**.

**파급**
- `docs/BACKTEST-DESIGN.md:174` "지수 레짐(갭다운 금지)은 개선 없음 → 기각" — **근거 무효**.
  개선이 없던 게 아니라 필터가 한 번도 켜진 적이 없다(G0와 동일 실행이었을 것).
- `docs/BACKTEST-DESIGN.md:424` §14 지렛대 ⓒ "FilterProperties.IndexRegime 훅 존재" —
  훅은 있었으나 엔진에서는 죽어 있었다는 사실을 병기해야 한다.
- **돈은 새지 않았다**: paper는 이 필터를 켜지 않는다(`application-paper.yml:26-32`는
  trailing-stop·disclosure-cooldown만 ON) → CRITICAL 아님, **HIGH**(판정 근거 무효).
- **신규 실험은 함정을 피했다**: `IndexTrendRule`은 market이 아니라 CandleHistoryRepository를
  직접 읽고(`BacktestIndexRegimeSource.java:115-118`), 저장 키도 백필과 동일
  (kospiStorageCode="KOSPI", `CandleBackfillService.java:93`) → 경로 일치 확인.

---

## 3~5. 항목별 판정표

| 심각도 | 항목 | 위치 | 내용 | 근거 |
|---|---|---|---|---|
| **HIGH** | 과거 판정 근거 무효 | `BacktestIndexRegimeSource.java:61-63` · `BacktestRunner.java:83` · `BacktestMarketDataService.java:59-69` | 갭다운 지수 레짐 필터가 백테스트에서 항상 무발동 — §7 "지수 레짐 기각"은 필터가 켜진 적 없는 실행의 결론. 미해소(의도적 미수정: 다른 실험의 회귀 기준) | `docs/BACKTEST-DESIGN.md:174`, git 이력 e1d96aa/02b0f8b |
| MEDIUM | 필터 발동 계량 부재 | `BacktestReportWriter.java:217-239` · `BacktestIndexRegimeSource.java:119-121` | 차단 건수·'판단 불가' 건수를 아무 데도 안 남긴다. G1≈G0일 때 "필터가 안 들었다"와 "데이터가 없었다"를 리포트만으로 구분 불가. 완화는 캐시 적재 로그 1줄뿐(0건이면 경고) | 무효 실험·과필터 오독 위험 |
| MEDIUM | allFiltersOff 미갱신 | `BacktestOrchestrator.java:289-297` | 새 indexTrend가 목록에 없다. regime-lab은 프로필 루프에서 매번 명시 설정 + 종료 시 명시 OFF라 현재는 안전하나, 향후 랩이 "allFiltersOff=전부 OFF"를 가정하면 조용히 켜진 채 돈다 | 회귀 앵커 보존 원칙 |
| MEDIUM | real 프로필 빈 부재(선존) | `NoOpIndexRegimeSource.java:13` @Profile("paper") | real에는 IndexRegimeSource 구현체가 없다. 신규 IndexTrendRule이 같은 의존을 하나 더 추가 → real 기동 실패 경로 증가. **실전 전환은 사람 게이트 G2 선행 + 이 빈 추가 선행** | 선존 결함(IndexRegimeRule 동일), 이번 변경이 악화만 |
| LOW | 캐시에 미래가 담김 | `BacktestIndexRegimeSource.java:36-37,109-125` | 잘라내기는 belowTrend 단일 지점(private·불변맵·유일 호출자)이라 현재 안전. 향후 캐시 직독 판정이 생기면 선견편향 재유입 | 방어적 설계 |
| LOW | 매도 통과가 미고정 | `IndexTrendRule.java:32` | `if (!signal.isBuy()) return pass()`는 있으나 테스트 없음. 회귀 시 `DailyBarSimulator.java:241`의 매도가 거부돼 포지션이 갇힌다 | FilterRulesTest IndexTrend 6케이스에 매도 케이스 없음 |
| LOW | MA200 워밍업 부족 | `application-backtest.yml:44` backfill-from 2019-04-01 | 2020-01-01 이전 거래일 ≈187 < 200 → G2는 2020년 1월 말까지 '판단 불가'(미차단, G0와 동일 동작). MA120은 영향 없음. 2020-03 코로나·2022 전 구간은 정상 판정. 리포트엔 정성 문구만(`BacktestReportWriter.java:236`) | 평일 197일 − 공휴일 ~10 |
| LOW | 지수는 KOSPI 하나 | `BacktestIndexRegimeSource.java:113` | KOSDAQ 종목도 KOSPI 추세로 판정(구현자 자인, 범위 밖) | 단순화 |

### 깨끗한 항목 (근거 병기)

| 항목 | 판정 | 근거 |
|---|---|---|
| 규칙 1·2 흐름 | 불변 | 신규 코드에 OrderEngine 참조 없음. 진입은 `DailyBarSimulator.java:116→124→129` |
| 규칙 3 RiskEngine 무수정 | OK | `git diff …/RiskEngine.java` 빈 출력. `IndexTrendRule.java:19-20` @Component implements RiskRule |
| 규칙 4 인터페이스 호환 | OK | `IndexRegimeSource.java:29-31` default. NoOp(paper)는 미오버라이드 → empty 상속. 함수형 인터페이스성 유지(FilterRulesTest의 람다 구현) |
| 규칙 5 청산 상태머신 | 무관 | LiquidationService·LiquidationPhase 미변경, 새 플래그 없음 |
| 기존 IndexRegimeRule 본문 | 무수정 | git diff 빈 출력 |
| 기본 OFF(이중 차단) | OK | `FilterProperties.java:82-83` false/200 · application-paper.yml에 index-trend 키 없음 · paper 소스는 NoOp |
| 프로필 격리 | OK | 신규 코드에 알림/주문 경로 없음. `TradingEventListener:28 @Profile("!backtest")` 무변경 |
| 지갑 칸 backtest 유출 | OK | application-backtest.yml에 bucket 키 없음(기본 OFF) |
| 비밀키 | OK | 변경 파일에 하드코딩 없음. yml은 ${KIS_APPKEY}/${KIS_SECRETKEY} |
| 기간 설정 | OK | candidate-from/to·crash-vol-from/to 값 무변경, stress-from/to만 신설(yml:127-128) |
| 기준선 yml 무변경 | OK | md5 852a29da9fd5d40d8be9b5c2f2904ba0 · mtime 2026-07-23 01:20(오늘 아님) · trades 780 · PF 1.959 · period 2023-07-22~2026-07-21 |
| regime-lab이 기준선 미기록 | OK | `BacktestReportWriter.java:238` writeComparison(…, false, null) → `:491-500` 로그만 |
| 미신고 파일 변경 없음 | OK | mtime 00:27~00:37 = 신고된 9개 파일과 정확히 일치. BacktestRunner·DailyBarSimulator·WalkForwardEngine·RiskEngine·IndexRegimeRule·기준선 yml 전부 미포함 |
| 과필터 오독 방지 | OK | `BacktestReportWriter.java:437-444`가 **불합격 포함 전 프로필**의 트레이드·PF·손익비·기대값·MDD·평균보유일을 싣는다 + 판독지침 `:232-234` 과필터 경고 + judge의 트레이드≥100(`:92-94`) + 종료 로그 요약 |
| G0 회귀 앵커 명시 | OK | 배너 로그·판독지침 `:225-227`에 §14.3 RR1(1591건·PF 1.26·MDD 33.5%) 미재현 시 실행 무효 명시 |

### 판단 보류 (근거 양쪽 병기)

- **writeComparison·judge·writeBaseline·applyExitProfile·runRiskLab의 라인 단위 무수정 여부**:
  두 파일은 오늘 수정됐고(00:30/00:31) 세션 시작 스냅샷이 없어 HEAD 대비 diff로는 이전 세션
  변경분과 구분되지 않는다. → 간접 근거로 **실질 무영향**으로 본다: writeComparison(`:413-502`)에
  regime 특화 분기가 전혀 없고(rows 범용 처리), judge(`:88-111`)는 §4 임계선 그대로,
  applyExitProfile/runRiskLab에도 regime 관련 코드가 없다. 확정하려면 cost-lab/risk-lab의
  C0·RR0 회귀 앵커 재현으로 확인.

---

## 최종 집계

**CRITICAL 0건 · HIGH 1건 · MEDIUM 3건 · LOW 4건**

- HIGH 1건은 이번 구현의 결함이 아니라 **기존 엔진의 결함**이며, regime-lab 결과의 유효성에는
  영향이 없다(새 판정은 리포지토리 직독 경로).
- **regime-lab 결과는 해석 가능**. 판독 순서: ① `[IndexTrend] … 캐시 적재: N건`(N=0이면 실행 무효)
  → ② G0가 §14.3 RR1(1591건·PF 1.26·MDD 33.5%)을 재현하는가(미재현 시 실행 무효)
  → ③ 그다음에야 G1·G2의 MDD·2022 창. 트레이드 수 급감은 과필터로 읽을 것.
- **실전 전환은 어떤 결과가 나와도 별개**: ADR-001(다일 보유) 재논의 + 게이트 G2 사람 승인 선행,
  추가로 real 프로필 IndexRegimeSource 빈 부재(MEDIUM-3) 해소 전에는 기동조차 불가.
- 후속: HIGH-1은 코드 수정이 아니라 **문서 정정(사람 판단)** 사안 —
  `docs/BACKTEST-DESIGN.md:174`(§7)·`:424`(§14 지렛대 ⓒ)에
  "해당 A/B는 필터 무발동 상태에서 측정됨 — 결론 보류"를 병기할 것.
