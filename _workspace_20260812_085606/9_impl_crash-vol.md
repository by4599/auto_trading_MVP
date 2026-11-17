# 9_impl_crash-vol — 약세장 방어 측정 실험 (crash-vol 모드)

측정 실험(전략 채택 아님). 질문: **지수(KOSPI) 급락 직후, 직전까지 저변동성이던 종목이
그 뒤 더 나았는가? "급등"인가 "덜 다치고 안정 회복"인가?** 결과는 리포트 전용 —
레지스트리·거버넌스 기준선 미기록. paper/real/리스크 룰·기존 모드 결과 전부 불변.

## 바꾼/추가 파일

| 파일 | 종류 | 내용 |
|---|---|---|
| `src/main/java/com/trading/backtest/LowVolCrashBacktester.java` | **신규** | 측정 엔진. `@Component @Profile("backtest")` |
| `src/test/java/com/trading/backtest/LowVolCrashBacktesterTest.java` | **신규** | 순수 단위 테스트 10개 (Mockito 없음) |
| `src/main/java/com/trading/backtest/BacktestReportWriter.java` | 수정(추가) | `writeLowVolCrashReport(...)` + `signedPct(...)` 추가. **기존 메서드 무수정** |
| `src/main/java/com/trading/backtest/BacktestOrchestrator.java` | 수정(추가) | 필드/생성자 인자/할당 1줄씩 + `crash-vol` 모드 분기 + `runCrashVol(...)` + `signedPct(...)`. **기존 모드 분기·로직 무수정** |

> 참고: `git diff --stat`이 두 파일에 큰 숫자를 보이는 것은 **내 작업 이전부터 있던 미커밋
> 변경분**(세션 시작 시 이미 `M` 상태) 때문이다. 내 추가분은 위 표대로 순수 가산이며 기존
> 라인은 건드리지 않았다.

## 설계 요약 (명세대로)

- **급락 앵커**: KOSPI 일봉에서 트레일링 10거래일 지수수익률 ≤ 임계(기본 -7%)인 날.
  한 앵커 채택 후 20거래일 쿨다운(겹치는 창 독립성). 임계 3종(-5/-7/-10%)별 이벤트
  개수를 세어 리포트에 남긴다(검정력 정직성). 본 통계는 -7%.
- **변동성(사후편향 없음)**: 앵커일 직전 20거래일 로그수익률 **모표준편차**. 20일 이력
  없는 종목은 그 이벤트에서 제외.
- **버킷**: 이벤트마다 변동성 오름차순 3등분(저/중/고). 저·고만 비교(중은 참고).
  버킷당 종목 < 3이면 그 이벤트 통째로 스킵(Spillover MIN 커버리지 사상).
- **전방 수익률**: 앵커 종가 대비 D+5/10/20/60. 데이터 모자란 호라이즌 제외.
  **벤치마크**: 같은 호라이즌 KOSPI 전방수익률을 같은 표에 나란히.

## 표본 부풀림 차단 — 이벤트 단위 접기 (핵심)

`SpilloverStatsBacktester`(27~28행 주석)의 정본 사상을 그대로 옮겼다. 한 급락 이벤트
(날짜) 안의 여러 종목 수익률은 **같은 시장 충격을 공유해 강하게 상관**되므로 독립 표본이
아니다. 그래서 `LowVolCrashBacktester.aggregate(...)`는:

1. 이벤트마다 저·고 버킷별로 종목 전방수익률을 모아 **`Quantiles.of(...).median()` 하나로
   접는다**(횡단면 중앙값).
2. 그 **이벤트 중앙값들만** 호라이즌·버킷별 리스트에 쌓고, 마지막에 다시 `Quantiles.of`로
   집계한다. 따라서 결과 `HorizonStat.events`(= `Quantiles.n`)는 **이벤트 수**이지
   (종목 수 × 이벤트 수)가 아니다.
3. 저·고·KOSPI 세 칸의 n을 같게 유지하려고, 한 이벤트가 어떤 호라이즌에 기여하려면
   저·고 버킷 커버리지(≥3)와 KOSPI 전방 데이터가 **동시에** 있어야 한다.

테스트 `aggregate_foldsPerEvent_notPerStock`가 이를 못박는다: 9종목 1이벤트 →
`events()==1`, `lowVol().n()==1`, 저 버킷(3종목 [0.10,0.20,0.30]) 중앙값 **0.20**
(9종목 풀링이면 다른 값). 즉 접힘이 실제로 일어남을 값으로 증명.

## 모드 배선 / 재현성

- `BacktestOrchestrator`에 `"crash-vol".equalsIgnoreCase(properties.getMode())` 분기 신설
  (cost-lab/risk-lab과 동일 패턴). `prepareCandidateUniverse("crash-vol")`로 54종목
  고정 후보 + 고정 창(candidateFrom/To)을 재사용 → `--backtest.mode=crash-vol` 한 줄이
  항상 같은 결과.
- KOSPI 일봉은 `includeKospi=true`라 `backfillAll`이 이미 적재. 없으면 `[CrashVol]`
  경고 로그 후 빈 리포트.

### 실행 커맨드
```
.\gradlew.bat bootRun --args="--spring.profiles.active=backtest --backtest.mode=crash-vol"
```
(실행·해석은 strategy-quant 담당 — 나는 컴파일+단위 테스트까지만.)

## 세부 로그 (`[CrashVol]`, 한국어)
- 시작 배너: 기간·유니버스 수·임계 3종별 앵커 개수·본 통계 앵커 개수.
- 이벤트별: 앵커일 + 저/중/고 버킷 종목 수(커버리지 부족 시 스킵 사유).
- 완료 요약: 호라이즌별 저−고 차 한 줄 표 + 경과초 + 리포트 절대경로.
- 리포트: `logs/backtest/REPORT-CRASHVOL-{yyyyMMdd-HHmm}.md` (기준선 yml 미기록).

## 검증 증거

```
[검증 증거] — 신규 테스트
명령: gradle test --tests com.trading.backtest.LowVolCrashBacktesterTest --console=plain
종료 코드: 0
결과: 10개 중 10개 통과, 실패 0 (TEST-*.xml: tests=10 skipped=0 failures=0 errors=0)
→ 판정: 통과

[검증 증거] — 전체 회귀
명령: gradle test --console=plain
종료 코드: 0
결과: 59개 테스트 클래스, 375개 중 375개 통과, 실패 0, 스킵 0
→ 판정: 통과 (기존 모드/테스트 회귀 없음)
```

테스트 10개: 변동성 모표준편차(정확값 0.05 / 소표본 0), 버킷 3등분(정확 분할 + 값 기준
정렬), 앵커 감지(-8% 1구간→앵커 1개·쿨다운 인접 병합 / 쿨다운 밖 두 번째 앵커 별도),
전방수익률(정확값 / 범위밖·0진입가 null), 표본 접기(이벤트 단위 n / 커버리지 미달 스킵).

## 미해결 / 우려

1. **이 창의 급락 이벤트 수가 적을 위험(가장 큰 우려)**: 2023~2026은 대체로 우호장이라
   -7% 임계 앵커가 손에 꼽을 수 있다. 임계 3종별 개수를 리포트에 그대로 남겨 **낮은
   검정력을 숨기지 않게** 했다. 이벤트가 적으면 방향 참고만 — 통계적 결론 금지(리포트
   판독지침 ④). 앵커가 0~1건이면 저−고 차는 사실상 단일 관측이다.
2. **생존편향**: 후보 유니버스는 오늘 살아있는 대형주라 "저변동성인데 상장폐지된 종목"이
   빠져 저변동성에 유리하게 편향될 수 있다(판독지침 ③).
3. **일봉 근사**: 장중 경로·정확한 진입시각 무시. D+60은 후보창 끝 근처 앵커에서 데이터
   부족으로 이벤트가 줄어든다(호라이즌별 n이 다를 수 있음 — 표에 n 병기).
4. **변동성 정의는 모표준편차(N)**: 표본표준편차(N-1)가 아니다. 상대 순위(버킷 갈림)에는
   영향 없으나 절대 변동성 수치를 다른 곳과 비교할 땐 유의.

---

# 9-B (2026-07-24 추가) — risk-auditor 감사 지적 4건 해소

`10_audit_crash-vol.md`의 **HIGH 2건 + MEDIUM 2건**만 고쳤다. 백테스트는 아직 한 번도
돌리지 않았다(실행·해석은 strategy-quant). 기존 모드(full/smoke/ma-breakout/scalping/
events/exit-lab/risk-lab/cost-lab) 로직·숫자 불변, `writeComparison`·`judge`·`writeReport`·
`writeBaseline` 무수정, `docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml` 무변경(mtime 07-23 01:20 확인).

## 바꾼 파일 (git diff와 1:1)

| 파일 | 변경 성격 | 내용 |
|---|---|---|
| `LowVolCrashBacktester.java` | 로직 변경 | 변동성 창 PRE/DURING 2종화(`VolWindow` 레코드 신설), 쿨다운 20→60, 앵커 탐지 `to` 클램프(`anchorScopeSize` 신설), `CrashVolReport` 필드 교체(`volWindow` 제거 → `List<WindowStats> windows`), 주석 정정, 로그 문구에 창 코드 추가 |
| `BacktestReportWriter.java` | 로직 변경 | `writeLowVolCrashReport` 본문 — 표 1개 → **PRE/DURING 표 2개** 루프, 빈 표본 `-` 렌더링(`signedPct(Quantiles)`·`lowMinusHighText` 신설), 헤더에 창 정의·쿨다운 근거 추가, 판독지침 ⓪·⑥ 추가 및 ④ 보강 |
| `BacktestOrchestrator.java` | **로그만** | `runCrashVol` 완료 로그를 창별 2블록으로 + "PRE가 답" 한 줄. 내 변경으로 호출부가 사라진 `private static String signedPct(double)` 삭제(고아 정리) |
| `LowVolCrashBacktesterTest.java` | 테스트 | 신규 5케이스 추가 + 기존 `aggregate` 호출 2곳의 인자 `3` → `W3`(=`new VolWindow("T3",...,3,0)`) 시그니처 적응. **기존 10케이스의 단언은 한 글자도 안 바꿨다** |

## HIGH-1 — 변동성 창이 급락 구간과 겹침 → 두 정의 병기

`VolWindow(code, label, startBack, endBack)` 레코드를 만들고 슬라이스를
`closes.subList(si - startBack, si - endBack + 1)`(양끝 포함)로 일반화했다.

| 창 | 인덱스 범위(앵커 si 기준) | 종가 개수 | 로그수익률 | 필요 최소 이력 | 재는 것 |
|---|---|---|---|---|---|
| **PRE** | `[si-30, si-10]` (`subList(si-30, si-9)`) | 21 | 20개 = r(si-29)…r(si-10) | `si ≥ 30` | 급락 **전까지** 조용했나 (급락 구간 완전 배제) |
| **DURING** | `[si-20, si]` (`subList(si-20, si+1)`) | 21 | 20개 = r(si-19)…r(si) | `si ≥ 20` | 이번 급락에서 **덜 맞았나** (기존 창 그대로) |

- 급락 구간의 수익률은 r(si-9)…r(si)이므로 PRE의 마지막 수익률 r(si-10)=close[si-10]/close[si-11]은
  급락 시작 **이전** 값이다 → 겹침 0.
- DURING은 폐기하지 않고 병기했다(선견편향 아님 — 정보집합이 앵커 종가까지로 진입 시점과 일치).
- `compute()`가 `VOL_WINDOWS = [PRE, DURING]`를 돌며 **같은 앵커·같은 전방수익률**에
  버킷팅 기준만 바꿔 `aggregate`를 2회 호출 → `WindowStats` 2벌.
- PRE는 `si ≥ 30` 이력이 없는 종목을 **PRE 집계에서만** 제외(DURING은 기존대로) →
  두 표의 이벤트 n이 다를 수 있고, 그 이유를 판독지침 ⑥에 명시했다.
- 리포트에 표 2개 + 판독지침 ⓪ "**두 표가 다르면 물었던 질문의 답은 PRE 표**"를 넣었다.

## HIGH-2 — 쿨다운(20) < 최대 호라이즌(60) → 60으로

`COOLDOWN_DAYS = 20 → 60`, `MAX_HORIZON = 60` 상수 신설. 클래스 주석의 "쿨다운으로 창
독립성 확보" 단언을 사실에 맞게 다시 썼다(왜 60이어야 전방창이 안 겹치는지 + 이벤트가
줄어드는 걸 감수한다는 문장). 파라미터화는 기존대로 `detectAnchorIndices(..., cooldownDays)`
인자로 유지 — 상수는 기본값일 뿐이다.

**이벤트 수 영향 예상(미실행, 추정)**: 앵커 채택은 그리디라 쿨다운을 키우면 이벤트 수는
**단조 비증가**(줄거나 같음). 2023-07~2026-07(약 740거래일)에서 -7% 앵커가 군집(예: 한 하락
국면에 20~40거래일 간격으로 2~3개)해 있었다면 그 군집이 1개로 합쳐진다 → 체감상 기존 대비
**절반 안팎까지 줄 수 있다**. D+60은 창 끝 데이터 부족까지 겹쳐 n이 더 작아진다.
줄면 준 대로 임계 3종별 개수와 함께 리포트에 찍히고, 판독지침 ④가 "낮은 검정력을 숨기지
않는다"고 못박는다. **몇 건이 될지는 실행 전에는 모른다 — 추정치를 결과처럼 쓰지 말 것.**

## MEDIUM-1 — 재현성(`to` 이후 앵커 편입)

`anchorScopeSize(dates, to)`(= `to` 이하 날짜 개수)를 신설하고, `compute()`에서
`kospiCloses.subList(0, anchorScopeSize(...))`를 **앵커 탐지 전용 시계열**로 쓴다.
앞쪽 prefix라 인덱스가 전체 시계열과 그대로 호환되어, 전방 수익률은 여전히 `to` 이후
데이터(`to.plusDays(120)` 로드분)를 정상 사용한다. 즉 `plusDays(120)`은 이제
**전방 수익률 데이터 확보 전용**으로만 작동한다(`loadDaily` javadoc에 명시).
임계치별 카운트(`thresholdCounts`)도 같은 클램프 시계열로 센다.

## MEDIUM-2 — 빈 표본 `-`

`BacktestReportWriter.signedPct(Quantiles)`(n=0 → `"-"`)와
`lowMinusHighText(HorizonStat)`(events=0 → `"-"`)를 추가하고 크래시볼 표 6칸 전부를
이 경로로 바꿨다. 기존 `signedPct(double)`은 그대로 두되 javadoc의 거짓 주장("빈 표본이면 -")을
새 메서드로 옮겼다. 오케스트레이터 완료 로그도 같은 표기를 쓴다.

## 테스트 (신규 5, 총 15케이스)

| 테스트 | 무엇을 못박나 |
|---|---|
| `volWindow_preSliceExcludesCrashSpan` | close[i]=i로 슬라이스 경계 직접 확인 — PRE=[10..30](21개, 급락구간 31~40 미포함), DURING=[20..40] |
| `volWindow_preAndDuringMeasureDifferentThings` | 급락 구간(31~40)에만 ±10% 변동, 그 이전엔 ±0.1% → PRE < 0.01, DURING > 0.05, 두 값 다름 |
| `cooldown_atLeastMaxHorizon_soForwardWindowsDoNotOverlap` | `MAX_HORIZON == max(HORIZONS)`, `COOLDOWN_DAYS ≥ 60`, 합성 급락 3개(30/55/95) → 앵커 [30, 95]이고 인접 간격 ≥ 60 |
| `anchorScope_clampedToDeclaredEnd` | `to` 이후(index 70) 급락 → 클램프 시 앵커 0건 / 클램프 없으면 [70] (한 테스트 안에 red-green 대조) |
| `emptySample_rendersDash` | `Quantiles.EMPTY → "-"`, n>0이면 `"+0.00%"`, events=0 저−고 차 `"-"`, 정상 이벤트는 `"+3.00%"` |

Mockito 미사용(순수 static 헬퍼 + 레코드). `aggregate_foldsPerEvent_notPerStock` 포함
기존 10케이스 단언 무변경·전부 통과.

## 검증 증거 (이번 세션 직접 실행, 2026-07-24 22:44~22:50)

```
[검증 증거] — Red 확인 (수정을 되돌렸을 때 새 테스트가 실제로 잡는가)
명령: gradle test --tests com.trading.backtest.LowVolCrashBacktesterTest --console=plain
      (COOLDOWN_DAYS 60→20 · PRE_WINDOW endBack 10→0 · signedPct/lowMinusHighText의 n=0 분기 제거)
종료 코드: 1
결과: 15개 중 11개 통과, 실패 4
  - 표본이 0이면 리포트에 '-'로 찍힌다 FAILED
  - 급락 구간에만 큰 변동을 심으면 PRE는 낮고 DURING은 높다 FAILED
  - 쿨다운 기본값은 최대 호라이즌(60) 이상 FAILED
  - PRE 창은 급락 구간을 완전히 배제한다 — 슬라이스 경계 FAILED
→ 판정: Red 확인 (앵커 클램프 테스트는 한 테스트 안에서 클램프/무클램프를 대조하므로 항상 통과)

[검증 증거] — Green (수정 복구 후)
명령: gradle test --tests com.trading.backtest.LowVolCrashBacktesterTest --console=plain
종료 코드: 0
결과: TEST-LowVolCrashBacktesterTest.xml → tests="15" skipped="0" failures="0" errors="0"
→ 판정: 통과

[검증 증거] — 전체 회귀
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build gradle test --console=plain
종료 코드: 0
결과: BUILD SUCCESSFUL · 59개 테스트 클래스 380개 중 380개 통과, 실패 0, 에러 0, 스킵 0
      (감사 시점 375개 + 신규 5개)
→ 판정: 통과 (기존 모드·테스트 회귀 없음)

파일: docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml mtime 2026-07-23 01:20 (무변경)
```

## 미해결 / 우려

1. **이벤트 수 급감 위험(최대 우려)**: 쿨다운 60이 정확성 대신 검정력을 깎는다. 앵커가
   1~2건이면 저−고 차는 사실상 단일 관측 — 판독지침 ④·⑤와 임계 3종 카운트로 정직하게
   드러나지만, **실행 결과에서 n이 작으면 "결론 없음"으로 읽어야 한다.**
2. **PRE와 DURING 결과가 갈릴 경우의 해석은 사람 몫**: 코드는 둘을 나란히 찍기만 하고
   어느 쪽이 옳다고 판정하지 않는다(판독지침 ⓪이 "PRE가 물었던 질문"이라고만 안내).
3. **감사 잔여 지적 미해소(범위 밖, 의도적)**: MEDIUM 유니버스 생존편향(판독지침 ③에 공시),
   LOW 중(mid) 버킷 n 미표기, LOW 종목별 거래일 인덱스 D+N 어긋남(Spillover 선례와 동일),
   LOW 문서 드리프트(`BacktestOrchestrator` 420~423행·`application-backtest.yml`의
   "cost-lab·risk-lab 전용" 주석을 crash-vol도 쓴다). 지시받은 4건만 고쳤다.
4. **리포트 실물 미확인**: 백테스트를 돌리지 않아 `REPORT-CRASHVOL-*.md`가 실제로
   어떻게 보이는지는 확인하지 못했다(표 렌더링은 단위 테스트로 셀 문자열까지만 검증).
5. KOSPI 일봉이 없는 조기 반환 경로는 `windows`가 빈 리스트라 리포트에 표가 아예 없다
   (경고 로그 + 앵커 0건 표기는 그대로). 의도된 동작이지만 실행 시 그 화면을 보게 되면
   "데이터 없음"이지 "차이 없음"이 아니다.
