# 14. 구현 — 백테스트 캔들 데이터 소급 확장 (2020 코로나 · 2022 금리쇼크)

작성: 2026-07-24 · 담당: trading-implementer
범위: ①KIS API 소급 한계 실측 ②백필 깊이를 판정 창과 분리 ③crash-vol이 확장 데이터를 쓰게

---

## ① KIS 일봉 API 실측 결과 (가장 중요)

**결론: 소급 확장 가능하다. KIS는 2000년까지도 일봉을 준다. 대안 데이터원 불필요.**

실호출로 측정 (모의투자 URL `openapivts...:29443`, 종목 TR `FHKST03010100`,
지수 TR `FHKUP03500100`, 삼성전자 005930 기준, 읽기 전용 — 주문 없음):

| 요청 | rt_cd | 받은 행수 | 실제 반환 구간 | 해석 |
|---|---|---|---|---|
| 2020-01-01 ~ 2020-12-31 | 0 | **100** | 2020-08-05 ~ 2020-12-30 | 1회 호출 상한 100행 |
| 2019-01-01 ~ 2019-12-31 | 0 | **100** | 2019-08-05 ~ 2019-12-30 | 2019 데이터 있음 |
| 2015-01-01 ~ 2015-12-31 | 0 | **100** | 2015-08-06 ~ 2015-12-30 | 2015 데이터 있음 |
| 2010-01-01 ~ 2010-12-31 | 0 | **100** | 2010-08-10 ~ 2010-12-30 | 2010 데이터 있음 |
| 2005-01-01 ~ 2005-12-31 | 0 | **100** | 2005-08-09 ~ 2005-12-29 | 2005 데이터 있음 |
| 2000-01-01 ~ 2000-12-31 | 0 | **100** | 2000-08-01 ~ 2000-12-26 | **2000년까지 조회됨** |
| 2020-03-01 ~ 2020-03-31 (코로나 폭락 단월) | 0 | **22** | 2020-03-02 ~ 2020-03-31 | 거래일 전량 수취 |
| 2026-04-01 ~ 2026-06-30 (대조군) | 0 | 61 | 2026-04-01 ~ 2026-06-30 | 정상 |
| 2020-01-01 ~ 2023-12-31 (4년 통짜) | 0 | **100** | 2023-08-02 ~ 2023-12-28 | 최신 100행만 — 통짜 요청은 위험 |
| **KOSPI 지수** 2020 전체 | 0 | **50** | 2020-10-21 ~ 2020-12-30 | **지수는 상한이 50행** |
| KOSPI 지수 2020-03 | 0 | 22 | 2020-03-02 ~ 2020-03-31 | 코로나 급락 지수 데이터 있음 |
| KOSDAQ 지수 2020-03 | 0 | 22 | 2020-03-02 ~ 2020-03-31 | 있음 |
| 373220(LG엔솔, 2022-01 상장) 2020-03 | 0 | **0** | — | 상장 전은 0행 (오류 아님) |
| 373220 2022-02 | 0 | 18 | 2022-02-03 ~ 2022-02-28 | 상장 후 정상 |

핵심 숫자 세 개:
1. **1회 호출 상한 = 종목 100행 / 지수 50행** (지수가 절반이라는 건 이번에 처음 확인)
2. **소급 한계 = 최소 2000년** — 2020·2022는 여유 있게 커버
3. 통짜 4년 요청은 최신 100행만 주고 `rt_cd=0`(성공)으로 응답한다 →
   **페이지네이션 없이는 "조용한 데이터 구멍"이 확정적으로 발생**

### 페이지네이션 — 이미 있음 (손대지 않음)

`KisCandleHistoryClient.fetchDailyPaged()`가 이미 날짜 커서 역방향 페이지네이션을 한다:
- 응답이 최신→과거 순 → `가장 오래된 행 - 1일`을 다음 커서로 재호출, `from` 도달 시 종료
- `MAX_PAGES = 60`, 호출 간 `THROTTLE_MS = 600` (KIS 레이트리밋 대응)
- 상한이 100이든 50이든 **실제 반환된 최고령 날짜**로 커서를 옮기므로 지수(50행)도 자동 대응

용량 검산 (backfill-from 2019-04-01 기준, 약 7.3년 ≈ 1,800거래일):
- 종목: 1,800 / 100 = **18페이지** < 60 ✔
- 지수: 1,800 / 50 = **36페이지** < 60 ✔ (여유 24페이지)

→ 지시대로 **청킹 추가하지 않았다**. 다만 MAX_PAGES 초과 시 조용히 잘리므로 아래 감지기를 넣었다.

### 불완전 수취 감지 (신규)

`CandleBackfillService`에 gap 단위 검사 추가:
- `weekdaysBetween(from,to)` × `COVERAGE_WARN_RATIO(0.80)` 미만이면 WARN
- `COVERAGE_MIN_WEEKDAYS(20)` 미만의 짧은 증분 gap은 검사 제외 (연휴 헛경보 방지)
- 정상 케이스 검산: 2019-04-01~2023-07-21 평일 1,125일 / 실제 거래일 ≈ 1,055 → 비율 0.94 → 경고 없음
- 잘린 케이스: 평일 1,040일에 100건 → 0.096 → **WARN 발생**
- 상장 전 구간(373220 등)도 WARN이 뜬다 — 메시지에 "상장 전·거래정지면 정상"이라고 명시했다

---

## ② 백필 깊이를 판정 창과 분리

| 파일 | 변경 | 이유 |
|---|---|---|
| `BacktestDataProperties.java` | `backfillFrom`(기본 null) + getter/setter | 저장 하한만 지정 |
| `CandleBackfillService.java` | `rangeFrom()`이 backfillFrom 있으면 그 값, 없으면 기존 공식 | 기본 동작 완전 보존 |
| `application-backtest.yml` | `backfill-from: "2019-04-01"` + 주석 | 2020-01을 워밍업 260일 포함해 판정 가능 |

**`years`(3)·전역 `from`/`to` 계산은 한 줄도 건드리지 않았다.**

### 기존 모드 무영향 보장 근거 (3중)

1. **판정 창이 rangeFrom과 무관** — `BacktestOrchestrator.execute()`의
   `from = LocalDate.now(clock).minusYears(properties.getYears())`는 그대로다.
   `rangeFrom()`은 백필(저장)에만 쓰인다.
2. **조회 상한 고정** — `BacktestMarketDataService:64`가
   `from.minusDays(WARMUP_CALENDAR_DAYS=260) ~ to`로만 읽는다.
   2019-04 캔들을 넣어도 full/smoke/ma-breakout/scalping/exit-lab의 조회 범위 밖이다.
3. **cost-lab·risk-lab은 `candidateFrom/To`(2023-07-22~2026-07-21) 고정** — 손대지 않았다.
   테스트 `backtestYaml_bindsBackfillAndCrashVolWindow`가 candidate 창 불변을 회귀 검사한다.

---

## ③ crash-vol이 확장 데이터를 실제로 쓰게

| 파일 | 변경 |
|---|---|
| `BacktestDataProperties.java` | `crashVolFrom`(2020-01-01) / `crashVolTo`(2026-07-21) 신설 |
| `application-backtest.yml` | `crash-vol-from` / `crash-vol-to` 명시 (부동 날짜 금지 — 재현성) |
| `BacktestOrchestrator.java` | crash-vol 분기가 candidateFrom/To → crashVolFrom/To 사용. 유니버스는 candidateSymbols(54종목) 그대로 |
| `BacktestOrchestrator.java` | `runCrashVol()` 배너에 "사용 창 + candidate 창과 별개 + backfill-from 하한" 3줄 명시 |
| `BacktestOrchestrator.java` | `prepareCandidateUniverse(mode, from, to)` — 기간을 인자로 받게 시그니처 변경 |

> `prepareCandidateUniverse`는 기존에 항상 `candidateFrom/To`를 로그에 찍었다. crash-vol만
> 다른 창을 쓰게 되면서 **로그가 거짓말을 하게 되므로** 기간을 인자로 받도록 바꿨다.
> cost-lab·risk-lab 호출부는 `candidateFrom/To`를 그대로 넘겨 동작·로그 모두 불변.

---

## 백필 실행 커맨드 · 예상 소요 · 주의

```cmd
.\gradlew.bat bootRun --args="--spring.profiles.active=backtest --backtest.mode=crash-vol"
```

- 백필은 idempotent다 — `missingRanges()`가 앞쪽 공백만 계산해 그 구간만 요청한다.
- **예상 소요(백필 부분)**: 종목 54개 + 지수 2개.
  - 종목: 앞쪽 공백 2019-04-01~2023-07-21 ≈ 1,055거래일 → 약 11페이지 × 0.6초 ≈ **7초/종목**
    → 54종목 ≈ **6~7분** (+ HTTP 왕복 시간, 실제로는 10~15분 예상)
  - 지수: 1,055 / 50 = 22페이지 × 0.6초 ≈ 14초 × 2 = 30초
  - **총 백필 10~20분 예상**. crash-vol 계산 자체는 DB 읽기라 수십 초.
- **레이트리밋 주의**: 호출 간 600ms는 `KisCandleHistoryClient`가 이미 넣는다.
  paper 앱(1초 루프)이 동시에 돌면 같은 appkey로 초당 호출이 겹친다 → **백필은 장 마감 후,
  가급적 paper 앱을 멈춘 상태에서** 돌리는 것이 안전하다.
- **로그에서 반드시 확인할 것**: `[Backfill] ⚠ ...` WARN이 뜨는지.
  뜨면 그 종목/구간은 데이터가 잘렸을 수 있다 (상장 전 구간이면 정상).

## 실행 후 반드시 확인할 회귀 앵커

**risk-lab이 780건 / PF 1.959 / 기대값 +1.30% / MDD 9.87% 그대로인가.**

```cmd
.\gradlew.bat bootRun --args="--spring.profiles.active=backtest --backtest.mode=risk-lab"
```
(RR1 하프(0.5R·동시5) 행을 본다. `--backtest.write-baseline`은 붙이지 않는다 — 기본 false)

깨질 수 있는 이론적 경로 하나: risk-lab 판정 창의 시작(2023-07-22) 직전 260일 워밍업 구간에
**지금까지 비어 있던 캔들이 이번 백필로 메워지면** 초기 ATR·이평선 값이 달라져 초기 진입이
바뀔 수 있다. 위 ②의 근거대로 조회 범위는 `2023-07-22 - 260일 = 2022-11-05`부터이고 기존
데이터도 그보다 앞(2022-07경)부터 있어야 정상이므로 **원칙적으로 변화 없음**이지만,
일부 종목이 워밍업 구간까지만 얕게 적재돼 있었다면 값이 바뀔 수 있다.
**숫자가 하나라도 달라지면 즉시 보고 대상**이다(원인을 찾기 전에는 확장 데이터를 신뢰하지 말 것).

---

## 검증 증거

```
[검증 증거 1 — 전체 테스트]
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build gradle test --console=plain
종료 코드: 0
결과: 391개 중 391개 통과, 실패 0, 오류 0, 스킵 0
→ 판정: 통과

[검증 증거 2 — Red-Green (rangeFrom 오버라이드)]
1) 수정 적용 상태: CandleBackfillServiceTest 11/11 통과
2) 오버라이드 3줄 제거 후 재실행:
   명령: gradle test --tests "com.trading.backtest.CandleBackfillServiceTest"
   종료 코드: 1
   결과: 11 tests completed, 1 failed
     - "backfill-from을 설정하면 rangeFrom이 그 값을 그대로 쓴다 (저장 하한만 바뀜)" FAILED
3) 수정 복구 후 전체 재실행: 종료 코드 0, 391/391 통과
→ 판정: 테스트가 실제로 이 변경을 잡는다

[검증 증거 3 — 신규/기존 테스트 개별]
BacktestDataPropertiesTest  12/12 통과 (신규 5 포함)
CandleBackfillServiceTest   11/11 통과 (신규 6 포함)
```

신규 테스트 목록:
- `backfill-from 기본값은 null이다`
- `years 기본값 3은 불변이다 (전역 불변 회귀)`
- `crash-vol-from/to 기본값은 2020-01-01 ~ 2026-07-21이다`
- `crash-vol 창은 candidate 창과 독립이다 (한쪽 변경이 전파되지 않음)`
- `application-backtest.yml이 backfill-from·crash-vol 창을 바인딩한다` (+ candidate 창 불변 회귀)
- `backfill-from 미설정이면 rangeFrom은 기존 공식 그대로다`
- `backfill-from을 설정하면 rangeFrom이 그 값을 쓴다 (rangeTo·years 불변 동시 검증)`
- `소급 확장 시 앞쪽 공백이 backfill-from까지 잡힌다`
- `weekdaysBetween은 양끝 포함 평일 수를 센다`
- `긴 구간을 기대 거래일보다 크게 적게 받으면 불완전 수취로 판정한다`
- `짧은 증분 구간은 연휴로 비어도 불완전 수취로 보지 않는다 (경계 포함)`

---

## 남긴 한계 · 우려

1. **실제 백필은 실행하지 않았다** (지시대로). 위 예상 소요는 페이지 수 × 스로틀 계산값이지
   실측이 아니다.
2. **수정주가**: `FID_ORG_ADJ_PRC=1`로 요청한다(기존 코드 그대로). 액면분할(삼성전자 2018-05 등)
   이전 구간까지 내려가면 조정 일관성 검증이 필요하지만 backfill-from 2019-04는 그 뒤라
   이번 범위에서는 문제가 아니다. **2018년 이전으로 더 당길 경우 반드시 재확인할 것.**
3. **상장 전 구간 WARN이 매 실행 반복된다** — 373220 같은 후발 상장 종목은 앞쪽 공백이
   영원히 메워지지 않아(데이터 자체가 없음) 매 백필마다 1회 호출 + WARN 1줄이 난다.
   기능적 문제는 없지만 로그가 조금 시끄럽다. "빈 응답 확인 마커" 저장은 범위 밖이라 안 했다.
4. **생존 편향(survivorship bias)**: 2020년까지 소급하면 현재 유니버스 54종목은 "2020년 이후
   살아남고 지금 편입된" 종목들이다. 약세장 방어 통계가 낙관 쪽으로 치우칠 수 있다 —
   판정 시 quant가 반드시 감안해야 한다.
5. **MAX_PAGES=60 상한은 손대지 않았다.** 2019-04는 여유가 있으나(종목 18/지수 36페이지),
   2015년 이전까지 당기면 지수가 상한에 닿는다. 그때는 WARN이 뜨도록 감지기를 넣어뒀다.
6. **범위 밖 발견(수정 안 함)**: `backfillExtra()`는 예외를 삼키고 WARN만 남기지만,
   `backfillAll()`은 한 종목 실패가 전체를 중단시킨다. 54종목 장시간 백필에서는
   후자가 더 아플 수 있다 — 보고만 하고 손대지 않았다.
