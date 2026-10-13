# 19_impl — 계좌 기준 성적 API + 전고점 재오염 방지 (A~E 구현)

> 작업 2026-09-21 KST · 대상 브랜치 `backtest/regime-filter-and-validation`
> 선행: `_workspace/17_ops_peak-equity-fix.md`(조사) · `18_ops_peak-equity-applied.md`(값 교정 실행)

## 0. 한 줄

**"얼마 벌었나"의 정본을 `trade_result`(오염)에서 `daily_equity`(정확)로 옮기고,
전고점이 올라간 순간을 로그·텔레그램으로 남기게 했다.** 매매 경로는 한 줄도 건드리지 않았다.

테스트 **687개 전부 통과 · 종료 코드 0**. 검산값 **-212,040원 / -2.98%** 재현 확인.
전고점 텔레그램 발송 빈도는 리더 확정 기준(§10)으로 조였다 — **로그는 매번, 발송만 조임**.

---

## 1. 바꾼 파일과 이유

### 신규

| 파일 | 역할 | 줄 |
|---|---|---|
| `src/main/java/com/trading/dashboard/AccountPerformanceService.java` | `daily_equity` → 총자산 시계열·일별 순손익·누적 수익률·전고점·현재 낙폭·MDD·강제정지 문턱 | 193 |
| `src/main/java/com/trading/dashboard/AccountPerformanceController.java` | `GET /api/performance/account?days=` (paper 전용) | 41 |
| `src/main/java/com/trading/dashboard/OrderQueryController.java` | `/api/orders/filled`(기간·페이징) + `/api/orders/open`(미체결) | 136 |
| `src/test/java/com/trading/dashboard/AccountPerformanceServiceTest.java` | 위 서비스 단위 테스트 (MDD 3케이스 포함) | 10+3 테스트 |
| `src/test/java/com/trading/dashboard/OrderQueryControllerTest.java` | 주문 조회 컨트롤러 테스트 | 7+3 테스트 |
| `src/test/java/com/trading/order/OrderHistoryQueryTest.java` | 새 파생 쿼리를 **실제 H2에서** 검증 (`@DataJpaTest`) | 3 테스트 |

### 수정

| 파일 | 바꾼 것 | 왜 |
|---|---|---|
| `position/ShadowPortfolio.java` | 전고점 갱신 로그 `DEBUG → INFO`(직전값·새값·증가율) + `calibrator.notifyPeakRaised()` 호출 1줄 | (A) 오염 시점을 남기지 않아 7거래일을 놓쳤다. **갱신 조건·클램프 상한은 한 글자도 안 바꿨다**(아래 §6 diff 근거) |
| `position/PeakEquityCalibrator.java` | `default void notifyPeakRaised(double, double) {}` 추가 | 알림을 프로필이 갈리는 자리에 둔다 — 백테스트가 텔레그램을 두드리는 경로를 구조로 차단 |
| `position/EvidenceBasedPeakEquityCalibrator.java` | `notifyPeakRaised` 구현 (텔레그램) + **발송 빈도 규칙** + `Clock` 주입 | paper 전용 구현체. 직전값 0(최초 확립)은 '경신'이 아니라 안 보냄. 빈도 기준은 §10 |
| `dashboard/PerformanceService.java` | 응답에 `source` 경고 필드 | (C) 화면이 오염된 숫자를 정본으로 읽지 않도록 |
| `dashboard/DashboardController.java` | `/api/orders/filled` 메서드를 `OrderQueryController`로 이사 (233 → 204줄) | (D)(E)를 얹으면 300줄 초과 |
| `order/OrderHistoryRepository.java` | `findByStatusInAndRequestedAtGreaterThanEqualOrderByRequestedAtDesc(…, Pageable)` 추가 | (E) 기간·페이징 |
| `test/…/OrderLifecycleTest.java` | 손수 만든 `StubOrderHistoryRepository`에 새 메서드 1개 구현 | 인터페이스에 메서드가 늘어 컴파일이 깨졌다 (기계적 추가) |

> `PerformanceService`·`trade_result`·`PerformanceController`는 **삭제하지 않았다**(B-4 표본·감사 이력).
> 화면 연결 해제도 하지 않았다 — 다음 작업 범위.

---

## 2. 새 API 계약

### (B) `GET /api/performance/account?days=90` — **paper 전용**

`days`: 1~3650 (기본 90, 범위 밖은 잘라 맞춤)

| 필드 | 뜻 | 실제값(2026-09-21) |
|---|---|---|
| `source` | 출처 문구 | `daily_equity — 계좌 잔고 원장(수수료·세금 반영)…` |
| `days` / `from` | 조회 기간 | `365` / `2025-09-22` |
| `initialEquity` | 기간 내 최초 기록의 **시작** 총자산 | `10000000` |
| `currentEquity` | 마지막 기록의 총자산(마감 찍혔으면 마감값) | `9787960` |
| `cumulativePnl` | `currentEquity - initialEquity` (원) | **`-212040`** |
| `cumulativeReturnPercent` | 위를 최초 대비 % | `-2.12` |
| `peakEquity` | **`ShadowPortfolio`** 전고점 (= DB `portfolio_state.PEAK_EQUITY`) | `10088806` |
| `peakUnverified` | 전고점이 교정된 미검증 값인가 | `false` |
| `currentDrawdownPercent` | `(현재-전고점)/전고점` — **음수 = 전고점 아래** | **`-2.98`** |
| `maxDrawdownPercent` | 기간 내 최대 낙폭 (음수) | `-2.98` |
| `mddLimitPercent` | `risk.mddLimit × 100` (양수 한도값) | `10.0` |
| `forcedStopThreshold` | `전고점 × (1 - mddLimit)` | `9079925` |
| `roomToThreshold` | `현재 총자산 - 문턱` | `708035` |
| `closedDays` / `netPnlSum` | 마감 찍은 날 수 / 그날들 순손익 합 | `3` / `0` |
| `series[]` | `{date, equity}` — 차트용, 값이 있는 날 전부(오래된 날 먼저) | 47점 |
| `daily[]` | `{date, closed, startEquity, endEquity, netPnl, netPnlPercent}` — 최신 먼저 | 47행 |

- **`closed=false`인 날은 `netPnl`이 `null`** (0으로 꾸미지 않는다). 합계·`closedDays`에서도 빠진다.
- **주말 행 대응**: `daily_equity`에는 토·일 행이 실제로 들어 있다(실측 47행 중 **9행**: 07-19, 07-26,
  08-01, 08-02, 08-08, 08-29, 09-12, 09-19, 09-20). 마감 기록이 없으므로 손익 집계에 들어가지 않고,
  값이 이어지는 행이라 러닝 최대 계산도 흔들지 않는다. 시계열에는 그대로 그린다.
- **한도 출처**: `RiskLimitsProperties.getMddLimit()` — `GlobalEquityStopRule`·`RiskMonitor`·`/api/params`가
  읽는 것과 **같은 빈**이다. 하드코딩 없음.

### (C) `GET /api/performance` (기존, 변경 최소)

응답 맨 앞에 `source` 추가:

```
"trade_result — 모의 매도 체결가 결함(CLAUDE.md 결함 5)으로 손익이 과대계상됨.
 정확한 계좌 기준 성적은 /api/performance/account 를 볼 것"
```

다른 필드·동작은 불변.

### (D) `GET /api/orders/open` — 신규

`OrderStatus.ACCEPTED` + `CANCEL_REQUESTED`, 최신순 배열. 페이징 없음(많아야 몇 건).

`{id, stockCode, side, quantity, filledQty, pendingQty, orderNo, status, requestedAt, cancelRequestedAt, bucket}`

### (E) `GET /api/orders/filled?days=&page=&size=`

| 파라미터 | 기본 | 범위 | 뜻 |
|---|---|---|---|
| `days` | **없음(전 기간)** | 1~365 | 오늘 포함 최근 N일 (일별 원장 API와 같은 셈법) |
| `page` | 0 | 0 이상 | 0-based |
| `size` | 20 | 1~200 | 한 페이지 건수 |

**파라미터가 없으면 지금 동작과 완전히 동일**(전 기간 최근 20건). 응답도 **지금처럼 JSON 배열**이다 —
`tab-home.js:200`이 배열을 그대로 읽으므로 감싸는 형태로 바꾸지 않았다(총건수 필드는 그래서 없다).

---

## 3. 테스트 결과 (이번에 실행한 것만)

```
[검증 증거 — 전체]
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build \
      ~/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gvueggl8a5cioxuftxrik/gradle-9.2.0/bin/gradle \
      test --console=plain
종료 코드: 0
결과: 687개 중 687개 통과, 실패 0, 에러 0, 건너뜀 0 (테스트 클래스 101개)
→ 판정: 통과
```

이번 작업 관련 클래스별 내역 (`TEST-*.xml` 집계, 합계 82):

| 클래스 | 건수 | 실패 |
|---|---|---|
| `AccountPerformanceServiceTest` | 10 | 0 |
| `AccountPerformanceServiceTest$MaxDrawdown` | 3 | 0 |
| `OrderQueryControllerTest$FilledOrders` | 7 | 0 |
| `OrderQueryControllerTest$OpenOrders` | 3 | 0 |
| `OrderHistoryQueryTest` (@DataJpaTest, 실제 H2) | 3 | 0 |
| `ShadowPortfolioTest$PeakRaiseAlert` | **12** | 0 |
| `ShadowPortfolioTest` (기존 + 중첩 4개) | 25 | 0 |
| `PerformanceServiceTest` | 4 | 0 |
| `ShadowPortfolioReconcilerTest` (회귀) | 15 | 0 |

### Red-Green 기록 (한 번 통과한 것만으로는 아무것도 증명하지 못한다)

| 단계 | 명령 | 결과 |
|---|---|---|
| RED (A)(C) | `--tests ShadowPortfolioTest --tests PerformanceServiceTest` | 종료 1 · **36개 중 3개 실패** (경신 알림 2건 + source 1건) |
| GREEN (A)(C) | 같은 명령 | 종료 0 |
| RED (B)(D)(E) | `--tests AccountPerformanceServiceTest --tests OrderQueryControllerTest` (뼈대만 있는 상태) | 종료 1 · **23개 중 21개 실패** |
| GREEN (B)(D)(E) | 같은 명령 | 종료 0 |
| **변이 검사(MDD)** | `Math.min(worst, …)` → `worst = …` 로 바꿔 "가장 깊은 골짜기" 대신 "마지막 값"을 쓰게 함 | 종료 1 · **13개 중 1개 실패**, 실패한 것이 정확히 `중간에 꺼졌다 회복해도 가장 깊었던 골짜기를 기억한다` |
| RED (알림 빈도) | `--tests ShadowPortfolioTest` (Clock만 주입하고 빈도 규칙 없는 상태) | 종료 1 · **37개 중 4개 실패** (ⓐⓑⓒ + INFO 로그 동시 확인) |
| GREEN (알림 빈도) | 같은 명령 | 종료 0 |
| 복구 후 | 전체 재실행 | 종료 0 · **687/687** |

> 변이 검사가 중요한 이유: MDD 테스트가 "우연히 통과하는 테스트"가 아니라 **실제로 그 버그를 잡는다**는
> 증거다. 마지막 낙폭만 보는 흔한 오구현을 정확히 한 건만 골라 실패시켰다.

---

## 4. MDD·낙폭 계산식과 그것을 고정한 테스트

```
현재 낙폭(%) = (현재 총자산 - 전고점) / 전고점 × 100          ← 전고점은 ShadowPortfolio
최대 낙폭(%) = min over t [ (equity_t - runningMax_t) / runningMax_t ] × 100
               runningMax_t = max(equity_1 … equity_t)         ← 시계열(daily_equity) 기준
equity_t     = 그날 마감 총자산, 없으면 그날 시작 총자산
강제정지 문턱 = 전고점 × (1 - risk.mddLimit)
문턱까지 남은 금액 = 현재 총자산 - 강제정지 문턱
```

부호 규약: **낙폭은 음수**(아래로 내려간 정도). 한 번도 꺾이지 않았으면 `0.0`.
`mddLimitPercent`만 양수 한도값(`10.0`)이다 — `/api/params`와 부호를 맞추기 위해서다.

고정한 테스트:

| 케이스 | 테스트 이름 |
|---|---|
| 상승만 | `MaxDrawdown.only_rising_gives_zero` |
| 중간에 꺼졌다 회복 | `MaxDrawdown.dip_then_recovery_keeps_deepest_valley` (10M→12M→**9M**→13M ⇒ MDD −25%, 현재 낙폭 0%) |
| 주말 행 섞임 | `MaxDrawdown.weekend_rows_do_not_distort` (토·일 2행 포함 ⇒ MDD −25% 불변, `closedDays=1`) |
| 전고점 출처 | `peak_comes_from_shadow_portfolio_not_ledger` |
| 한도 출처 | `threshold_follows_risk_limits_properties` (0.20으로 바꾸면 문턱도 따라감) |
| 전고점 미확립 | `no_peak_yields_null_drawdown` |

---

## 5. 검산 — 누적 손익이 -212,040원인가

두 경로로 확인했다.

**① 테스트 픽스처 (운영 원장 축약본, 주말 행 포함)** —
`cumulative_pnl_matches_real_account` · `drawdown_and_threshold_match_real_account` 통과:

```
initialEquity 10,000,000 / currentEquity 9,787,960 / cumulativePnl -212,040 / -2.12%
peakEquity 10,088,806 / currentDrawdownPercent -2.98
forcedStopThreshold 9,079,925 / roomToThreshold 708,035
```

**② 운영 DB 사본 전체 47행으로 같은 식을 재계산** —
`backup/trading-db-20260921_220444.zip`(오늘 22:04 온라인 백업)을 임시 폴더에 풀어
H2 읽기 전용(`ACCESS_MODE_DATA=r`)으로 `daily_equity` 47행을 뽑아 같은 공식에 넣었다.
**운영 DB는 건드리지 않았고 실행 중인 앱도 손대지 않았다.**

| 항목 | 47행 전체 계산 | 기대값 | 일치 |
|---|---|---|---|
| `cumulativePnl` | -212,040 | -212,040 | ✅ |
| `cumulativeReturnPercent` | -2.12 | — | ✅ |
| `currentDrawdownPercent` | -2.98 | 약 -2.98 | ✅ |
| `maxDrawdownPercent` | -2.98 (가장 깊었던 날 2026-09-11) | — | ✅ |
| `forcedStopThreshold` | 9,079,925 | 9,079,925 (18_ops §2) | ✅ |
| `roomToThreshold` | 708,035 | 708,035 (18_ops §2) | ✅ |
| `closedDays` | **3** (09-16 · 09-17 · 09-21) | — | — |

⚠ **실행 중인 앱으로 실제 HTTP 호출은 하지 않았다** — 앱(PID 31624)은 이번 변경 이전 코드라
`/api/performance/account`가 아직 없다. 위 ②는 같은 공식을 같은 데이터에 적용한 독립 재계산이다.

⚠ **`netPnlSum`이 0인 것은 정상이다.** 일별 마감 기록(`DailyPnlRecorder`)은 2026-09-15에 생겼고,
그 뒤 계좌가 전액 현금으로 멈춰 있어 마감 3일 모두 순손익 0이다. **"하루하루의 합"이 아니라
"처음과 지금의 차이"(`cumulativePnl`)가 -212,040원의 정본**이다 — 이 둘을 헷갈리면 안 된다.

---

## 6. 300줄 상한 대응

| 파일 | 전 | 후 | 처리 |
|---|---|---|---|
| `DashboardController.java` | 233 | **204** | `/api/orders/filled`를 `OrderQueryController`로 이사(§1 표) |
| `OrderQueryController.java` (신규) | — | 136 | `/filled` + `/open` |
| `AccountPerformanceService.java` (신규) | — | 193 | 함수 최대 19줄 |
| `AccountPerformanceController.java` (신규) | — | 41 | |
| `ShadowPortfolio.java` | 144 | 155 | |

**생산 코드 전부 300줄 미만 · 함수 50줄 미만 · 중첩 3단계 미만.**

⚠ 테스트 `ShadowPortfolioTest.java`는 427 → **637줄**이 됐다(저장소 내 최대). 같은 클래스의 픽스처를
공유하고 기존 `@Nested` 구성을 그대로 따르는 편이 낫다고 판단해 분리하지 않았다.
이 저장소의 테스트는 원래 300줄을 넘는다(488·431·416·400…).
**리더 판단(2026-09-21): 그대로 둔다** — 길이 규칙 위반은 인지했고 `BACKLOG.md`의
[2026-08-14] 코딩 지침 준수 리팩터링 항목으로 따로 추적한다. 지금 쪼개면 이번 diff가 읽기 어려워진다.

---

## 7. 아키텍처 규칙 준수 확인

| 규칙 | 이번 변경 |
|---|---|
| Strategy→Signal→RiskEngine→OrderEngine | **무변경.** 이번 작업에 주문·신호 코드가 없다 |
| `RiskEngine` 무수정 / 새 `RiskRule` 없음 | 해당 없음 — 새 룰을 만들지 않았다 |
| 연동 인터페이스 시그니처 고정 | `MarketDataService`·`KisOrderClient`·`PositionManager`·`BrokerageApiClient` **전부 무변경** |
| 단일 청산 상태머신 | 무변경 |
| `@EnableScheduling` 위치 | 무변경 |
| 비밀키 | 코드에 없음 |
| 시간 판정 | `LocalDate.now()` 직접 호출 없음 — 주입한 KST `Clock`(`ClockConfig`)만 사용 |
| 불변성 | 응답 맵은 전부 새로 만들어 반환. 인자를 변형하는 곳 없음(헬퍼는 맵을 **돌려주고** 호출부가 `putAll`) |

**판정 로직 무변경 증거** — `ShadowPortfolio.tick()` diff에서 `!account.isFresh()`,
`current <= 0 || current <= peakEquity`, `calibrator.isImplausible(current)` 세 줄은 바이트 동일.
추가된 것은 `previous` 지역변수 1개 · `log.info` 1줄 · `notifyPeakRaised` 호출 1줄뿐이다.

**백테스트 격리** — `ShadowPortfolio`는 `@Profile({"paper","backtest"})`이고 `BacktestRunner:165`가
봉마다 `tick()`을 부른다. 그래서 알림 도구를 `ShadowPortfolio`에 직접 주입하지 않고
`PeakEquityCalibrator`(이미 프로필로 갈리는 자리)의 기본 무동작 메서드로 두었다.
`NoOpPeakEquityCalibrator`(backtest)는 아무것도 보내지 않는다 —
`application-backtest.yml`의 빈 토큰이 OS 환경변수에 지는 문제(BACKTEST-DESIGN §12)를 구조로 막았다.
테스트 `PeakRaiseAlert.backtest_never_notifies`가 이를 고정한다.

---

## 8. 남은 것 / 하지 않은 것

### 리더 판단 — 2건 모두 회신 받아 반영 완료 (2026-09-21)

1. **전고점 알림 빈도 → 기준 확정, 구현·테스트 완료.** 상세는 §10.
2. **`/api/performance/account`를 신규 `AccountPerformanceController`(`@Profile("paper")`)에 둔 것 → 승인.**
   `PerformanceController`에 paper 전용 빈을 주입했다면 backtest 기동이 깨지고, `DailyPnlController`
   선례와도 일치한다는 근거를 리더가 확인했다. URL은 `/api/performance/account` 그대로라 화면 작업에
   영향 없다. **되돌리지 않는다.**

### 하지 않은 것 (범위 밖)

- 화면(프런트엔드) 작업 일체 — 탭 추가, `/api/performance` 연결 해제, `chart-svg.js` 분리
- 모드 전환 이력·매수 차단 이력(`ModeTransition`, `RiskBlockRecord`) — 계획서 Step 2의 나머지
- 거래 기준 추정 성적(`SellPriceEstimator`, `TradeStatsService`) — 계획서 Step 3 2층
- 손익비 브래킷 백테스트(Step 5)
- 루트의 낡은 중복 `.java` 정리
- `PerformanceService`/`trade_result` 삭제 — **일부러 남겼다**(B-4 표본·감사 이력)

### 알려진 문제 (고치지 않고 보고만)

- **`gradle test`가 운영 로그 `logs/paper.log`에 이어 쓴다.** 마지막 전체 실행으로
  10,964,963 → 11,114,706 바이트(**+146KB**) 늘었다(이번 작업 중 전체 실행 3회 누적 약 +560KB). 실행 중인 앱의 로그와 테스트 픽스처 값이
  같은 파일에 섞인다(SCOPE_GUARDIAN.md가 이미 기록한 문제 — 09-01에 가짜 "🎉 5거래일 달성" 줄이
  섞였던 그것). **리더 확정: 별건으로 남긴다 — 고치지 않았다.**
- 실행 중인 앱(PID 31624, 8080)은 **끄지도 재시작하지도 않았다.** 따라서 이번 변경은
  **아직 배포 전**이다 — 다음 재기동부터 적용된다.
- Spring 컨텍스트 전체 기동 검증은 못 했다(`@SpringBootTest`가 저장소에 없고 앱을 재시작할 수 없다).
  대신 위험한 두 지점을 개별로 막았다: ① 새 파생 쿼리는 `@DataJpaTest`로 **실제 H2에서** 돌려
  이름 파싱·정렬·페이징·기간 필터를 확인했고(`OrderHistoryQueryTest` 3건 통과),
  ② URL 중복 매핑은 `/api/orders` 핸들러가 `OrderQueryController` 하나뿐임을 grep으로 확인했다
  (`DashboardController`에서 완전히 제거됨).

### 참고

- `BACKLOG.md` +37줄은 **리더가 쓴 것**(대시보드·브래킷 백테스트 등재). 내가 건드리지 않았다.

---

## 9. 되돌리기

전부 읽기 전용 조회 + 로그/알림이라 되돌려도 매매에 영향이 없다.

- 파일 단위: 신규 6개 삭제 + `git checkout` 수정 7개
- (A)만 되돌리려면: `ShadowPortfolio.tick()`의 `log.info` → `log.debug`,
  `calibrator.notifyPeakRaised(previous, current);` 한 줄 제거
- **발송 빈도만** 되돌려 "갱신마다 발송"으로 하려면: `EvidenceBasedPeakEquityCalibrator`의
  `alertedToday` 조기 반환 1줄 제거 (상수·상태 필드는 그대로 둬도 무해)

---

## 10. 전고점 텔레그램 발송 빈도 (리더 확정 2026-09-21)

### 원칙

**INFO 로그는 갱신될 때마다 전부 남긴다(수사 기록) · 텔레그램만 조인다(사람 호출).**
오염을 사후에 되짚는 건 로그의 일이고, 텔레그램은 "지금 이상한 일이 일어났다"를 알리는 용도다.
그래서 `ShadowPortfolio`의 로그 쪽은 **손대지 않았고**, 발송 판정만
`EvidenceBasedPeakEquityCalibrator.notifyPeakRaised`에 넣었다.

### 규칙 — 둘 중 하나라도 해당하면 1회 발송

| # | 조건 |
|---|---|
| ① | 그 **거래일의 첫 갱신**일 때 |
| ② | 그날 **직전 발송 시점의 전고점 대비 +1.0% 이상** 더 올랐을 때 (그때 기준값을 갱신) |

### 왜 이 기준인가

전고점은 신고점을 찍는 날 1초 간격으로 조금씩 계속 올라가므로, 갱신마다 보내면 하루 수십 건이 간다.
반대로 2026-09-21 사고처럼 **+7.94% 튀는 오염은 반드시 울려야 한다.** 이 문턱이 그 둘을 가른다 —
평범한 신고점 날은 **1~2회**, 오염은 **즉시**. 최악의 경우에도 하루 몇 건이다.

### 구현 메모

| 항목 | 결정 |
|---|---|
| 임계값 | `EvidenceBasedPeakEquityCalibrator.RENOTIFY_RISE_THRESHOLD = 0.010` — 이름으로 의도가 드러나는 상수. **설정 파일로 빼지 않았다**(지금 필요 없는 확장) |
| "거래일당 첫 갱신" | `LocalDate.now(clock).equals(lastAlertDate)` 비교. **주입한 KST `Clock`**(`ClockConfig`)만 쓴다 — `LocalDate.now()` 직접 호출 없음 |
| 상태 | 메모리 필드 `lastAlertDate` · `lastAlertPeak`. **영속화하지 않는다** — 재기동하면 그날 첫 발송이 한 번 더 갈 수 있고 그건 허용(DB 스키마 안 늘림) |
| 동시성 | `notifyPeakRaised`를 `synchronized`로 잠근다 — 발송 판정이 읽고-쓰기 한 묶음이라 |
| 메시지 기준값 | 그날 이미 알린 적이 있으면 "**마지막으로 알린 값**"을 기준으로 상승률을 표시한다. 잘게 쪼개져 오르는 오염을 한 칸짜리 상승률(+0.05% 등)로 축소해 보여주지 않기 위함 |

### 고정한 테스트 (`ShadowPortfolioTest$PeakRaiseAlert`, 12건 전부 통과)

| 요구 | 테스트 이름 | 기대 |
|---|---|---|
| ⓐ | `same_day_small_rises_notify_only_once` | 같은 날 +0.01%씩 3회 상승 → 발송 **1회** |
| ⓑ | `same_day_one_percent_more_notifies_again` | 같은 날 +0.5%(조용) 후 +1.0% → 발송 **2회** |
| ⓒ | `new_trading_day_notifies_again` | 9/21 발송 뒤 시계를 9/22로 넘기고 작은 상승 → 발송 **2회** |
| ⓓ | `backtest_never_notifies` | `NoOpPeakEquityCalibrator`로 조립 → 발송 **0회** (기존 테스트 유지) |
| 오염 | `contamination_sized_jump_always_alerts` | 같은 날 2번째여도 +7.93%(사고 실측값 10,890,158원) → **반드시 발송** |
| 로그 | `info_log_is_written_on_every_raise_even_when_silent` | 3회 갱신 → **INFO 로그 3줄** · 텔레그램 **1회** |

기존 6건(같은 값 재확인·하락·낡은 스냅샷·불가능 값·최초 확립·기본 경신)도 그대로 통과한다.

### Red-Green

```
RED   : Clock만 주입하고 빈도 규칙이 없는 상태 → 종료 1 · 37개 중 4개 실패
         (ⓐ ⓑ ⓒ + INFO 로그 테스트가 정확히 실패 — 발송이 3회씩 나갔다)
GREEN : 규칙 구현 후 같은 명령 → 종료 0
전체  : 687개 중 687개 통과 · 종료 코드 0
```

### 손대지 않은 것 (리더 지시)

- `ShadowPortfolio.tick()`의 판정 3줄 — `git diff`에 나타나지 않음을 재확인했다
  (`isFresh` / `current <= 0 || current <= peakEquity` / `isImplausible` 전부 무변경).
  이번 라운드에서 `ShadowPortfolio.java`는 **한 글자도 바뀌지 않았다**(변경은 캘리브레이터 쪽뿐).
- `ShadowPortfolioTest.java` 길이 · `BACKLOG.md` · `logs/paper.log` 오염 — 전부 그대로 둠.

---

## 11. 감사 후속 — M-1 · M-2 · L-1 (2026-09-22)

감사 `_workspace/20_audit_account-performance.md`(CRITICAL 0 · HIGH 0 · MEDIUM 3 · LOW 5)의
지적 3건을 고쳤다. **M-3(real 프로필 공백)는 증분 0·이월 관찰이라 손대지 않았다**(게이트 G2 안에서 다룰 일).

### M-1 — 장 밖 경보 사각지대 + 그날 1회 할당 조기 소모

**무엇이 문제였나.** 도장(`lastAlertDate`/`lastAlertPeak`)을 `sendCritical` **호출 전에** 찍었다.
그런데 `TelegramNotifier.send()`는 거래일 09:00~15:30 밖이면 조용히 스킵한다. `ShadowPortfolio.tick()`은
장 밖에도 1초마다 돌고 잔고 스냅샷에 장중 게이트가 없어 **08:30 자동 기동~09:00 구간의 갱신도 성립**한다
→ 경보는 한 통도 안 나가면서 그날 "첫 갱신 1회" 보장만 소모된다.
**실측 오염 2건이 둘 다 장 밖**이었고(09-09 세션 중 추정 · 09-11 **17:17** 17,047,935원),
2026-09-21 오염(+7.94%)은 `isImplausible` 상한 **아래**라 클램프도 안 걸린다 — 이 알림이 유일한 탐지선이다.

**어떻게 고쳤나.** 장중 전용 발송 규칙(2026-08 결정)은 **깨지 않았다.** 대신 경로를 하나로 합쳤다:

```
notifyPeakRaised → worthAlerting(기존 규칙: 거래일 첫 갱신 / 직전 발송 대비 +1.0%)
                 → hold(보류함 누적)
                 → flushIfDuringMarketHours()   ← 장중이면 즉시, 장 밖이면 그대로 둠
@Scheduled(fixedDelay = 60_000) flushPendingAlert() → flushIfDuringMarketHours()
```

| 항목 | 처리 |
|---|---|
| 도장 시점 | **내보낼 때만** 찍는다(`flushIfDuringMarketHours` 안). 장 밖에서는 안 찍으므로 다음 장중 첫 갱신이 1회를 그대로 보장받는다 |
| 보류함 | 값 하나가 아니라 **누적** — `pendingPrevious`(가장 이른 직전값) · `pendingCurrent`(가장 높은 새값) · `pendingCount` · 시각 범위(`pendingFirstAt`/`pendingLastAt`) · `pendingHeldOutOfHours` |
| 비우기 | `@Scheduled(fixedDelay = 60_000)` **이 클래스 안**. `@Profile("paper")`라 backtest에는 스케줄 자체가 생기지 않는다. 장중이 아니면 즉시 return |
| 메시지 | 장 밖에서 모인 것이면 `"장 밖(9/21 17:17 ~ 9/22 09:05, 3회)에 오른 것을 지금 알립니다."`를 앞에 붙인다 — 지금 오른 것으로 오해하지 않게 |
| 장중 단발 | `pendingHeldOutOfHours=false` → 안내 문구 없이 기존과 동일한 메시지 |

### M-2 — 1초 감시 루프 안의 블로킹 HTTP

**무엇이 문제였나.** `notifyPeakRaised`가 `synchronized` 안에서 동기 HTTP POST를 했다
(connect 3s + read 5s = 최대 8초). 스케줄러 풀이 1개라 같은 스레드에
`RiskMonitor`(MDD 강제청산)·`StopLossMonitor`(손절)가 올라가 있다 → **손절 감시 최대 8초 정지.**

**어떻게 고쳤나.** 전송만 전용 스레드로 뺐다 — 신규 `BackgroundAlertSender`(패키지 전용, 74줄).

| 항목 | 처리 |
|---|---|
| 스레드 | 단일 · **데몬** · 이름 `peak-alert-sender` · 30초 유휴 시 반납(`allowCoreThreadTimeOut`) |
| 큐 | `ArrayBlockingQueue(32)` 상한. 넘치면 **버리고 로그만**(알림이 메모리를 먹지 않게) |
| 예외 | 전송 예외는 안에서 삼키고 `log.warn` — 매매·감시로 전파하지 않는다 |
| 종료 | 캘리브레이터 `@PreDestroy shutdownSender()` → `alertSender.close()` |
| 스프링 빈 아님 | 쓰는 쪽이 직접 만든다 — 빈으로 두면 다른 곳이 끌어다 쓰며 경계가 흐려진다 |

⚠ **풀 크기는 건드리지 않았다**(직렬이라 안전했던 감시들이 동시에 돌기 시작한다).
**기존 동기 전송 5곳(`RiskMonitor` 3 · `ShadowPortfolioReconciler` 2)도 무변경** — `BACKLOG.md`
[2026-09-21] 별건. 이번에 새로 더한 블로킹만 0으로 만들었다.
⚠ `calibrate()`(기동 시 클램프 알림)는 **동기 그대로 뒀다** — `@PostConstruct` 경로라 1초 루프와
무관하고, 감사가 지적한 지점도 아니다.

### L-1 — 오도하는 catch 문구

`"tick 오류 — peakEquity 유지"` → **`"tick 오류 (peakEquity 현재값={})"`**.
알림에서 던진 경우엔 갱신·저장이 이미 끝난 뒤라 "유지"가 거짓이고, 사고 조사 때 "갱신 실패"로 읽힌다.

### 파일 300줄 상한 대응 (분리 계획)

구현을 다 넣자 `EvidenceBasedPeakEquityCalibrator`가 **314줄**이 됐다 → 주석을 깎는 대신 책임으로 쪼갰다.

| 파일 | 책임 | 줄 |
|---|---|---|
| `EvidenceBasedPeakEquityCalibrator` | 전고점 검증 + 발송 판정 · 보류함 | **268** |
| `BackgroundAlertSender` (신규, 패키지 전용) | 감시 스레드 밖 전송 (스레드 · 큐 · 종료) | **74** |

함수 최대 20줄 · 중첩 2단계. 다른 생산 파일 길이 변화 없음.

### 고정한 테스트 (`ShadowPortfolioTest$PeakRaiseAlert` — 12건 → **18건**, 전부 통과)

| 요구 | 테스트 이름 |
|---|---|
| 장 밖 갱신 → 발송 0회 · 도장 안 찍힘 (다음 장중 첫 갱신이 1회 보장) | `out_of_hours_raise_sends_nothing_and_keeps_daily_quota` |
| 장 밖 3회 누적(1,000만 → 1,020만 → 1,050만 → 1,089만) → 한 통, 가장 이른 직전값·가장 높은 새값 포함 | `held_out_of_hours_raises_are_sent_as_one_message` |
| 장중이 아니면 flush가 아무것도 안 함 (보류는 남아 다음 장중에 나감) | `flush_does_nothing_outside_market_hours` |
| 2026-09-21 오염 재현 (17:17 +7.94%) → 개장 후 반드시 한 통 | `contamination_out_of_hours_is_alerted_after_open` |
| 발송이 감시 스레드를 막지 않음 (호출 스레드 ≠ 전송 스레드) | `send_does_not_run_on_the_caller_thread` |
| backtest 0회 · `@Scheduled`도 안 생김 | `backtest_never_notifies` (유지) + `alerting_calibrator_is_paper_only` (신규 — `@Profile("paper")`와 `flushPendingAlert`의 `@Scheduled` 존재를 구조로 고정) |

기존 12건은 단언 내용을 그대로 두고 **검증 모드만** 비동기 대응으로 바꿨다
(`times(n)` → `timeout(2_000).times(n)`, `never()` → `after(200).never()`).
장중/장외는 `MarketCalendarService`를 **실객체로 조립**하고(휴장일 비움 = 토·일만 휴장) 기존
`MutableClock`만 움직여 제어한다 — 구체 클래스를 목으로 만들지 않았다(Java 25 제약).

### Red-Green

```
RED   : MarketCalendarService 주입 + flushPendingAlert 스텁만 있는 상태
        → 종료 1 · 43개 중 5개 실패 (장 밖 3건 + 오염 재현 + 스레드 분리 — 정확히 새 요구만)
GREEN : M-1·M-2 구현 후 같은 명령 → 종료 0
```

### 전체 테스트

```
[검증 증거]
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build       ~/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gvueggl8a5cioxuftxrik/gradle-9.2.0/bin/gradle       test --console=plain
종료 코드: 0
결과: 706개 중 706개 통과, 실패 0 / 에러 0 / 건너뜀 0 (테스트 클래스 103개)
→ 판정: 통과
```

⚠ 687 → 706의 +19 중 **내 증분은 +6**(`PeakRaiseAlert` 12→18)이다. 나머지 +13은 같은 시각 다른
에이전트가 올린 `com.trading.backtest`(BracketLab 등) 테스트다 — 나는 `backtest`·`static`을 건드리지 않았다.

### 지시 준수 확인

| 항목 | 결과 |
|---|---|
| `tick()` 판정 3줄 바이트 동일 | ✅ `git diff -U0`에 `isFresh` / `current <= 0 \|\| current <= peakEquity` / `isImplausible` **매치 0건** |
| `git diff -U0` 제거 줄 | 옛 `log.debug` 1줄 + **L-1 catch 문구 1줄 = 2줄.** 둘 다 판정 3줄이 아니다(L-1은 리더 승인 항목) |
| `NotificationService` 시그니처 | 무변경 |
| 파일 300줄 / 함수 50줄 / 중첩 3단계 | ✅ (위 분리 계획) |
| 앱 미정지 | ✅ 끄거나 재시작하지 않았다 — 배포는 리더가 개장 전에 |
| `backtest`·`static` 미접촉 | ✅ 범위 한정 `git status`로 확인 |

