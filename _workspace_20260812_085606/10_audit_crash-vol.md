# 10_audit_crash-vol — 저변동성 급락장 측정 실험 감사

감사일: 2026-07-24 · 대상: `crash-vol` 모드(측정 실험, 리포트 전용) · 감사자: risk-auditor

## 이전 CRITICAL·HIGH 현황 (재감사 추적)

| 출처 | 항목 | 현재 상태 |
|---|---|---|
| `2_audit_cost-lab.md` / `6_audit_reproducibility.md` | cost-lab·재현성 지적 | 이번 감사 범위 밖(코드 mtime 07-23 이전, crash-vol이 건드리지 않음) — 상태 불변 |
| 신규 | 아래 HIGH 2건 | 미해소 |

## 읽은 파일

- `src/main/java/com/trading/backtest/LowVolCrashBacktester.java` (신규 305줄, 전체)
- `src/test/java/com/trading/backtest/LowVolCrashBacktesterTest.java` (신규 202줄, 전체)
- `src/main/java/com/trading/backtest/BacktestReportWriter.java` (diff + 291~348 신규 구간)
- `src/main/java/com/trading/backtest/BacktestOrchestrator.java` (diff + 96~200, 415~610)
- `src/main/java/com/trading/backtest/BacktestDataProperties.java` (diff), `src/main/resources/application-backtest.yml` (diff)
- 대조: `SpilloverStatsBacktester.java:21~38, 60~200`, `EventStatsBacktester.java:201~216(Quantiles)`
- `docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml`, `_workspace/9_impl_crash-vol.md`, CLAUDE.md 아키텍처 규칙

## 항목별 판정

| # | 감사 항목 | 판정 | 근거 |
|---|---|---|---|
| 1 | 선견편향(look-ahead) | **없음** | 앵커 감지 `LowVolCrashBacktester.java:135-144`는 `closes[i-10..i]`만, 변동성 `:203`은 `subList(si-20, si+1)`(앵커일 종가까지=진입 시점 정보집합), 전방수익률 `:170-176`은 `idx→idx+h`만. 미래 캔들 유입 경로 없음 |
| 2 | 표본 접기(이벤트 단위) | **정상** | `:217-227` 이벤트당 버킷 중앙값 1개만 적재, `:233` `events = lowMed.size()` = 이벤트 수. 테스트 `LowVolCrashBacktesterTest.java:144-157`이 9종목→n=1로 못박음. Spillover 정본(`SpilloverStatsBacktester.java:26-31`)과 동일 사상 |
| 3 | 기존 모드·결과 불변 | **불변** | crash-vol 세션이 만진 공유 파일은 Orchestrator·ReportWriter 2개(mtime 05:34)뿐, 나머지 엔진/전략 파일 mtime은 07-21~07-23. 분기 `:184-188`은 다른 모드 뒤·VB 기본블록 앞에 순수 가산 후 즉시 return, 파라미터 홀더 변경 0. `writeComparison`·`judge`·`writeReport`·`writeBaseline` 무수정 |
| 4 | 거버넌스 기준선 미기록 | **정상** | `writeBaseline` 호출부는 Orchestrator 250·363, ReportWriter 434뿐 — crash-vol 경로 미도달. `writeLowVolCrashReport`(291-343)는 md만 기록. 기준선 yml mtime 2026-07-23 01:20(무변경), 내용 780/1.959/2023-07-22~2026-07-21 확인 |
| 5 | 프로필 격리·비밀키 | **정상** | `:37-38` `@Component @Profile("backtest")`. 의존은 `CandleHistoryRepository`·`BacktestDataProperties`뿐(주문·텔레그램·리스크 룰 무관), `@Scheduled` 없음, 하드코딩 키 없음 |

## 지적 사항

| 심각도 | 항목 | 위치 | 내용 | 근거 |
|---|---|---|---|---|
| HIGH | 변동성 창이 급락 구간과 겹침(측정 대상 ≠ 질문) | `LowVolCrashBacktester.java:203`(+`:32,:52` 주석) | 변동성 창 `si-20..si`의 뒤쪽 10일이 앵커 정의(트레일링 10일 급락) 구간과 겹치고 앵커일 자체 수익률도 포함. "급락 **직전까지** 저변동성"이 아니라 "급락 국면 포함 20일간 덜 흔들린"을 잰다 → 저/고 버킷이 사실상 "이번 급락에서 덜/더 맞은 종목"으로 갈려 이후 반등 비대칭이 '저변동성 효과'로 오독될 수 있다 | 임무 §1 질문 정의, 주석 `:32`("앵커일까지") vs `:52`("앵커일 직전") vs `9_impl_crash-vol.md`("직전 20거래일") 불일치. **판단 보류 병기**: 진입가가 앵커 종가(`:175`)라 정보집합은 일치 — 선견편향은 아니며 의도된 설계일 수 있음. 저비용 해소: 급락 이전 창(`si-30..si-10`) 변동성 칸을 나란히 추가 |
| HIGH | 쿨다운(20) < 최대 호라이즌(60) → 이벤트 전방창 중첩 | `LowVolCrashBacktester.java:50,59,140` | 앵커가 20거래일 간격이면 D+60 전방창이 최대 40일 겹쳐 이벤트 중앙값들이 독립이 아님 → D+20(경계)·D+60의 n이 유효표본을 과대표시. 클래스 주석 `:33`·`:49`는 "쿨다운으로 창 독립성 확보"라고 단언 | 표본 접기와 별개의 시계열 중첩 문제. 해소: 호라이즌별 쿨다운(≥h) 적용 또는 D+60 행에 "중첩 창 — 독립 표본 아님" 경고 병기 |
| MEDIUM | 앵커 탐지 창이 선언 기간(`to`)을 넘어감 | `LowVolCrashBacktester.java:257` + `BacktestOrchestrator.java:183` | KOSPI도 `to.plusDays(120)`까지 로드 → `to` 이후 앵커가 탐지되어 `thresholdCounts` 표(`ReportWriter:308-313`)에 계상되고, 데이터가 쌓일수록 `to` 이후 앵커가 D+5부터 통계에 편입 → "한 줄이 항상 같은 결과"(주석 `:183`)라는 재현성 주장이 성립하지 않음 | 여유 데이터는 전방수익률에만 필요. 앵커 탐지는 `to`까지로 잘라야 함 |
| MEDIUM | 표본 0인데 `+0.00%`로 표시 | `BacktestReportWriter.java:345-348`, `EventStatsBacktester.java:202` | javadoc은 "빈 표본 n=0이면 `-`"라고 적었으나 코드에 분기 없음. 앵커 0건이면 `Quantiles.EMPTY`(중앙값 0)가 `+0.00%`로 찍혀 "측정했는데 차이가 없다"로 오독될 수 있음 | 이벤트 수가 적을 위험이 실행 전 최대 우려(`9_impl_crash-vol.md` 우려 1)라 표시 정직성이 중요 |
| MEDIUM | 유니버스 선택 편향(생존·유동성) | `BacktestOrchestrator.java:185`, `BacktestDataProperties.java:75-87` | 후보 54종목은 §14.1에서 오늘 기준으로 스크리닝된 생존 대형주 → 저변동성 버킷에 유리한 횡단면 편향 | 리포트 판독지침 ③(`ReportWriter:333-334`)에 **공시되어 있음** — 무효 사유는 아니나 결론 문장에 반드시 동반 표기 |
| LOW | 중(mid) 버킷 n 미표기 | `BacktestReportWriter.java:318` | 같은 행의 `이벤트 n`은 저/고/KOSPI 기준(`LowVolCrashBacktester.java:222,226`)이고 중 버킷은 다를 수 있는데 표에 n이 없음 | "(참고)중" 라벨로 완화되어 있음 |
| LOW | 종목별 거래일 인덱스로 D+N 계산 | `LowVolCrashBacktester.java:242` | 거래정지·결측일이 있는 종목은 D+N이 KOSPI의 D+N과 달력상 어긋남 | `SpilloverStatsBacktester.java:190-199`도 동일 관례 — 코드베이스 선례와 일치 |
| LOW | 문서 드리프트 | `BacktestOrchestrator.java:420-423`, `application-backtest.yml`(candidate 블록 주석) | "cost-lab·risk-lab 전용/두 모드만 읽음"이라 적힌 고정 후보 설정을 crash-vol도 사용 | 유지보수 혼선 |

## 검증 증거 (직접 실행, 2026-07-24 22:31~22:32)

```
명령: gradle test --tests com.trading.backtest.LowVolCrashBacktesterTest --console=plain
결과: BUILD SUCCESSFUL · TEST-*.xml tests=10 skipped=0 failures=0 errors=0
명령: gradle test --console=plain   (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build)
결과: BUILD SUCCESSFUL · 59클래스 375테스트 · failures=0 errors=0 skipped=0 (전 XML 이번 실행분)
파일: docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml mtime 2026-07-23 01:20 · trades 780 / PF 1.959 / 2023-07-22~2026-07-21 (무변경)
```

## 결론

**CRITICAL 0건 · HIGH 2건.** 돈이 새는 경로·실계좌/paper 영향·기준선 오염은 없다
(리포트 전용·프로필 격리·기준선 미기록 모두 확인). 선견편향 없음, 표본 접기 정상.

- **실행 가능 여부**: 엔진 실행 자체는 안전하므로 **실행은 가능**하나, HIGH 2건이
  결과 해석을 왜곡한다 → **HIGH 2건 해소 전에는 이 실험의 숫자로 어떤 결론(특히
  "저변동성이 급락 후 더 낫다")도 확정하지 말 것.** 리포트 배포 시 두 항목을 한계로 병기.
- 실전(`real`) 관련 함의 없음 — 어떤 경우에도 **사람 게이트(G2) 선행**이며 이 실험은
  전략 채택 근거가 아니다.
