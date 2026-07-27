# 12_audit_crash-vol-refix — 집중 재감사 (HIGH 2 + MEDIUM 2 수정 확인)

감사일: 2026-07-24 22:50~22:57 · 대상: `crash-vol` 수정분 4파일 · 감사자: risk-auditor
선행: `_workspace/10_audit_crash-vol.md`

## 이전 지적 현황 (맨 위)

| # | 지적 | 심각도 | 현재 상태 |
|---|---|---|---|
| HIGH-1 | 변동성 창이 급락 구간과 겹침 | HIGH | **해소** (PRE/DURING 병기, 인덱스 산술 직접 검산 통과) |
| HIGH-2 | 쿨다운(20) < 최대 호라이즌(60) | HIGH | **해소** (COOLDOWN 60, 경계 조건 검산 통과, 주석↔코드 일치) |
| MEDIUM-1 | 앵커 탐지가 `to` 이후로 샘 | MEDIUM | **해소** (`anchorScopeSize` prefix 클램프) |
| MEDIUM-2 | 빈 표본이 `+0.00%` | MEDIUM | **해소** (`signedPct(Quantiles)`/`lowMinusHighText` → `-`) |
| MEDIUM(범위밖) | 유니버스 생존·유동성 선택 편향 | MEDIUM | 미변경 — 리포트 판독지침 ③에 공시(수용) |
| LOW(범위밖) | 종목별 거래일 인덱스 D+N | LOW | 미변경 — Spillover 선례와 동일(수용) |
| LOW | 중(mid) 버킷 n 미표기 | LOW | **해소** (mid도 n=0이면 `-`) |
| LOW | 고정후보 설정 주석 드리프트 | LOW | 미변경(무해) |

## HIGH-1 인덱스 산술 검산 (직접 계산)

정의: `r(t) = ln(close[t]/close[t-1])`. 앵커 si의 급락 판정은
`close[si]/close[si-10] - 1 ≤ -7%` → **급락 구간을 구성하는 일간 수익률은 r(si-9)…r(si)** (10개).

| 창 | 코드 | `slice` (`LowVolCrashBacktester.java:352-354`) | 종가 인덱스 | 로그수익률 | 개수 | 최소 si |
|---|---|---|---|---|---|---|
| PRE | `:79-81` startBack=30(=20+10), endBack=10 | `subList(si-30, si-10+1)` | si-30 … si-10 (21개) | r(si-29) … **r(si-10)** | 20 | 30 (`minIndex`) |
| DURING | `:88-90` startBack=20, endBack=0 | `subList(si-20, si+1)` | si-20 … si (21개) | r(si-19) … r(si) | 20 | 20 |

- ① **겹침 0 확인**: PRE의 마지막 수익률 `r(si-10)`은 급락 첫 수익률 `r(si-9)`보다 하루 앞이다 →
  교집합 공집합. PRE 마지막 종가 `close[si-10]`은 급락률의 **분모(기준가)**일 뿐 급락 이동분이 아니다.
  틈도 없다(딱 맞닿음). **주장대로 정확**.
- ② **20개 검산**: 수익률 개수 = `startBack - endBack` = 30-10 = 20, 20-0 = 20. 종가는 각 21개.
  테스트가 값으로 못박음 — `LowVolCrashBacktesterTest.java:189-197` (close[i]=i 픽스처, si=40 →
  PRE 첫 10.0·끝 30.0, DURING 첫 20.0·끝 40.0). **주장대로 정확**.
- ③ **새 선견편향 없음**: PRE가 참조하는 최대 인덱스는 `si-10 < si`. 두 창 모두 상한이 si 이하이며
  `si+k` 형태 참조가 없다. 하한도 `minIndex()=startBack` 가드로 음수 불가(`:259`).
- ④ **전방·앵커 오염 없음**: `forwardReturn`(`:224-230`)·`detectAnchorIndices`(`:172-187`) 로직 무변경
  (diff상 시그니처·본문 동일). `aggregate`는 창별 2회 호출이지만 앵커 목록·전방수익률 계산은 동일 입력
  (`:140-141`), 버킷팅 기준만 교체 — 같은 이벤트·같은 전방수익률 위에서의 비교가 맞다.

## HIGH-2 쿨다운 경계 검산

- `COOLDOWN_DAYS = 60`(`:67`), `MAX_HORIZON = 60`(`:59`), 채택 조건 `i - lastAccepted >= 60`(`:181`).
- **간격이 정확히 60일 때**: 앵커 i, i+60의 전방 구간은 각각 `r(i+1..i+60)`, `r(i+61..i+120)` →
  **공유 수익률 0**(가격점 `close[i+60]` 하나만 접점 = 앞 이벤트의 청산가 = 뒤 이벤트의 진입가).
  중첩 없음 ✅. 즉 `>= 60`이 정확한 경계 조건이다(`> 60`일 필요 없음).
- 주석↔코드 일치: 클래스 주석 `:38-39`("최대 호라이즌 이상의 쿨다운으로 솎는다")·상수 주석 `:61-66`
  (기존 20의 결함을 사실대로 기술) 모두 코드와 일치. 테스트 `:221-241`이 `MAX_HORIZON == max(HORIZONS)`와
  `COOLDOWN ≥ MAX_HORIZON`를 상수 수준에서 잠금 + 간격 25는 배제/65는 채택을 값으로 확인.

## MEDIUM-1 / MEDIUM-2

- **재현성**: `anchorScopeSize`(`:193-200`)는 오름차순 날짜에서 `to` 이하 개수를 세고, `:121`에서
  `kospiCloses.subList(0, scope)` **prefix**로 자른다 → 앵커 인덱스가 전체 시계열 인덱스와 그대로 호환
  (prefix라 재매핑 불필요). `aggregate`에는 **잘리지 않은** `kospiCloses`가 들어가(`:140`) D+60 전방
  수익률은 `to` 이후 데이터로 계속 계산된다 — 클램프가 전방 데이터를 갉아먹지 않음 ✅.
  `thresholdCounts`도 같은 scope로 계산(`:126`) → 표와 본통계 기준 일치. 테스트 `:245-262`.
- **빈 표본**: `BacktestReportWriter.java:366-373`의 `signedPct(Quantiles)`(n=0→`-`)·
  `lowMinusHighText`(events=0→`-`)를 표 6칸 전부가 경유(`:322-326`), 오케스트레이터 로그도 동일
  경로 사용(`BacktestOrchestrator.java:603-605`). 테스트 `:266-279`가 `-`와 `+0.00%`를 구분 검증.

## 추가 확인 항목

| 항목 | 판정 | 근거 |
|---|---|---|
| 기존 모드 불변 | ✅ | 이번 수정에서 mtime이 바뀐 파일은 4개뿐(22:44~22:48). 다른 엔진/전략 파일은 07-21~07-23 그대로. Orchestrator diff의 **삭제 라인은 HEAD 대비 딱 1줄**(생성자 마지막 인자 줄) — 나머지 전부 순수 가산 |
| `writeComparison`·`judge`·`writeReport`·`writeBaseline` | ✅ 무수정 | diff 헌크 지도(@@ -33/-71/-80/-100/-111/-124/-154)가 수정 전과 동일, 마지막 헌크만 crash-vol 구간에서 증가 |
| 고아 `signedPct(double)` 삭제 | ✅ 자기 고아 정리 | 그 메서드는 HEAD에 없었고(삭제 라인 1줄이 전부) crash-vol 최초 추가분에서만 쓰였다. cost-lab이 쓰는 `pct(double)`는 그대로 살아 `:539,:567`에서 사용 중 |
| `CrashVolReport` 필드 변경 파급 | ✅ 무영향 | `CrashVolReport`/`WindowStats`/`VolWindow` 참조는 `LowVolCrashBacktester`·`BacktestOrchestrator`·`BacktestReportWriter`·전용 테스트뿐. CLAUDE.md 규칙4(인터페이스 우선)는 KIS 연동 인터페이스 대상이라 해당 없음 |
| 기준선 yml | ✅ 무변경 | mtime 2026-07-23 01:20 · trades 780 / PF 1.959 / period 2023-07-22~2026-07-21 |
| 프로필·주문·비밀키 | ✅ | `@Profile("backtest")` 유지, 의존은 CandleHistoryRepository·Properties뿐, `writeBaseline` 호출 0, 하드코딩 키 없음 |

## 신규 결함

CRITICAL 0 · HIGH 0. 아래는 LOW(실행 막지 않음, 해석 시 참고).

| 심각도 | 항목 | 위치 | 내용 |
|---|---|---|---|
| LOW | 앵커가 '급락의 첫 진입점'으로 편향 + 쿨다운 60으로 후속 급락 전면 배제 | `LowVolCrashBacktester.java:181` | 첫 돌파일을 채택하고 60거래일을 막으므로, 같은 하락장의 더 깊은 저점은 표본에서 사라진다. 독립성을 얻는 대가이며 리포트 ④에 이벤트 감소는 공시되나 '첫 진입점 편향'은 미기재 |
| LOW | KOSPI 캔들 부재 시 표 자체가 사라짐 | `LowVolCrashBacktester.java:112-113` → `BacktestReportWriter.java:318` | `windows=List.of()`라 섹션 헤더만 남고 표가 없다(경고 로그는 있음). 빈 실행을 '결과 없음'으로 오독할 여지는 작음 |
| INFO | 표본 급감 위험 | — | 쿨다운 60 + 임계 -7% + 2023~2026 우호장이면 앵커가 0~2건일 수 있다. **n≤2면 방향 언급도 금지**하고 임계치별 개수 표로 검정력을 먼저 보고할 것 |

## 검증 증거 (직접 실행)

```
명령: gradle cleanTest test --console=plain   (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build)
시각: 2026-07-24 22:55 · 종료: BUILD SUCCESSFUL
집계: 59 클래스 / 380 테스트 / failures 0 / errors 0 / skipped 0 (결과 XML 전부 이번 실행분, stale 0)
그중 LowVolCrashBacktesterTest: tests=15 failures=0 (기존 10 + 신규 5)
파일: docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml 무변경(780 / 1.959 / 2023-07-22~2026-07-21)
```
※ Red-Green 되돌림 검증은 직접 수행하지 않았다(감사자는 코드를 고치지 않는다). 대신 신규 5개
테스트가 **상수 동어반복이 아니라 값·경계로 고정**되어 있음을 확인했다(예: PRE 슬라이스 끝값 30.0,
간격 25 배제/65 채택, `-` vs `+0.00%` 구분).

## 결론

**CRITICAL 0건 · HIGH 0건 — HIGH 2건 모두 닫힘, MEDIUM 2건 닫힘, 새 HIGH 없음.**
선견편향 없음(PRE 도입으로도 미래 참조 0), 표본 접기 정상(이벤트 단위 유지), 전방창 중첩 제거됨.

→ **실행 가능.** `strategy-quant`가 `--backtest.mode=crash-vol`을 돌려도 좋다. 단 해석 시:
① 답은 **PRE 표** 기준으로 읽고 DURING은 대조용, ② 이벤트 n이 작으면(≤2) 결론 금지,
③ 생존·유동성 선택 편향(리포트 ③)을 결론 문장에 병기. 이 실험은 리포트 전용이며
어떤 결과가 나와도 전략 채택·실전 승격은 아니다(승격은 ADR-001 재논의 + 게이트 G2 사람 선행).
