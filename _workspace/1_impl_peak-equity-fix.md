# 1_impl_peak-equity-fix — 오염된 전고점(PEAK_EQUITY) 교정 + 재발 방지 가드

작성: 2026-08-12 / 담당: trading-implementer

## 한 줄 결론

가짜 전고점 1,302만원을 실측 근거값 1,000만원으로 정정했고(운영 DB 반영·확인 완료),
같은 오염이 다시 생기지 않도록 `ShadowPortfolio.tick()`에 신선도·실측 상한 가드를 넣었다.
전체 테스트 495개 통과(실패 0), 신규 회귀 테스트는 Red-Green으로 버그를 실제로 잡는지 확인했다.

## 1. 바꾼 파일

| 파일 | 변경 |
|---|---|
| `src/main/java/com/trading/position/PeakEquityCalibrator.java` | **신규** — daily_equity 실측 기록으로 "증거상 가능한 최대 자산"을 계산해 ① 저장값 교정(`calibrate`) ② 틱 갱신 차단(`isImplausible`) 제공. `@Component @Profile({"paper","backtest"})`, 생성자 주입 |
| `src/main/java/com/trading/position/DailyEquityRepository.java` | `findMaxStartEquity()` 추가 (`@Query("select max(d.startEquity) ...")`) — 인터페이스라 테스트에서 목 가능 |
| `src/main/java/com/trading/position/ShadowPortfolio.java` | ① `restore()`가 저장값을 실측과 대조해 교정·재영속화 ② `tick()`에 `account.isFresh()` 가드 + `calibrator.isImplausible()` 가드 + `current <= 0` 가드 추가. 생성자에 `PeakEquityCalibrator` 주입 |
| `src/test/java/com/trading/position/ShadowPortfolioTest.java` | **신규** — 오염 시나리오 회귀 테스트 9건 |
| `src/test/java/com/trading/risk/RiskMonitorTest.java` | `new ShadowPortfolio(...)` 생성자 인자 1개 추가(실측 기록 없음 목 → 기존 동작 유지). 그 외 무수정 |

건드리지 않은 것: `RiskEngine`·`RiskRule` 인터페이스, `LiquidationService` 상태머신,
`PositionManager`/`MarketDataService`/`KisOrderClient` 시그니처, `GlobalEquityStopRule`, 백테스트 설정.

## 2. PEAK_EQUITY를 어떤 값으로, 어떤 경로로 교정했나

**교정값: 10,000,000원** (오염값 13,027,929원 → 정정)

근거 (운영 DB `./trading-db` 직접 조회, 2026-08-12):

```
SELECT MAX(START_EQUITY) MX, MIN(START_EQUITY) MN, COUNT(*) C FROM DAILY_EQUITY;
MX    | MN        | C
1.0E7 | 9912930.0 | 24
```

24거래일 전체 이력에서 계좌가 도달한 최대(시작) 자산은 정확히 시드 자본 1,000만원이며
그 이상 간 기록이 없다. 즉 1,302만원은 실제로 도달한 적 없는 값이다. 실측으로 확인된
최대 자산이 유일하게 방어 가능한 전고점이므로 그 값을 채택했다.

**반영 경로는 두 겹 (둘 다 같은 값)**

1. **즉시 — 운영 DB 1회성 교정 (완료).** 앱이 가동 중이라 파일이 잠겨 있었지만
   `application-paper.yml`의 `AUTO_SERVER=TRUE` 덕분에 같은 DB에 별도 접속해
   `UPDATE PORTFOLIO_STATE SET STATE_VALUE=10000000 WHERE STATE_KEY='PEAK_EQUITY'` 실행.
2. **영구 — 기동 시 자동 교정 (코드).** `ShadowPortfolio.restore()`가 저장값을
   `PeakEquityCalibrator.calibrate()`에 통과시켜, 실측 근거상 불가능하면 검증된 최대
   자산으로 낮추고 다시 저장한다. 손으로 SQL을 만지지 않아도 재오염 시 스스로 복구된다.

1번만 하면 재발 시 다시 사람이 SQL을 쳐야 하고, 2번만 하면 재시작 전까지 매수가 계속
막힌다. 그래서 둘 다 했다. (가동 중인 앱의 메모리 값은 여전히 옛 1,302만이므로,
**실제 매수 재개는 앱 재시작 후**다 — 재시작하면 기동 재동기화가 자동으로 RUNNING까지 올린다.)

## 3. 가드의 정확한 조건

`ShadowPortfolio.tick()` — 아래 중 하나라도 걸리면 전고점을 갱신하지 않는다(기존 값 유지):

| 순서 | 조건 | 이유 |
|---|---|---|
| 1 | `!account.isFresh()` | 잔고 API 실패로 낡은 캐시/DB 폴백을 쓴 스냅샷. `RiskMonitor`(86행)·`StopLossMonitor`(91행)와 동일 패턴 |
| 2 | `current <= 0` | 폴백 근사로 총자산이 0/음수 |
| 3 | `current <= peakEquity` | 갱신 대상 아님 (기존 동작) |
| 4 | `calibrator.isImplausible(current)` | 신선해도 실측 근거상 불가능하게 큰 값 |

`isImplausible(x)` ≡ `x > MAX(daily_equity.start_equity) × 1.15`.
`daily_equity` 기록이 없거나(백테스트) 조회에 실패하면 **판정 보류(false)** — 근거 없이
안전장치 기준선을 건드리지 않고, 백테스트 결정성도 유지된다.

1.15배(장중 미기록 고점 허용폭)의 근거: `daily_equity`는 거래일 **시작** 자산만 남기므로
장중에 올랐다가 되밀린 진짜 고점은 기록에 없다. 그 여지는 남기되 물리적 상한을 넘지 않게 잡았다 —
`PositionLimitRule`(종목당 10%) × `MaxPositionCountRule`(최대 5종목) = 주식 노출 최대 50%,
국내 일일 가격제한폭 +30% → 하루에 오를 수 있는 총자산은 최대 약 15%.

`restore()`의 교정 조건도 같은 상한을 쓴다: 저장값이 상한을 넘으면 `MAX(start_equity)`로 낮춘다.
전고점이 낮아지는 방향은 MDD를 **작게** 계산해 청산이 덜 발동하는 쪽이므로, 근거 없는 하향을
막으려고 "명백히 불가능할 때만" 발동하도록 문턱을 뒀다.

## 4. 테스트 목록과 실행 결과

신규 `ShadowPortfolioTest` 9건 (+ 기존 `RiskMonitorTest` 12건 회귀):

1. 낡은(폴백) 스냅샷은 전고점을 갱신하지 못한다
2. 신선하지만 실측 근거상 불가능한 총자산도 전고점을 갱신하지 못한다 ← 실제 오염값 13,027,929 재현
3. 신선하고 근거 범위 안인 총자산은 전고점을 갱신·영속화한다
4. 총자산 0(폴백 근사)은 전고점을 갱신하지 않는다
5. 실측 기록이 없으면(백테스트) 검증을 보류하고 기존대로 갱신한다
6. 기동 시 오염된 전고점을 검증된 최대 자산으로 교정하고 다시 저장한다
7. 근거 범위 안의 전고점은 그대로 복원하고 덮어쓰지 않는다
8. 실측 기록이 없으면 저장된 전고점을 임의로 건드리지 않는다
9. 교정 후에는 현재 자산 999만이 MDD 한도 10% 안에 들어온다 (사고의 최종 증상 검증)

### [검증 증거] 전체 스위트

```
명령: gradle test --console=plain   (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build)
종료 코드: 0
결과: 75개 클래스 / 495개 테스트 중 495개 통과, 실패 0, 에러 0, 스킵 0
→ 판정: 통과
```

(집계: `C:/Users/SAMSUNG/auto_trading-build/test-results/test/TEST-*.xml` 합산.
`ShadowPortfolioTest` tests=9 failures=0 errors=0 / `RiskMonitorTest` tests=12 failures=0 errors=0,
둘 다 이번 실행 타임스탬프 09:04:58)

### [검증 증거] Red-Green (가드가 실제로 버그를 잡는지)

가드를 임시로 무력화(`isFresh` 검사와 `isImplausible` 검사를 `if (false)`로, `calibrate()` 호출을
저장값 그대로 반환으로) 후 재실행:

```
명령: gradle test --tests "com.trading.position.ShadowPortfolioTest" --console=plain
결과: 9 tests completed, 4 failed
  - 기동 시 오염된 전고점을 검증된 최대 자산으로 교정하고 다시 저장한다 FAILED
  - 교정 후에는 현재 자산 999만이 MDD 한도 10% 안에 들어온다 FAILED
  - 신선하지만 실측 근거상 불가능한 총자산도 전고점을 갱신하지 못한다 FAILED
  - 낡은(폴백) 스냅샷은 전고점을 갱신하지 못한다 FAILED
BUILD FAILED
```

가드 복구 후 위 "전체 스위트" 실행에서 전부 통과 — RED→GREEN 확인 완료.

## 5. DB가 실제로 정정됐는지 확인한 방법과 결과

앱이 DB 파일을 잠그고 있어 일반 접속은 `Database may be already in use`로 거부됐고,
`AUTO_SERVER=TRUE`(paper 설정) 덕에 같은 DB에 별도 세션으로 붙어 처리했다.

```
$ java -cp h2-2.2.224.jar org.h2.tools.Shell \
    -url "jdbc:h2:file:./trading-db;AUTO_SERVER=TRUE" -user sa -password "" \
    -sql "UPDATE PORTFOLIO_STATE SET STATE_VALUE = 10000000 WHERE STATE_KEY = 'PEAK_EQUITY'; ..."
(Update count: 1, 0 ms)

# 새 세션으로 재조회 (독립 확인)
STATE_KEY            | STATE_VALUE
PEAK_EQUITY          | 1.0E7
RUN_STREAK_DAYS      | 7.0
RUN_STREAK_LAST_DATE | 2.0260811E7
```

교정 전 조회값은 `PEAK_EQUITY | 1.3027929E7`이었다. 다른 두 행은 손대지 않았다.

## 6. 남긴 한계 · 후속

1. **매수 재개는 앱 재시작이 필요하다.** 가동 중인 프로세스의 메모리 전고점은 아직 옛 1,302만원이다
   (DB만 고쳐도 실행 중 인스턴스는 다시 읽지 않는다). 재시작하면 `restore()`가 1,000만원을
   읽고, `ShadowPortfolioReconciler.onStartup()`이 RUNNING으로 올린다.
2. **1.15배 상한은 리스크 룰 파라미터에서 유도한 상수**다(`PeakEquityCalibrator.INTRADAY_HEADROOM`).
   종목당 비중 10%/최대 5종목이 바뀌면 같이 재검토해야 한다. 지금은 설정값이 아니라 상수다.
3. **입금 등으로 자산이 갑자기 늘면 그날 하루는 전고점 갱신이 보류**된다(다음 날 `start_equity`가
   새 수준을 기록하면 상한이 따라 올라감). 전고점이 낮은 쪽으로 잠깐 보수적일 뿐, 헛청산 방향은 아니다.
4. **오염이 처음 어떻게 들어왔는지는 미확정.** 낡은 스냅샷 경로였는지, 잔고 API가 신선하지만
   잘못된 총자산을 준 것인지 로그로는 특정하지 못했다(가드는 두 경우 다 막는다). 재현이 필요하면
   `KisBalanceClient`의 총자산 합산 필드 감사가 다음 후보다 — 이번 요청 범위 밖이라 손대지 않았다.
5. `daily_equity`는 하루 1행(시작 자산)만 남아 사후 검증 해상도가 낮다. 장중 최고 자산을 별도
   기록하면 상한 추정 없이 검증할 수 있다 — 범위 밖, 제안만 남긴다.

---

# 부록 A — risk-auditor 감사 반영 (재작업 1/3회차, 2026-08-12)

감사 `_workspace/2_audit_peak-equity-fix.md`의 **HIGH 2건 해소 + MEDIUM-1(백테스트 영향) 동시 해소**.

## A-1. HIGH-1 — 교정 과잉 하향 수정

| 전 | 후 |
|---|---|
| 상한 초과 시 `verifiedMax`(1,000만)로 슬래시 | 상한 `ceiling = verifiedMax × (1+허용폭)`(1,150만)으로 **클램프** |
| 원값 소실 | `portfolio_state.PEAK_EQUITY_RAW_BEFORE_CALIBRATION`에 **원값 보존**(덮어쓰기 전에 저장) |
| `log.error`만 | `log.error` + `notifier.sendCritical(...)` (`ShadowPortfolioReconciler.correctFromBroker` 패턴) |

전고점 하향은 MDD 분모를 낮춰 `GlobalEquityStopRule`·`RiskMonitor` **양쪽을 동시에 느슨하게** 하므로,
"오염 차단에 필요한 최소치"인 상한까지만 낮춘다.

> ⚠ **중요한 상호작용(리더·감사자 확인 필요)**: 클램프 정책에서는 자동 교정만으로는
> 이번 계좌의 매수 게이트가 **안 열린다** — 전고점 1,150만 대비 현재 자산 9,992,814원은 MDD 13.1%로
> 여전히 한도 10% 초과다. 실제로 게이트를 연 것은 §5의 **1회성 DB 교정값 10,000,000원**이며,
> 이 값은 상한(1,150만) 이하라 재기동해도 `calibrate()`가 건드리지 않는다(테스트로 고정:
> "운영 DB에 넣은 1,000만원 전고점이면 현재 자산 999만은 MDD 한도 10% 안이다").
> 즉 **자동 교정 = 보수적 오염 차단, 실측 복구 = 사람이 근거를 확인한 1회성 조치**로 역할이 갈린다.

## A-2. HIGH-2 — 허용폭을 리스크 파라미터에서 유도

`INTRADAY_HEADROOM = 0.15` 하드코딩 제거. 이제 매 호출마다 `RiskLimitsProperties`에서 유도한다:

```java
double exposure = Math.min(1.0, limits.getMaxPositionWeight() * limits.getMaxPositionCount());
return exposure * DAILY_PRICE_LIMIT;      // DAILY_PRICE_LIMIT = 0.30 (국내 일일 가격제한폭)
```

- 기본값(0.10 × 5종목 = 노출 50%) → 허용폭 15% (기존과 동일)
- 설정 UI에서 0.30으로 올리면(0.30 × 5 = 노출 100%) → 허용폭 30% — 정당한 신고점을 오판하지 않는다
- 노출은 `min(1.0, ...)`으로 100%를 넘지 못한다 → 허용폭 상한 30%

## A-3. MEDIUM-1 — 백테스트 영향 차단 (프로필별 구현체 교체)

감사 지적대로 `BacktestPositionManager.computeDailyPnl`(95~96행)이 시뮬 날짜마다 `daily_equity`를
저장하므로, 검증을 그대로 켜면 백테스트 판단이 달라질 수 있었다. 24창 재실행 대신 **구조로 차단**했다
(코딩 컨벤션 "인터페이스 뒤에 숨기고 프로필로 구현체 교체"):

| 타입 | 프로필 | 동작 |
|---|---|---|
| `PeakEquityCalibrator` (인터페이스) | — | `calibrate` / `isImplausible` 계약만 정의 |
| `EvidenceBasedPeakEquityCalibrator` | `paper` | daily_equity 실측 검증 + 클램프 + 알림 |
| `NoOpPeakEquityCalibrator` | `backtest` | 저장값 그대로, 항상 `isImplausible=false` |

→ 백테스트 경로에는 검증 코드가 아예 주입되지 않으므로 G0 앵커(§14.4) 재현성에 **영향 없음**.
테스트로도 고정: "무검증 구현체로 조립하면 tick 갱신 동작이 변경 전과 같다".

## A-4. 추가로 바뀐 파일 (부록 A 범위)

| 파일 | 변경 |
|---|---|
| `src/main/java/com/trading/position/PeakEquityCalibrator.java` | 클래스 → **인터페이스**로 변경 |
| `src/main/java/com/trading/position/EvidenceBasedPeakEquityCalibrator.java` | **신규** — paper 구현체 (실측 검증·클램프·원값 보존·sendCritical, 허용폭 파라미터 유도) |
| `src/main/java/com/trading/position/NoOpPeakEquityCalibrator.java` | **신규** — backtest 무검증 구현체 |
| `src/main/java/com/trading/position/PortfolioState.java` | `KEY_PEAK_EQUITY_RAW_BEFORE_CALIBRATION` 상수 추가 |
| `src/test/java/com/trading/position/ShadowPortfolioTest.java` | 9건 → **19건**(경계값 3, 허용폭 유도 3, 클램프/보존/알림 3, 백테스트 2 포함) |
| `src/test/java/com/trading/risk/RiskMonitorTest.java` | 조립을 `NoOpPeakEquityCalibrator`로 단순화 |

`ShadowPortfolio.java`는 인터페이스 타입만 그대로 주입받으므로 부록 A에서 **추가 수정 없음**.

## A-5. 검증 증거 (부록 A — 이번에 직접 실행)

```
[검증 증거] 전체 스위트
명령: gradle test --rerun-tasks --console=plain   (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build)
종료 코드: 0
결과: 78개 클래스 / 505개 테스트 중 505개 통과, 실패 0, 에러 0, 스킵 0
→ 판정: 통과
(ShadowPortfolioTest 본체 + 중첩 3클래스 XML 타임스탬프 09:23:36 — 이번 실행분)
```

```
[검증 증거] Red-Green — HIGH-1/HIGH-2 수정이 실제로 검사되는가
수정을 임시로 되돌림: ① clamped = verifiedMax ② intradayHeadroom() = 0.15 고정
명령: gradle test --tests "com.trading.position.ShadowPortfolioTest*" --console=plain
결과: 19 tests completed, 4 failed
  - 기동 시 오염된 전고점을 '검증된 최대 자산'이 아니라 상한까지만 낮춘다 FAILED
  - 클램프 시 원값을 별도 키로 보존하고 교정값을 영속화한다 FAILED
  - 비중을 30%로 올리면 허용폭도 따라 올라 정당한 신고점을 막지 않는다 FAILED
  - 노출은 100%를 넘지 못한다 — 허용폭 상한은 30% FAILED
BUILD FAILED
→ 수정 복구 후 위 전체 스위트에서 505/505 통과 (RED→GREEN 확인)
```

```
[검증 증거] 운영 DB 재확인 (부록 A 작업 후, 별도 H2 세션)
STATE_KEY            | STATE_VALUE
PEAK_EQUITY          | 1.0E7          ← §5 교정값 유지 (상한 1,150만 이하라 재기동해도 불변)
RUN_STREAK_DAYS      | 7.0
RUN_STREAK_LAST_DATE | 2.0260811E7
(RAW 키는 아직 없음 — 자동 클램프가 한 번도 발동하지 않았다는 뜻. §5는 사람이 한 1회성 SQL 교정)
```

## A-6. 감사 지적 중 이번에 처리하지 않은 것

| 심각도 | 항목 | 처리 |
|---|---|---|
| MEDIUM | 검증 근거(start_equity)가 오염원(잔고 totalAssetValue)과 비독립 | **미처리** — 근본 해결은 잔고 파싱 감사 또는 장중 고점 별도 기록이 필요하다. 이번 요청 범위 밖이라 기록만 남긴다 |
| MEDIUM | 메모리 값 잔여 창 / 재시작 전 RUNNING 전환 금지 | **운영 지침으로 전달** — 코드 변경 아님 |
| MEDIUM | 재시작이 사람 게이트 없이 RUNNING (2026-07-16 사용자 정책) | **미처리** — 정책 변경은 사용자 승인 사항 |
| LOW | real 프로필에 전고점·MDD 게이트 부재(기존 상태) | **미처리** — 실전 전환(G2) 선행 사항 |
