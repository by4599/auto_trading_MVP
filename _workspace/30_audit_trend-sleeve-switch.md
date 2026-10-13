# 30_audit — 모의투자 A동(돈치안) 장중 전환 감사 + 배포 기록

> 감사 2026-10-01 08:42~09:03 · `risk-auditor` · 코드 무수정 · 빌드/테스트 미실행(지시). 정적 대조 + git 확인.
> 감사 환경이 파일 작성을 못 해 리더가 보고를 이 경로에 보존했다. §8은 리더가 덧붙인 배포 기록이다.

## 0. 판정 — CRITICAL 0 · HIGH 1 · MEDIUM 5 · LOW 9 → **장중 배포 가능 (단 "완료"는 아님)**

- **CRITICAL 0**: 흐름 우회 · 실계좌 노출(`application-paper.yml:7` 모의 도메인) · 비밀키 하드코딩 모두 0건.
- **HIGH 1(H-1)은 A동 보유분이 15:30을 넘기는 순간부터 발동한다.**
- **M-5(CLAUDE.md 규칙 6과 충돌)**는 사용자에게 한 번 직접 확인받으면 닫힌다.

### 1순위 결론 — 틱 단위 SAFE_MODE 플래핑은 되살아나지 않는다

- **돈치안 일봉은 종목별로 하루 1회만 받아 캐시한다**(`DonchianBreakoutStrategy.java:88-103`, `asOf==today`면 KIS 미호출).
  평상시 틱당 호출은 변경 전과 같은 `getRecentCandles` 2건. 늘어난 것은 하루 한 번 분량(종목당 +1건, 지수 하루 3~6건)뿐.
- 레이트리미터는 대기형(`KisRateLimiter.acquire` sleep) — 호출이 늘면 실패가 아니라 대기가 는다.
- 지수 조회(`KisIndexRegimeSource`): 그날 성공하면 재호출 없음(`:153`) · 실패 시 30분 뒤(`:154-155`) ·
  시도 1회당 `consecutiveFailures` 최대 +1 · 전용 데몬 스레드라 스케줄러 스레드를 막지 않는다.
- 조건부 경로 2개: **M-2**(08:30 장전 조회 실패 → 개장 직후 90초 관문 우회) · **H-1**(밤샘 보유 시 장외 잔고 호출 재개).

## 1. HIGH

### H-1 — 다일 보유가 잠들어 있던 장외 경로 두 개를 깨운다

**발동 조건**: 보유분이 15:30을 넘긴다 — A동의 정상 상태다. 보유가 있으면 `snapshotAccount()`가 장외에도
잔고를 불러(`KisPositionManager.java:111-119`) **장외에도 신선한 스냅샷**이 생기고, 이걸 쓰는 두 곳에 장 시간 검사가 없다.

- **(b) 전고점 오염 재개방 (결함 6)** — `ShadowPortfolio.tick()`(`:106-130`)이 "신선함 + 15% 상한"만 보고 장외에도
  전고점을 올린다. 관측된 오염 2건은 **둘 다 장 밖**이었다. 귀결: 09:00 `RiskMonitor`가 오염된 전고점 기준 MDD로
  **A동까지 전량 강제청산**, 다음 날도 반복 — 09-10~09-21 7거래일 정지와 같은 모양. 탐지 알림은 개장 후 한 통으로
  나가는데 청산과 같은 분이라 사람이 끼어들 틈이 없다.
- **(c) 장외 매도 폭주** — `StopLossMonitor.checkStops()`(`:77-104`) · `OrderEngine.executeSell`(`:102-111`) ·
  `KisOrderClientImpl`에 장 시간 가드 0건. 종가가 손절선·트레일선 아래면 15:30 이후 매 초: 시장가 매도 → KIS 거부 →
  FAILED + 예외(`KisOrderClientImpl:112-114`) → `hasPendingSell`이 ACCEPTED/PARTIAL만 봐서(`:165-170`) 다음 초 재시도.
  약 17시간 · FAILED 행 최대 약 6만 건. `OrderFailureCooldownRule`은 매수 전용(`:29`).
  선례: 08-05~06 "장시작전" 거부 2,336건 · 취소 5,983회(8266c0e). 고점 영속화로 트레일 매도 거부가 매 틱 재발동한다.

**고칠 방법**: ① `StopLossMonitor.checkStops` 장외 매도 판정 건너뛰기(`RiskMonitor:78` 방식) ② `ShadowPortfolio.tick`
장외 전고점 갱신 금지. 장중 판정 로직 불변. **기한: A동 첫 보유분이 15:30을 넘기기 전.**

## 2. MEDIUM

| # | 내용 | 고칠 방법 |
|---|---|---|
| M-1 | 지수 **부분 응답이 성공으로 기록**된다(`KisCandleHistoryClient:74` 빈 페이지 break → `KisIndexRegimeSource:131-137` 성공 처리 → `:153` 재호출 없음). 120개 미만이면 판정 불가 → 관문이 **종일 조용히 매수 차단**, 어제 정상 판정까지 사라짐. 흔적은 WARN뿐 | `IndexTrendCalculator.read` 비면 실패 취급(lastSuccess 보존·30분 재시도·장중 텔레그램) |
| M-2 | 08:30 장전 지수 조회 실패가 `blindSince=08:3x`를 남겨, 09:00 첫 2건까지 실패하면 실패 3회 + 눈먼 30분 → **즉시 SAFE_MODE**. 872afce가 없앤 "짧은 삐끗 정지" 재현 | 창 시작을 09:00~09:01로 |
| M-3 | 새 DB 저장(`StopLossMonitor:124→141-143`)이 **ATR 손절 판정(`:131`)보다 앞**. 저장 예외 시 그 틱 ATR 판정 없이 catch로 빠짐. 24_audit M-1과 같은 결 | 저장을 try/catch, 실패 시 메모리 값으로 진행 |
| M-4 | ADR 개정 §2.2 방어선 미구현 — MDD 8%(실제 `risk.mddLimit` 0.10) · 칸별 낙폭 상한 A동 −12%/B동 −20%(코드 없음). B동이 꺼진 지금 계좌 MDD 10%는 대략 A동 −24%라야 발동(400만/975만) | **실전(G2) 전 필수.** 판단 보류 |
| M-5 | CLAUDE.md 규칙 6("Sleeve A 구현 보류 — 자금·로직을 얹으면 ADR-001 위반")과 정면 충돌. 반대 근거: ADR-001 개정(2026-08-07 "사용자 승인" 명기)이 A동 개시를 결정 — 규칙의 근거가 사라졌고 문구만 낡았다 | 사용자 직접 확인 + 규칙 6 갱신(사용자 승인 사항) |

## 3. LOW (9)

| # | 내용 |
|---|---|
| L-1 | 일봉 2페이지가 빈 응답이면 100봉 미만이 그날 돈치안 캐시에 들어가 그 종목은 하루 신호 없음(fail-safe) |
| L-2 | 재기동·날짜 바뀐 첫 라운드는 틱당 KIS 4건(약 4초). 풀 1개라 감시 루프가 최대 약 4초 밀림(종목 수만큼의 틱 동안) |
| L-3 | 지수 "마지막 성공 판정 유지"에 기한 없음 — N거래일(예 3) 초과 시 fail-closed 권고. 판단 보류 |
| L-4 | 고점 영속화로 시세 튐 1회가 재시작 후에도 남음(`Position:157-163`은 0 이하만 거름) |
| L-5 | 공시 쿨다운 ON — D0 검증은 필터를 전부 끄고 채점했다. 성적 비교 분모 어긋남. 결정 대기 |
| L-6 | 종료 시 `shutdownNow()`가 조회 중 작업을 끊으면 장중 헛 텔레그램 1회 |
| L-7 | 브로커 보정으로 먼저 생긴 행은 칸이 null(=VB)이라 15:15 타임컷 대상 — 15:05~15:15 A동 매수가 걸릴 수 있다 |
| L-8 | `real` 프로필은 IndexRegimeSource 빈이 없어 기동 불가(기존부터, fail-safe) |
| L-9 | ADR §2.1 "trend-enabled=false로 잠겨 있다" 문구 낡음 · B동 배분 각 1,000만원(ADR 30% 초과)은 B동 재활성 시 문제 |

## 4. 통과 항목 (요약)

흐름 무변경(strategy·scheduler·order·backtest·RiskEngine·청산·IndexTrendRule·어제 수정분 diff 0) ·
새 룰 = `RiskRule` + `@Component` + `!backtest`(`IndexTrendDataGateRule:22-24`) · **매도 비차단**(`:36` `isBuy()` 가드) ·
**판정식 = 백테스트**(`IndexTrendCalculator:43-55` = `BacktestIndexRegimeSource:85-98`, Parity 테스트 2,821점) ·
**선견편향 3중 차단**(요청 to=어제 · 오늘 봉 버림 · `headMap(today,false)`) · 스레드(단일 데몬 · inFlight · `@PreDestroy`) ·
고점은 오를 때만 저장 · **옛 결함(옛 고점 누수) 해소**(매 틱 DB 값으로 `syncHigh`) · 컬럼 nullable(롤백 무해) ·
§15.7 D0 값 일치(MA120 · 돈치안 20/120 · TREND 0.0025/ATR1.0/arm0.01/trail0.03/다일/20일) · B동 3개 + RSI OFF ·
`trend-allocation` 원 단위 금액 · 비밀키 0건.

## 5. 감사하지 못한 것

빌드·테스트 미실행(876/876은 구현자 보고, 빌드 경로 `auto_trading-build-impl29`) · paper 전체 실기동 미검증 ·
운영 DB `trailing_high` ALTER 미실행 · KIS 응답 미실측(일봉 100행 상한·지수 TR 페이지 크기·M-1 실제 발생 여부·
장외 시장가 매도 거부 형태) · DB 미조회.

---

## 8. 배포 기록 (리더 추가, 2026-10-01)

### 1차 배포 실패 — 장중 약 4분 정지 (09:05~09:09)

`APPLICATION FAILED TO START` —
`No qualifying bean of type 'com.trading.risk.IndexRegimeSource' available: expected single matching bean but found 2: kisIndexRegimeSource,noOpIndexRegimeSource`

**원인**: 구현이 실행 중인 앱을 건드리지 않으려고 **별도 빌드 경로**(`auto_trading-build-impl29`)에서 테스트를 돌렸고,
그 사이 `NoOpIndexRegimeSource.java`를 **삭제**했다. 원래 빌드 경로(`auto_trading-build`)에는 **2026-09-22 00:29에 컴파일된
옛 `NoOpIndexRegimeSource.class`가 남아** 있었다. 재기동 시 증분 컴파일이 이 고아 클래스를 지우지 않아 같은 타입 빈이 2개가 됐다.
코드 버그가 아니라 **빌드 찌꺼기**다. 감사도 테스트도 이걸 잡을 수 없는 구조였다(둘 다 별도 경로를 봤다).

**조치**: 고아 클래스 1개 삭제 → 재기동 → 09:09:16 정상 기동(PID 19048, 15초).

**재발 방지(배포 절차에 추가)**: 별도 빌드 경로로 검증한 변경을 배포할 때 **소스 삭제가 있으면 운영 빌드 경로의 대응
`.class`를 먼저 지운다** — 더 확실하게는 배포 직전 `auto_trading-build/classes`를 통째로 지워 전체 재컴파일한다.

### 2차 배포 — 정상 (09:09)

| 감사 지정 점검 | 결과 |
|---|---|
| ① 기동 오류 · 새 룰 · DDL | `Started TradingApplication in 6.763 seconds` · `[RiskEngine] Loaded 15 risk rules`(14→15, `IndexTrendDataGateRule` 포함) · `trailing_high` DDL 오류 없음 |
| ② 지수 판정 | `[CandleHistory] 일봉 수취: code=0001 2025-08-27~2026-09-30 → 266건 (지수)` (≥120 ✅) · `[IndexTrend] KOSPI 일봉 266건 수취 — 2026-09-30 종가 6838.04 · MA120 7145.22 (-4.30%) → 하락 추세` — **M-1 상황 아님** |
| ③ 종목 일봉 | `[MarketData] 005930 일봉 125봉 수취 (요청 125봉, 페이지 2회)` · `035420 … 125봉` (≥120 ✅) — **100봉 상한 해소가 실측으로 확인됨** |
| ④ A동 매수 여부 | 지수가 MA120 아래(−4.30%) → **오늘 A동 신규 매수 0건이 정상** → H-1은 A동 경로로는 오늘 밤 미발동 |

### 전환 직전 B동이 산 2종목

옛 변동성 돌파가 09:00에 매수 주문 2건을 넣었고 **둘 다 체결**됐으나(결함 5로 DB 미반영) 재기동 재동기화가 잡았다:
`[Reconciler] 손절선 없는 보유분 2건 — 브로커 평균단가 기준으로` → `StopLossArmer` 장착 →
`브로커 기준 보정 2건` → RUNNING. 2026-08-04 `armMissingStops()` 수정이 의도대로 동작했다.

| 종목 | 수량 | 평단 | 손절선 | 칸 |
|---|---|---|---|---|
| 035420 NAVER | 6 | 193,700 | 186,018 (ATR 5,121 × 1.5) | VB → 15:15 타임컷 대상 |
| 005930 삼성전자 | 3 | 266,500 | 252,143 (ATR 9,571 × 1.5) | VB → 15:15 타임컷 대상 |

⚠ **H-1의 남은 노출**: 이 두 종목의 타임컷 매도(15:15 + 재시도 스윕 15:20·15:24·15:28)가 **모두 실패하면** 밤을 넘기고
H-1(b)·(c)가 열린다. → H-1 수정(`_workspace/31_impl_after-hours-guards.md`)을 15:30 전에 배포한다.
