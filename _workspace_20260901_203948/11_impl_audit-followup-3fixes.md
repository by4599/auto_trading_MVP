# 11_impl — 감사 후속 MEDIUM 3건 구현 (2026-08-21)

대상 감사: `2_audit_peak-equity-fix.md`(재감사 신규 MEDIUM) · `9_audit_anchor-sot-and-coverage.md`(LOW/MEDIUM 스코프 혼선) · `2_audit_manual-buy-drill.md`(MEDIUM 프로필 화이트리스트)
브랜치: `backtest/regime-filter-and-validation` · 전량 미커밋

## 한 줄 결론

3건 모두 구현하고 red-green으로 확인했다 — **599 테스트 전부 통과(실패 0)**, 각 수정을 임시로 되돌리면 해당 테스트가 실제로 깨진다.

---

## 작업 1 — 클램프된 전고점이면 MDD 자동 강제청산만 보류

### 먼저 사실 정정 (지시문과 코드가 다른 부분)

지시문은 "GlobalEquityStopRule이 여전히 자동 강제청산을 발동시킨다"고 했지만, **코드상 자동
강제청산 트리거는 `RiskMonitor`다**(`GlobalEquityStopRule`은 매수 거부 전용 — 두 클래스의
주석에도 역할 분리가 명시돼 있다). 따라서 **보류 로직은 `RiskMonitor`의 MDD 분기에 넣었고**,
`GlobalEquityStopRule`은 매수 차단을 그대로 둔 채 거부 사유 문구에만 보류 사실을 덧붙였다.

### 무엇을 어떻게 바꿨나

| 파일 | 변경 |
|---|---|
| `position/PortfolioState.java` | 키 상수 `KEY_PEAK_EQUITY_UNVERIFIED` 추가 (1=미검증, 0=사람 확인 완료) |
| `position/ShadowPortfolio.java` | 클램프 발생 시 `markPeakUnverified()`로 표시·영속화, 기동 시 표시 복원(`restoreUnverifiedMark`), `isPeakUnverified()` / `acknowledgePeakEquity()` 공개 |
| `risk/RiskMonitor.java` | MDD 한도 초과 + 전고점 미검증이면 **청산 트리거 대신 보류**(1회 텔레그램 통지 후 로그만) |
| `risk/GlobalEquityStopRule.java` | 매수 거부는 그대로. 거부 사유에 "미검증 — 자동청산 보류 중" 문구만 추가 |
| `control/TradingController.java` | `POST /api/trading/peak-equity-ack` (확인 문자열 `CONFIRM_PEAK_EQUITY`) — 사람 확인 신호 |

### 설계 판단 근거

1. **별도 boolean 플래그를 새로 만들지 않았다.** 청산 상태머신(`LiquidationPhase`)은 **한 줄도
   건드리지 않았고**, 새 상태·새 플래그도 추가하지 않았다. "미검증"은 청산 진행 상태가 아니라
   **전고점 값의 신뢰도**이므로, 전고점의 소유자인 `ShadowPortfolio`가 기존 key-value 저장소
   (`portfolio_state`, 이미 `PEAK_EQUITY_RAW_BEFORE_CALIBRATION`을 쓰던 그 구조)에 기록한다.
   `RiskMonitor`는 이미 `ShadowPortfolio`를 주입받고 있어 **생성자 시그니처 변경이 없다.**
2. **영속화가 필요한 이유.** 클램프는 기동 시 1회만 일어난다. 메모리 플래그였다면 재시작 한 번으로
   사람 확인 없이 자동청산이 되살아난다(= 게이트가 아니게 된다). 그래서 DB에 남기고 기동 시 복원한다.
   테스트 `mark_survives_restart`가 이를 고정한다.
3. **보류 범위를 MDD 청산 하나로 좁혔다.** 보류 중에도 ① 신규 매수 차단(`GlobalEquityStopRule`)
   ② 일일 손실 -5% 강제청산(`RiskMonitor` 1번 분기) ③ 종목별 ATR 손절(`StopLossMonitor`)은
   전부 그대로 동작한다. 테스트 `unverified_peak_does_not_hold_daily_loss_liquidation`로 고정.
4. **왜 보류가 안전한 방향인가.** 클램프는 "저장된 전고점이 실측 근거상 불가능"할 때만 일어나고,
   낮춘 값(ceiling)도 여전히 실제보다 높다. 그 값으로 계산한 MDD는 **과대평가**이므로 그대로
   청산하면 헛청산이다(실측: ceiling 11,500,000 vs 실자산 10,000,000 → MDD 13.04% > 한도 10%).
5. **알림 폭주 차단.** `RiskMonitor`는 1초 주기라 보류 통지를 보류 시작 시 1회만 보낸다
   (`unverifiedPeakNoticeSent`). 테스트로 고정(`times(1)`).

### ⚠ 이 선택의 위험 (감사자가 반드시 볼 것)

**보류는 방어를 한 칸 내려놓는 변경이다.** 전고점이 미검증인 동안 "진짜로" 자산이 10% 넘게
빠져도 MDD 자동청산은 돌지 않는다. 남은 방어선은 일일손실 -5% 청산 · 종목별 손절 · 매수 차단
세 가지뿐이며, 사람이 텔레그램을 못 보면 보류가 무기한 지속된다(만료 시각 없음).
"보류 후 N시간이면 자동 청산 복귀" 같은 타임아웃은 **의도적으로 넣지 않았다** — 타임아웃은
결국 오염된 값으로 헛청산을 내는 경로를 되살리기 때문이다. 이 판단은 재검토 대상이다.

---

## 작업 2 — 커버리지 관문의 스코프 대조

| 파일 | 변경 |
|---|---|
| `backtest/CoverageScope.java` (신규) | 검사 대상 식별 키 `record(mode, symbols, windowEnd)` + `coversSameDataAs` |
| `backtest/CandleCoverage.java` | 첫 컴포넌트를 `windowEnd` → `scope`로 교체, `windowEnd()`는 접근자로 유지(출력 문자열 불변) |
| `backtest/CandleCoverageChecker.java` | `verify()`가 스코프를 만들어 결과에 실어 보관, `check(CoverageScope)`로 시그니처 정리 |
| `backtest/BacktestReportWriter.java` | 관문에 3번째 조건 추가 — 커버리지가 검사한 스코프 ≠ 지금 채점한 스코프면 **기록 거부**, 사유에 양쪽 스코프를 찍는다 |

### 설계 판단 근거

- **동일성 판정은 `종목 집합 + 창 끝`으로만 한다. `mode`는 비교에서 뺐다.** 커버리지가 답하는
  질문이 "이 종목들의 캔들이 이 날짜까지 있는가"뿐이고, 실제 코드에 **같은 데이터로 돌면서 이름만
  바뀌는 정당한 경로가 있기 때문**이다: `LabScope.withNames(...)`(DonchianLab이 리포트 직전에
  슬러그를 `-SENS`/`-SIZING`으로 바꿔 넘김), full 모드의 빈 fileSlug(verify는 `"full"`).
  mode를 동일성에 넣으면 **정당한 기준선 갱신이 막힌다**. 대신 mode는 리포트/거부 사유에 찍어
  사람이 읽을 수 있게 남겼다. 테스트 `writes_whenOnlyModeLabelDiffers`가 이 경계를 고정한다.
- 종목은 순서 무관 **집합** 비교(부분집합은 불일치 = 거부, fail-closed).
- 대조는 커버리지 충분/미달 판정보다 **먼저** 한다 — 다른 대상을 본 검사라면 그 충분/미달 결론
  자체가 이 기준선과 무관하기 때문이다.
- 기존 3개 기록 경로(full / single-strategy / risk-lab)는 전부 `verify`와 같은 `symbols`·`to`를
  쓰므로 이번 변경으로 막히지 않는다(코드 대조 확인).

---

## 작업 3 — DrillService 프로필 가드

| 문제 | 변경 |
|---|---|
| A: `liquidate()`에 paper 가드 없음 | `manualBuy()`와 동일한 `isPaperProfile()` 가드를 맨 앞에 추가 |
| B: `contains("paper")`가 `paper,real` 동시 활성을 통과 | 조건을 `!contains("real") && contains("paper")`로 전환 |

**B에서 "paper 명시"를 함께 남긴 이유**: 지시문은 "real 미포함"만으로 전환하라고 했으나, 그렇게만
하면 **프로필 미설정(빈 배열)이 통과**해 기존 fail-closed 성질(2_audit에서 PASS로 확인된 항목)이
사라진다. 리허설은 실주문을 내는 도구이므로 더 보수적인 쪽(둘 다 요구)을 택했다.
결과적으로 real이 하나라도 있으면 paper 유무와 무관하게 무조건 차단이라는 요구는 충족한다.

---

## 검증 증거 (이번에 직접 실행)

```
[검증 증거 — 전체]
명령: gradle test --rerun-tasks --console=plain   (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build)
종료 코드: 0 (BUILD SUCCESSFUL in 31s)
결과: 91개 클래스 / 599개 테스트 전부 통과, 실패 0 · 에러 0 · 스킵 0
→ 판정: 통과
```

이번에 건드린 클래스별 (같은 실행의 XML 집계):
`DrillServiceTest 6/0` · `GlobalEquityStopRuleTest 3/0` · `BaselineWriteGateTest 7/0` ·
`ShadowPortfolioTest 11/0 (+ 중첩 UnverifiedMark 6/0, CeilingBoundary 3/0, HeadroomDerivation 3/0, BacktestCalibrator 2/0)` ·
`RiskMonitorTest 14/0` · `CandleCoverageCheckerTest 6/0` · `TradingControllerTest 21/0`

### Red-Green (수정을 임시로 되돌려 테스트가 버그를 잡는지 확인 → 즉시 복원)

| 되돌린 수정 | 실행 | 결과 |
|---|---|---|
| DrillService: liquidate 가드 제거 + `contains("paper")` 복원 | `--tests DrillServiceTest` | **6개 중 4개 FAILED** (real 청산 / 빈 프로필 청산 / paper+real 청산 / paper+real 매수) |
| BacktestReportWriter: 스코프 대조 블록 삭제 | `--tests BaselineWriteGateTest` | **7개 중 2개 FAILED** (다른 창 · 다른 종목 집합) |
| RiskMonitor: 보류 분기 삭제 | `--tests RiskMonitorTest` | **14개 중 1개 FAILED** (`unverified_peak_holds_mdd_liquidation_and_notifies_once`) |
| ShadowPortfolio: `markPeakUnverified()` 호출 삭제 | `--tests ShadowPortfolioTest` | **25개 중 4개 FAILED** (표시·해제·보류근거·저장 3건 카운트) |

복원 후 전체 재실행이 위의 599/599 통과다.

---

## 아키텍처 규칙 자가 점검

- 흐름(Strategy→Signal→RiskEngine→OrderEngine): **미변경** — 주문 경로에 손대지 않았다
- `RiskEngine` **무수정**, 새 `RiskRule`도 추가하지 않았다(기존 룰의 사유 문구만 변경)
- ADR-001 청산 단일 상태머신: `LiquidationService`·`LiquidationPhase` **diff 0**, 새 플래그 없음
- 연동 인터페이스(`MarketDataService`/`KisOrderClient`/`PositionManager`/`BrokerageApiClient`) 시그니처 **불변**
- 비밀키 하드코딩 없음. 테스트의 `test-key/test-secret`은 기존 픽스처 재사용
- 백테스트 결정성: `backtest` 프로필에서 미검증 표시는 **절대 켜지지 않는다**
  (`NoOpPeakEquityCalibrator`가 클램프를 안 하고, `RiskMonitor`는 paper 전용).
  `CoverageScope`는 `backtest` 전용 패키지 안에서만 산다
- 요청 범위 밖 코드 미변경. 워킹트리에 있던 남의 변경(`BACKLOG.md`, `Claude.md`,
  `application-paper.yml`, `mirror/` 패키지, `docs/supabase-schema.sql`)은 손대지 않았다

## 남은 불확실성 · 확신 없는 부분

1. **보류에 만료가 없다**(위 ⚠ 참고). 사람이 알림을 놓치면 MDD 자동청산이 무기한 꺼진 채로 남는다.
   운영 판단이 필요하다 — 대시보드에 "보류 중" 배지를 띄우는 것을 권고하지만 이번엔 **UI를 만들지
   않았다**(범위 밖). 지금은 텔레그램 1회 통지 + 매수 거부 사유 문구가 유일한 표시다.
2. **`peak-equity-ack` 엔드포인트는 런타임 검증을 못 했다.** `TradingController`에 `ShadowPortfolio`
   의존이 하나 늘었고(순환 없음, paper에 빈 존재), 단위 테스트는 통과하지만 **실제 기동 확인은
   paper 부팅이 최초 검증**이다(저장소에 `@SpringBootTest`가 없음). 참고: 이 컨트롤러는 이미
   `ShadowPortfolioReconciler(@Profile("paper"))`에 의존하고 있어 real 프로필 기동은 이번 변경
   이전부터 성립하지 않는다 — **real 공백은 기존 상태이고 이번에 악화시키지 않았다.**
3. `peak-equity-ack`의 **성공 경로(표시 있음 → 해제)는 컨트롤러 레벨 테스트가 없다.**
   `ShadowPortfolio.restore()`가 패키지 접근이라 `com.trading.control` 테스트에서 표시를 만들 수
   없기 때문이다. 대신 상태머신 자체는 `ShadowPortfolioTest$UnverifiedMark`에서 전부 검증했고,
   컨트롤러는 확인 문자열 불일치·보류 없음 두 경로만 테스트했다.
4. **운영 DB 현재 값에는 영향 없다.** 지금 저장된 전고점은 10,000,000(상한 이하)이라 다음 기동에도
   클램프가 발생하지 않고, `PEAK_EQUITY_UNVERIFIED` 행도 없으므로 표시는 꺼진 채 시작한다
   (= 기존과 동일 동작). 실제 보류 경로는 오염이 재발해야 발동한다.
5. 작업 2의 스코프 대조는 **정적 대조로만 확인**했다(백테스트 실행 미수행). 기존 3개 기록 경로가
   같은 `symbols`·`to`를 쓴다는 것은 코드 직독 근거이며, risk-lab 실제 실행으로 재확인하지 않았다.
