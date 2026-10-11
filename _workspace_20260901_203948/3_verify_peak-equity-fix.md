# 3_verify_peak-equity-fix — 증거 검증 (paper-ops-verifier)

검증 실행: 2026-08-12 12:32~12:40 KST / 담당: paper-ops-verifier
**이 문서의 모든 수치는 이번 세션에서 직접 실행한 명령의 원문 출력이다.** 이전 실행은 인용하지 않았다.

## 한 줄 판정

**코드 검증 = 통과(505/505, 종료 코드 0, Red-Green 독립 재현 완료) · 운영 반영 = 미완료.**
가동 중인 앱(PID 10580)은 **2026-08-10 23:05:41에 기동**해 이번 수정 코드가 아예 들어있지 않다 —
DB만 고쳐졌고 실제 매수 게이트는 아직 닫혀 있다. 재시작은 **사용자가 직접** 해야 한다.

---

## 1. 검증 요약표

| 주장 | 검증 명령 (이번 세션 직접 실행) | 결과 | 판정 |
|---|---|---|---|
| 전체 테스트 통과 | `gradle test --rerun-tasks --console=plain` | BUILD SUCCESSFUL, **종료 코드 0**, 78클래스/**505개 중 505개 통과**, 실패 0·에러 0·스킵 0 | ✅ |
| 구현자 주장 495/495 | 위 실행 XML 합산 | **재현 안 됨 — 505가 맞다.** 495는 부록 A(테스트 9→19건 확장) 이전 숫자, 감사자 505와 일치 | ✅ (숫자 갱신) |
| 감사자 주장 505/505 | 위 실행 XML 합산 | **재현됨** (독립 실행) | ✅ |
| 회귀 테스트가 실제로 버그를 잡는다 | 가드 4곳 임시 무력화 → `gradle test --tests "com.trading.position.ShadowPortfolioTest*"` | **19개 중 7개 실패, 종료 코드 1, BUILD FAILED** | ✅ RED 확인 |
| 원복 후 복구 | md5 대조 후 `gradle test --rerun-tasks` 재실행 | md5 일치, **505/505, 종료 코드 0** | ✅ GREEN 확인 |
| DB PEAK_EQUITY = 1.0E7 | H2 `SELECT * FROM PORTFOLIO_STATE` | `PEAK_EQUITY \| 1.0E7` | ✅ |
| RAW_BEFORE_CALIBRATION 키 없음 | H2 `SELECT COUNT(*) ... WHERE STATE_KEY='PEAK_EQUITY_RAW_BEFORE_CALIBRATION'` | **0행** — 자동 클램프 무발동 | ✅ |
| 실측 근거 MAX=1.0E7 / 24거래일 | H2 `SELECT MAX/MIN/COUNT FROM DAILY_EQUITY` | MX=1.0E7, MN=9,912,930, C=24 | ✅ |
| 보유 포지션 0개 | `GET /api/position` + H2 `SELECT COUNT(*) FROM POSITION` | `[]` / 0행 (두 경로 일치) | ✅ |
| 앱 살아있음 | `curl http://localhost:8080/` | HTTP 200 | ✅ |
| 현재 모드 | `GET /api/risk/status` | `EMERGENCY_STOPPED` | ⚠ 정지 중 |
| 메모리에 옛 오염값 잔존 | 프로세스 기동 시각 조회 | **2026-08-10 23:05:41 기동 — 수정 코드(8/12) 자체가 없음** | ❌ 재시작 필요 |
| 연속 가동일 5일 | `GET /api/trading/run-streak` | `{"streakDays":7,"goalDays":5,"achieved":true,"lastRecordedDate":"2026-08-11"}` | ✅ 단, 아래 §5 주의 |
| 청산 리허설 1회 성공 | 성공 기록 없음 (CLAUDE.md 2026-07-30 실패 기록) | 미실행 | ❌ 잔존 |
| 연속손실 라운드트립 전환 | `TradeResultTracker.recordSellFill` 직독 | 여전히 **체결 청크 단위** (javadoc "v1 한계" 그대로) | ❌ 잔존 |
| 데드맨 스위치 동작 | `GET /api/settings` | `HEARTBEAT_URL: {"set":"false"}` → **ping 스킵 중(미작동)** | ❌ 미설정 |
| 텔레그램 알림 | `GET /api/settings` | 봇 토큰·챗ID 둘 다 `set:true`. **실제 도달은 미검증**(테스트 발송 안 함) | ⚠ 설정만 확인 |

---

## 2. [검증 증거] 빌드·테스트 (spring-build-verify §4 형식)

### 2-1. 1차 전체 스위트

```
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build gradle test --rerun-tasks --console=plain
종료 코드: 0
결과: 78개 클래스 / 505개 테스트 중 505개 통과, 실패 0, 에러 0, 스킵 0
XML 타임스탬프: 2026-08-12T03:32:39.117Z ~ 03:32:50.759Z (= KST 12:32:39~12:32:50, 이번 실행분)
→ 판정: 통과
```

이번 변경 관련 클래스별 내역 (XML 원문):

| 테스트 클래스 | tests | failures | errors |
|---|---|---|---|
| `ShadowPortfolioTest` | 11 | 0 | 0 |
| `ShadowPortfolioTest$CeilingBoundary` | 3 | 0 | 0 |
| `ShadowPortfolioTest$HeadroomDerivation` | 3 | 0 | 0 |
| `ShadowPortfolioTest$BacktestCalibrator` | 2 | 0 | 0 |
| (ShadowPortfolioTest 계열 소계) | **19** | **0** | **0** |
| `RiskMonitorTest` | 12 | 0 | 0 |
| `ShadowPortfolioReconcilerTest` | 15 | 0 | 0 |

> 구현자 보고서 §4의 "495개"는 부록 A 이전(테스트 9건 시점) 숫자다. 부록 A에서 19건으로 늘어난
> 뒤의 정확한 값은 **505**이며, 감사자 재감사 수치와 내 독립 실행이 일치한다.

### 2-2. Red-Green — 내가 직접 되돌려서 확인했다

되돌린 것 4가지 (수정 전 상태로 임시 복귀):
1. `ShadowPortfolio.tick()`의 `!account.isFresh()` 가드 → `if (false)`
2. `ShadowPortfolio.tick()`의 `calibrator.isImplausible(current)` 가드 → `if (false)`
3. `EvidenceBasedPeakEquityCalibrator.intradayHeadroom()` → `return 0.15` (하드코딩 원복)
4. `calibrate()`의 클램프 → `verifiedMaxEquity()`로 과잉 하향 + 원값 보존 저장 제거

```
[RED]
명령: gradle test --tests "com.trading.position.ShadowPortfolioTest*" --console=plain
종료 코드: 1  (BUILD FAILED)
결과: 19 tests completed, 7 failed
  - 낡은(폴백) 스냅샷은 전고점을 갱신하지 못한다                              (ShadowPortfolioTest.java:85)
  - 신선하지만 실측 근거상 불가능한 총자산도 전고점을 갱신하지 못한다          (:97)
  - 상한 경계 > 상한을 1원이라도 넘으면 갱신하지 않는다                        (:165)
  - 허용폭 유도 > 비중을 30%로 올리면 허용폭도 따라 올라 정당한 신고점을 막지 않는다 (:199)
  - 허용폭 유도 > 노출은 100%를 넘지 못한다 — 허용폭 상한은 30%               (:211)
  - 기동 시 오염된 전고점을 '검증된 최대 자산'이 아니라 상한까지만 낮춘다      (:225)
  - 클램프 시 원값을 별도 키로 보존하고 교정값을 영속화한다                    (:238, TooFewActualInvocations)
```

```
[GREEN]
복구 검증: md5 대조
  ShadowPortfolio.java                      73f9998116ef0f2a4bf62895e95e2712  (원본과 동일)
  EvidenceBasedPeakEquityCalibrator.java    5ad962d213f6b552a12b099b29ad5482  (원본과 동일)
명령: gradle test --rerun-tasks --console=plain
종료 코드: 0
결과: 78클래스 / 505개 중 505개 통과, 실패 0
XML 타임스탬프: 2026-08-12T03:34:44.447Z ~ 03:34:55.783Z (= KST 12:34:44~55)
→ 판정: RED(7건 실패) → GREEN(505 통과) 확인. 이 테스트들은 실제로 이번 수정을 검사한다.
```

워킹트리는 검증 전 상태로 완전 복원됐다(md5 대조로 확인). 검증 목적의 임시 변경은 남아 있지 않다.

---

## 3. [검증 증거] 운영 DB (H2 AUTO_SERVER 직접 접속)

접속: `jdbc:h2:file:./trading-db;AUTO_SERVER=TRUE`, user `sa` (H2 2.2.224 Shell)

```
SELECT STATE_KEY, STATE_VALUE FROM PORTFOLIO_STATE ORDER BY STATE_KEY;
STATE_KEY            | STATE_VALUE
PEAK_EQUITY          | 1.0E7
RUN_STREAK_DAYS      | 7.0
RUN_STREAK_LAST_DATE | 2.0260811E7
(3 rows)

SELECT COUNT(*) FROM PORTFOLIO_STATE WHERE STATE_KEY='PEAK_EQUITY_RAW_BEFORE_CALIBRATION';
0        ← 자동 클램프가 한 번도 발동하지 않았다는 뜻 (정상)

SELECT MAX(START_EQUITY), MIN(START_EQUITY), COUNT(*) FROM DAILY_EQUITY;
1.0E7 | 9912930.0 | 24

SELECT TRADE_DATE, START_EQUITY FROM DAILY_EQUITY ORDER BY TRADE_DATE DESC LIMIT 3;
2026-08-12 | 9992814.0
2026-08-11 | 9992814.0
2026-08-10 | 9992814.0

SELECT COUNT(*) FROM POSITION;         → 0
SELECT COUNT(*) FROM TRADE_RESULT;     → 5
SELECT STATUS, COUNT(*) FROM ORDER_HISTORY GROUP BY STATUS;
  CANCELLED 44 / FAILED 2350 / FILLED 76
SELECT MAX(REQUESTED_AT) FROM ORDER_HISTORY;  → 2026-08-07 09:00:21
```

### 3-1. 매수 게이트 산수 (직접 계산)

`GlobalEquityStopRule`: `drawdown = (peak - current) / peak`, 한도 `mddLimit` 10%.
현재 자산 기준값 9,992,814원(오늘 `daily_equity.START_EQUITY`).

| 전고점 경로 | 전고점 | MDD | 매수 게이트 |
|---|---|---|---|
| 오염값(지금 앱 메모리) | 13,027,929 | **23.2970%** | 차단 (+ RiskMonitor 청산 트리거 사정권) |
| 자동 클램프만 적용 시 | 11,500,000 | **13.1060%** | 여전히 차단 |
| **현재 DB값 (1회성 실측 교정)** | 10,000,000 | **0.0719%** | **열림** |

허용폭 검산: `min(1.0, maxPositionWeight 0.10 × maxPositionCount 5) × 0.30 = 0.15` →
상한 = 10,000,000 × 1.15 = 11,500,000. **현재 DB값 1.0E7 ≤ 상한** → 재기동해도 `calibrate()`가
값을 건드리지 않는다(감사자 판단과 일치, 코드 직독으로 확인).

---

## 4. [운영 점검] 2026-08-12 12:36 KST (paper-ops-check §5 형식)

| 항목 | 상태 | 근거 (직접 실행) |
|---|---|---|
| 앱 | 살아있음 | `GET /` → HTTP 200 |
| 운전 모드 | **EMERGENCY_STOPPED** (비상 정지) | `GET /api/risk/status` → `{"tradingMode":"EMERGENCY_STOPPED","configured":true,"dailyPnlPercent":0.0,"consecutiveLossCount":0}` |
| 실행 중인 프로세스 | PID 10580, **기동 2026-08-10 23:05:41** | `Win32_Process` 조회 — **수정 코드(2026-08-12)를 담고 있지 않음** |
| 보유 포지션 | 0개 | `GET /api/position` → `[]` (DB `POSITION` 0행과 일치) |
| 오늘 손익 | 0.0% / 실현·미실현 0 | `GET /api/pnl/daily` → `{"tradeCount":0,"unrealizedPnl":0,"realizedPnl":0}` |
| 연속 가동일 | 7일 (목표 5, 달성) | `GET /api/trading/run-streak` → `lastRecordedDate: 2026-08-11` |
| 마지막 주문 | 2026-08-07 09:00:21 | `ORDER_HISTORY` 최대 `REQUESTED_AT` — **5일째 신규 주문 없음**(전고점 오염으로 매수 차단된 것과 정합) |
| 청산 리허설 | **미실행** | 성공 기록 없음 (CLAUDE.md: 2026-07-30 시도 실패) |
| 연속손실 집계 단위 | **체결 청크 단위(잔존 결함)** | `TradeResultTracker.recordSellFill()` — 매도 체결 1건당 1회 기록, javadoc "v2에서 라운드트립 단위로 바꿔야 한다" 그대로 |
| 데드맨 스위치 | **미작동** | `GET /api/settings` → `HEARTBEAT_URL {"set":"false"}` → `DeadmanHeartbeat.ping()`이 매번 스킵 |
| 텔레그램 | 설정됨 / 도달 미검증 | `TELEGRAM_BOT_TOKEN`·`TELEGRAM_CHAT_ID` 둘 다 `set:true`. 테스트 발송은 하지 않았다 |

→ 남은 관문: **청산 리허설 1회** · **연속손실 라운드트립 전환** (가동일 5일은 7일로 충족)

---

## 5. ⚠ 재시작 시점에 대한 경고 (내가 새로 발견한 것)

**지금(장중 12:36) 재시작하면 오늘 15:25에 연속 가동일 7일이 0으로 리셋된다.**

근거 — `RunStreakRecorder.judgeStreak()` (직독):

```java
boolean upSinceOpen = !appStartedAt.isAfter(today.atTime(calendar.openTime(today)));
if (!upSinceOpen) { ... return 0; }   // 개장(09:00) 이후 기동 → 그날은 미인정, 연속 기록 리셋
```

- 오늘 개장 09:00. 지금 재시작하면 `appStartedAt`(12:xx) > 09:00 → 오늘은 무중단 미인정 → **0으로 리셋**
- **오늘 15:25 이후에 재시작하면** 오늘치는 기존 프로세스(8/10 기동)로 정상 기록되어 7→8이 되고,
  내일 15:25에는 `appStartedAt`(8/12 저녁) < 내일 09:00이라 연속이 이어진다 → **손실 없음**

또한 `RunStreakRecorder`는 **운전 모드를 보지 않는다** — 앱이 켜져 있기만 하면 EMERGENCY_STOPPED여도
가동일로 센다. 즉 지금의 "7일"은 "7거래일 동안 매매했다"가 아니라 "7거래일 동안 앱이 떠 있었다"는 뜻이다.
이 사실은 릴리즈 체크리스트 "연속 5거래일 무중단 실행" 판정 시 사용자에게 알려야 한다.

부수 사실: 재시작하면 `ShadowPortfolioReconciler.onStartup()`이 **사람 확인 없이 곧바로 RUNNING**으로
올린다(사용자 정책 2026-07-16, `ShadowPortfolioReconciler.java:117-123`). 즉 장중에 재시작하면
그 즉시 매매가 재개된다. 장 마감 후 재시작하면 RUNNING이 되어도 장이 닫혀 있어 주문이 나가지 않는다.

---

## 6. 재시작 후 사용자가 따라 할 절차 (쉬운 말)

> **왜 재시작이 필요한가:** 어제 컴퓨터 안 기록장(DB)의 잘못된 "최고 기록"은 고쳤지만,
> 지금 돌고 있는 프로그램은 **8월 10일 밤에 켜진 것**이라 그 잘못된 숫자를 머릿속에 그대로 들고 있다.
> 프로그램은 켜질 때 한 번만 기록장을 읽는다. 그래서 껐다 켜야 새 숫자를 읽는다.

**권장 시점: 오늘(8/12) 오후 3시 25분 이후.** (그 전에 끄면 "7일 연속 켜둠" 기록이 0으로 지워진다)

1. **끄기** — 지금 돌고 있는 프로그램 창에서 `Ctrl + C`를 누른다.
   (창을 못 찾으면 작업관리자에서 java 프로세스 번호 **10580**을 끝낸다)
2. **켜기** — 프로젝트 폴더에서 `run-paper.bat`을 실행한다.
3. **1분 안에 화면 로그에서 이 한 줄을 찾는다:**
   ```
   [ShadowPortfolio] peakEquity 복원: 1.0E7
   ```
   - 이 줄이 보이면 **성공**. 최고 기록을 1,000만원으로 제대로 읽었다는 뜻이다.
   - 만약 대신 `오염된 peakEquity 교정: ... → ...` 이라는 빨간 줄이 뜨면 **멈추고 사람에게 알린다**
     (기록장이 또 이상해졌다는 뜻. 이 경우 텔레그램으로도 "🔧 [전고점 교정]" 알림이 온다)
   - `13027929` 같은 숫자가 보이면 **실패** — DB를 안 읽은 것이니 알린다
4. **켜졌는지 확인** — 브라우저에서 `http://localhost:8080` 을 열고, 또는:
   ```
   GET http://localhost:8080/api/risk/status
   ```
   `"tradingMode":"RUNNING"` 이면 정상 (재시작하면 자동으로 RUNNING이 된다)
5. **살 수 있는 상태인지 확인** — 아래를 조회해서 최고 기록이 1,000만원인지 다시 본다.
   ```
   H2 조회: SELECT * FROM PORTFOLIO_STATE;
   → PEAK_EQUITY | 1.0E7  이면 정상
   → PEAK_EQUITY_RAW_BEFORE_CALIBRATION 이라는 줄이 새로 생겼으면 → 자동 교정이 발동한 것, 사람에게 알린다
   ```
6. **매수가 실제로 열렸는지**는 다음 거래일 장중에 확인한다 — 대시보드에 매수 주문이 나가거나,
   로그에 `전고점 대비 MDD ... 초과 — 신규 매수 금지` 라는 거절 문구가 **더 이상 안 나오면** 열린 것이다.

### 만약 재시작해도 RUNNING이 안 되거나 비상 정지에 머물면 (재가동 게이트)

`/api/trading/start`로 바로 못 켠다. 반드시 아래를 먼저 호출한다 — **확인 문자열과 이유가 둘 다 필요**하다:

```
POST http://localhost:8080/api/trading/resume
Content-Type: application/json

{"confirm":"CONFIRM_RESUME","reason":"전고점 오염 교정 후 재가동 (2026-08-12)"}
```

- `confirm` 값이 정확히 `CONFIRM_RESUME`이 아니면 거절된다
- `reason`이 비어 있으면 거절된다 (왜 멈췄었는지 기록을 남기기 위한 장치)
- 성공하면 **SAFE_MODE(새로 사는 건 멈추고 지키기만 하는 상태)**가 된다 — 그 다음
  대시보드의 "거래 시작"(= `POST /api/trading/start`)을 눌러야 RUNNING이 된다

> **내가 대신 누르지 않은 이유:** 재시작·재가동은 실제로 돈이 움직이기 시작하는 조작이라
> 사람이 직접 하는 것이 이 프로젝트 규칙이다(paper-ops-check 안전 원칙).

---

## 7. SCOPE_GUARDIAN.md 갱신 제안 (직접 갱신 안 함 — 사용자 승인 필요)

### 7-1. "안정성 — 예외 처리" 항목

현재 문구:
> ⚠ | KIS 장애 시 SAFE_MODE 자동 전환·데드맨 스위치 완료. 부분 체결 매수 손절 장착 완료(2026-07-20).
> 잔존: 연속손실 집계가 매도 체결 청크 단위

**평가: 상태 기호는 `⚠` 유지가 맞다. 다만 근거 문구는 갱신할 사유가 생겼다.**

| 판단 | 근거 |
|---|---|
| 이번 수정은 이 항목을 **전진시켰다** | "잘못된 데이터가 안전장치를 영구히 망가뜨리는 경로"를 막았다. 낡은 스냅샷·실측 초과값 차단(`ShadowPortfolio.tick()` 4단 가드) + 기동 시 자동 교정 + 원값 보존 + 사람 알림. 505/505 통과·Red-Green 재현으로 증거 확보 |
| 그러나 **`☑`로 못 올린다** | 표에 명시된 잔존 결함 "연속손실 집계가 매도 체결 청크 단위"가 **그대로 남아 있다**(`TradeResultTracker.recordSellFill` 직독으로 확인). 이 항목의 `⚠` 사유가 해소되지 않았다 |
| 근거 문구에서 **사실과 다른 부분 발견** | "데드맨 스위치 완료"는 코드 기준으로만 맞다. 실제 운영에서는 `HEARTBEAT_URL`이 비어 있어(`set:false`) ping이 매번 스킵된다 — **감시 장치가 꺼진 상태**다 |

**제안 문구 (사용자 승인 시 반영):**
> ⚠ | KIS 장애 시 SAFE_MODE 자동 전환 완료. 부분 체결 매수 손절 장착 완료(2026-07-20).
> 잘못된 잔고 1회가 전고점을 영구 오염시키는 경로 차단 완료(2026-08-12, `PeakEquityCalibrator` — 505/505·Red-Green 검증).
> 잔존 ①: 연속손실 집계가 여전히 매도 체결 청크 단위. 잔존 ②: 데드맨 스위치는 코드만 완료 —
> `HEARTBEAT_URL` 미설정으로 실제 ping 미발송(2026-08-12 실측)

### 7-2. 함께 검토를 제안하는 항목 (승인 대상)

| 항목 | 현재 | 제안 | 근거 |
|---|---|---|---|
| 검증 / 모의투자 연속 5거래일 무중단 | ☐ | **☑ 후보** (판단은 사용자) | `run-streak` 7일·`achieved:true`. **단서**: `RunStreakRecorder`는 운전 모드를 보지 않아 EMERGENCY_STOPPED로 서 있던 날도 셌다. "매매를 5일 했다"는 증거는 아니다 — 이 정의로 충족을 인정할지는 사용자 판단 |
| 핵심 기능 / 강제청산 리허설 | ⚠ | **⚠ 유지** | 성공 기록 없음. 리허설은 보유 포지션이 있어야 의미가 있는데 현재 보유 0개 → 재시작·매수 재개 후에 실행해야 한다 |

---

## 8. 미완료 항목 정리

| # | 항목 | 상태 | 다음 행동 |
|---|---|---|---|
| 1 | 앱 재시작(수정 코드 + 교정값 반영) | ❌ 미완료 | **사용자 실행** — §6 절차, 오늘 15:25 이후 권장 |
| 2 | 모의계좌 강제청산 리허설 1회 | ❌ 미완료 | **사용자 실행** — 보유 포지션이 생긴 뒤 장중에 `POST /api/trading/liquidation-drill` (`{"confirm":"CONFIRM_LIQUIDATE"}`) |
| 3 | 연속손실 라운드트립 집계 전환 | ❌ 미완료 | 구현 필요 (`trading-implementer`) — 이번 변경으로 개선되지 않았다 |
| 4 | 데드맨 스위치 실제 작동 | ❌ 미작동 | `HEARTBEAT_URL` 설정 필요 (healthchecks.io 등) — 사용자 결정 |
| 5 | 텔레그램 도달 확인 | ⚠ 미검증 | 재시작 시 나오는 기동 알림으로 확인 가능 |
| 6 | 오염 최초 원인 규명 | ❌ 미해결 | 감사자 권고대로 BACKLOG 등록 (`KisBalanceClient` 총자산 합산 감사) |

→ **미완료 6건 — "완료" 아님.** 코드는 통과했으나 운영 반영이 남아 있다.
