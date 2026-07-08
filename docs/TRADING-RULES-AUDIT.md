# 트레이딩 규칙 검증 보고서 (2026-07-07)

> 대상: `com.trading.risk` 7개 룰 + `VolatilityBreakoutStrategy` + `LiquidationService`
> 방법: 소스 전수 리뷰 (ADR-001 · README · Claude.md 대조)
> 결론 요약: **파이프라인 구조(Strategy→Signal→RiskEngine→OrderEngine)와 청산 상태머신은 설계대로 구현됨.
> 그러나 계좌 단위 안전장치(MDD·일일손실)는 현재 구조로는 실전에서 작동하지 않거나 오작동한다. 아래 CRITICAL 4건은 실전 전환 전 반드시 해소해야 한다.**

---

## 등록된 리스크 룰 현황 (paper 프로파일 기준 7개)

| # | 룰 | 문서상 조건 | 실제 동작 상태 |
|---|---|---|---|
| 1 | `PendingOrderRule` | 보유 중/미체결 매수 존재 시 중복 매수 차단 | ✅ 정상 |
| 2 | `PositionLimitRule` | 종목당 비중 10% 초과 매수 차단 | ✅ **해소 (Gate 1)** — 분모가 현금 포함 총자산으로 교정됨 (F-7=F-1 동일 원인) |
| 3 | `MaxPositionCountRule` | 보유 5종목 이상 매수 차단 | ✅ 정상 (매도 완료 시 포지션 행 삭제 확인, [FillStateUpdater.java:223](../src/main/java/com/trading/order/FillStateUpdater.java)) |
| 4 | `MarketCloseRule` | 15:20 이후 신규 매수 금지 | ✅ 동작하나 타임존 의존 (F-8) |
| 5 | `DailyLossRule` | -3% 매수차단 / -5% 강제청산 | ✅ **활성 (Gate 1)** — dailyPnl 실값 연동 + `RiskMonitor` 상시 감시 |
| 6 | `GlobalEquityStopRule` | 전고점 대비 MDD 10% 초과 시 강제청산 | ✅ **해소 (Gate 1)** — 현금 포함 equity + 신호 독립 감시 |
| 7 | `ConsecutiveLossRule` | 연속 손실 3회 시 1시간 중지 | 🔴 비활성 — 입력값 0 하드코딩, 매도 체결 데이터 필요 (Gate 3에서 F-4와 함께) |

> README의 "6대 리스크 룰"은 실제와 불일치 — `PendingOrderRule` 포함 **7개**가 RiskEngine에 주입된다 (F-12).

---

## CRITICAL — 실전 전환 전 필수 해소

### F-1. MDD 가드(GlobalEquityStopRule)가 오탐과 미탐을 동시에 일으킨다

> ✅ **해소 (2026-07-07, Gate 1)**: `KisBalanceClient`(잔고조회 VTTC8434R) 신설 —
> 총자산 = `tot_evlu_amt`(예수금 포함), 현재가 = KIS 실시간가. 3초 TTL 캐시로 레이트리밋 대응.
> `RiskMonitor`·`GlobalEquityStopRule`에 totalAssetValue ≤ 0 가드 추가로 폴백 시 오탐 차단.
> 검증: `KisPositionManagerTest` 6/6, `RiskMonitorTest` 9/9 통과.

- 위치: [GlobalEquityStopRule.java](../src/main/java/com/trading/risk/GlobalEquityStopRule.java), [KisPositionManager.java:45-69](../src/main/java/com/trading/position/KisPositionManager.java), [Account.java](../src/main/java/com/trading/position/Account.java)
- 원인 1 — **현금 미포함**: `totalAssetValue`가 보유 포지션 평가금액의 합만 계산한다 (예수금 제외).
- 원인 2 — **현재가 부재**: `currentPrice = averagePrice`로 근사하므로 가격이 아무리 하락해도 평가금액이 변하지 않는다.
- 결과:
  - **미탐**: 실제 주가가 -30% 폭락해도 평가금액은 평단가 그대로 → MDD가 절대 감지되지 않는다.
  - **오탐**: 포지션을 매도해 현금화하는 순간 `totalAssetValue`가 감소한다. 예: 삼성전자 1주(8만원) 보유 → peakEquity=80,000 → 전량 매도 → equity=0 → drawdown 100% → **정상 매도만으로 강제청산이 발동**된다.
- 권고: `totalAssetValue = 예수금 + Σ(수량 × 현재가)` 로 재정의. KIS 잔고조회(VTTC8434R)의 `tot_evlu_amt`를 그대로 쓰는 것이 가장 안전. Sprint 3에서 currentPrice 교체와 함께 처리.

### F-2. 계좌 단위 룰이 "매수 신호가 있을 때만" 평가된다

> ✅ **해소 (2026-07-07, Gate 1)**: `RiskMonitor` 신설 — 1초 주기로 신호와 무관하게
> 일일손실 −5%·MDD 10%를 검사해 강제청산 트리거. 룰(DailyLossRule/GlobalEquityStopRule)은
> 매수 거부만 담당하도록 역할 분리, 임계값은 `RiskLimits` 단일 출처로 통합.

- 위치: [TradingScheduler.java:79-87](../src/main/java/com/trading/scheduler/TradingScheduler.java) — `riskEngine.check()`는 신호가 존재할 때만 호출됨.
- 결과: 돌파 신호가 없는 날에는 계좌가 -5%든 -15%든 `DailyLossRule`·`GlobalEquityStopRule`이 한 번도 실행되지 않는다. 강제청산 트리거가 **진입 게이트에 묶여 있어** 하락장(=신호 없는 날)일수록 작동 확률이 낮아지는 역설.
- ADR-001 2.2는 "매 1초 틱마다 실시간 자산 평가"를 명시하나, 현재 `ShadowPortfolio.tick()`은 peakEquity **갱신만** 하고 drawdown **판정은 하지 않는다**.
- 권고: 룰을 두 계층으로 분리.
  - 진입 게이트(신호 시 평가): PendingOrder / PositionLimit / MaxPositionCount / MarketClose / ConsecutiveLoss
  - 계좌 모니터(1초 스케줄 상시 평가): DailyLoss / GlobalEquityStop → `RiskMonitor` 컴포넌트 신설, 신호와 무관하게 `triggerForceLiquidation()` 호출.

### F-3. 강제청산 실행부가 스텁 — "최후의 보루"가 중단만 하고 청산은 못한다

> 🟡 **코드 해소 (2026-07-08, Gate 2) — 리허설 대기**: `KisBrokerageApiClient` 실구현.
> 잔고 = `BalanceClient`(무캐시), 매도 = `KisOrderClient.sell(수량)`, 취소 = `OrderCancelClient`
> (FillProcessor에서 추출, VTTC0803U). 청산 경로는 매도 전용으로 강제(매수 side 거부).
> 리허설 도구: `POST /api/trading/liquidation-drill` (확인 문자열 `CONFIRM_LIQUIDATE` 필수).
> **Gate 2 완료 판정은 모의계좌 청산 리허설 1회 성공 후** — 사용자 실행 필요.
> 한계: 취소 대상은 자체 order_history 기준이라 앱 밖(HTS 수동) 주문은 취소 불가 (런북 명시).
> 검증: `KisBrokerageApiClientTest` 7/7, 전체 스위트 64/64 통과.

- 위치: [KisBrokerageApiClient.java](../src/main/java/com/trading/risk/KisBrokerageApiClient.java) — `sendMarketOrder`/`getActualHoldingQuantity`가 `UnsupportedOperationException`을 던짐.
- 결과: 청산이 트리거되면 전 종목 실패 처리 후 `EMERGENCY_STOPPED` 전환(신규 매매 중단)은 되지만, **보유 포지션은 시장에 그대로 노출된 채 남는다**. README 미구현 목록에 기재는 되어 있으나, "중단은 되고 청산은 안 된다"는 실질 효과를 인지해야 한다.
- 권고: Sprint 3 최우선. 구현 전까지는 강제청산 알림(`sendCritical`)을 받으면 **수동 매도**가 유일한 대응임을 운영 수칙으로 명문화.

### F-4. 출구(매도) 전략이 존재하지 않는다 — 전략 정합성 훼손

> ✅ **해소 (2026-07-08, Gate 3)**: `TimeCutScheduler` 신설 — 평일 15:15 KST(cron, `zone="Asia/Seoul"` 명시로
> F-8 타임존 문제 미상속) 보유 포지션 전량을 Signal → RiskEngine → OrderEngine 평시 매도 경로로 정리.
> FORCE_LIQUIDATING/EMERGENCY_STOPPED 시 양보(청산 상태머신이 포지션 소유), 미체결 SELL 존재 시
> 중복 매도 방지, 종목별 예외 격리. 한계(운영 문서화): 15:15에 앱이 꺼져 있으면 해당일 타임컷은
> 건너뛰고 다음 거래일 15:15에 정리된다. 휴장일에는 KIS 주문 거부가 에러 로그로 남는다(거래일 캘린더는 로드맵 별도 항목).
> 검증: `TimeCutSchedulerTest` 9/9, 전체 스위트 73/73 통과.

- 위치: [VolatilityBreakoutStrategy.java](../src/main/java/com/trading/strategy/VolatilityBreakoutStrategy.java) — SELL 신호를 생성하지 않음. ADR-001 2.3의 "Sleeve B 15:15 타임컷"도 어디에도 구현되어 있지 않다.
- 결과: 래리 윌리엄스 변동성 돌파는 **당일 진입 → 당일(또는 익일 시가) 청산**이 전제인 단타 전략이다. 현재 시스템은 1주 매수 후 무기한 보유하며, `PendingOrderRule`이 재매수를 막으므로 사실상 "첫 돌파에 1주 사고 영원히 끝". 백테스트 통계와 전혀 다른 전략이 된다.
  - 부수 효과: 실현손익이 발생하지 않으므로 Sprint 3에서 `dailyPnlPercent`·`consecutiveLossCount`를 구현해도 입력이 영원히 0이다.
- 권고: 15:15 타임컷 스케줄러(보유 전량 시장가 매도 → `OrderEngine.execute()` 경로)를 Sprint 3 범위에 포함. ADR대로 청산(`LiquidationService`)이 아닌 평시 매도 경로를 사용할 것.

---

## HIGH

### F-5. DailyLossRule·ConsecutiveLossRule 입력값 하드코딩 (문서화된 기지 사항)

> 🟡 **절반 해소 (2026-07-07, Gate 1)**: `dailyPnlPercent` = (현재 총자산 − 당일 시작 자산) ÷ 당일 시작 자산으로
> 실값 연동 (평가손익 포함, `daily_equity` 테이블에 기준 영속화 — 장중 재시작 안전).
> `consecutiveLossCount`는 매도 체결 데이터가 필요하므로 Gate 3(F-4 출구 전략)과 함께 구현.

- ~~`dailyPnlPercent = 0.0`~~, `consecutiveLossCount = 0` 고정. 기동 시 경고 로그를 남기는 점은 양호. F-4(출구 전략)가 선행되어야 실현손익이 생긴다는 의존 관계에 유의.

### F-6. Trim 3회 연속 실패 에스컬레이션은 도달 불가능한 데드코드

- 위치: [LiquidationService.java:87-132](../src/main/java/com/trading/risk/LiquidationService.java)
- `triggerPartialTrim()`은 항상 `executePartialTrim(targets, 0)`으로 **1회만** 호출되고, 실패 시 재시도 루프/재제출이 없다. 따라서 `totalFailures`는 항상 1이며 `>= 3` 승격 분기는 절대 실행되지 않는다. **ADR-001 문서의 예시 코드 자체에 같은 결함이 있다** — "Trim 실패 시 에스컬레이션 부재"를 해결했다고 명시한 ADR의 주장과 실제 코드가 모순.
- 권고: 실패 시 지연 재시도(예: 30초 후 `executePartialTrim(failedTargets, totalFailures)` 재제출) 구현 또는 ADR에서 에스컬레이션 조항을 "1회 실패 즉시 승격"으로 개정. Trim 자체가 CapacityScalingEngine(ADR-002, 미구현) 전용이므로 당장 위험은 없음.

### F-7. PositionLimitRule의 비중 분모가 잘못됨 — 10% 캡이 기능하지 않는다

> ✅ **해소 (2026-07-07, Gate 1)**: F-1 교정의 부수 효과 — `Account.getPositionWeight()`의
> 분모(totalAssetValue)가 현금 포함 총자산이 되고 분자도 실시간 현재가 기반이 되어
> 10% 비중 캡이 의도대로 동작한다. (주문 수량 1주 고정 문제는 별도 — Phase 2 R 사이징)

- 위치: [Account.java:53-60](../src/main/java/com/trading/position/Account.java) — `weight = 포지션 평가금액 / totalAssetValue(현금 제외)`.
- 결과: 미보유 종목은 weight 0으로 항상 통과, 보유 종목은 N종목 균등 시 항상 1/N (1종목이면 100%). 즉 "총자산의 10%까지만 배분"이라는 의도가 아니라 "이미 들고 있으면 차단"으로 동작하며, 이는 `PendingOrderRule`과 완전 중복이다. ADR의 Sleeve B 30% 자금 한도도 같은 분모 문제를 공유하게 된다.
- 권고: F-1과 동일하게 분모를 현금 포함 총자산으로 교체. 아울러 현재 주문이 1주 고정(`ORD_QTY=1`)이라 비중 룰이 사실상 무의미한 점도 v2 주문 수량 로직 설계 시 함께 해결.

---

## MEDIUM

### F-8. MarketCloseRule이 시스템 기본 타임존에 의존

- `LocalTime.now()` 사용 — 현재 Windows(KST)에서는 정상이나, 클라우드(UTC) 이전 시 15:20 컷이 자정 근처로 밀린다. `Clock` 주입 + `ZoneId.of("Asia/Seoul")` 고정 권장. (경계값: 정확히 15:20:00은 `isAfter`라 통과 — 의도 확인 필요, 사소함)

### F-9. 시세 파싱 실패가 0.0으로 조용히 강등된다

- [KisMarketDataService.java:129-137](../src/main/java/com/trading/market/KisMarketDataService.java) — `parseDouble` 실패 시 0.0 반환. 전일 고가/저가 파싱이 깨지면 `range=0 → breakoutPrice=시가`가 되어 **시가 위 아무 가격에서나 매수 신호**가 난다. 가격 필드 파싱 실패는 예외를 던져 해당 틱을 스킵하는 것이 안전.

### F-10. DailyLossRule의 08:30 리셋 크론은 실질 no-op

- 이 클래스에는 리셋할 상태가 없다(P&L은 Account에서 옴). 실제 일일 리셋은 Sprint 3에서 `dailyPnlPercent` 계산 주체(잔고 스냅샷 기준시점)에 구현해야 하며, 현재 크론은 로그용임을 주석에 명시할 것.

### F-11. ConsecutiveLossRule 차단 해제 후 재차단 조건 미정의

- 1시간 경과 후 카운터가 여전히 ≥3이면 즉시 재차단된다. 카운터를 언제 리셋할지(차단 시? 다음 익절 시? 일일 리셋?)가 어디에도 정의되어 있지 않다 — Sprint 3 구현 전에 규칙으로 확정 필요.

---

## 문서 불일치 (수정 대상)

| # | 항목 | 내용 |
|---|---|---|
| F-12 | README "6대 리스크 룰" | 실제 7개 (`PendingOrderRule` 누락). 기동 로그도 "Loaded 7 risk rules"로 찍힌다 → 본 검증과 함께 README 갱신됨 |
| F-13 | Claude.md 전면 낡음 | "5대 룰", "MarketDataService/KisOrderClient/PositionManager 구현체 없음" 등 — 전부 구현 완료된 상태와 불일치. AI 컨텍스트 파일이 낡으면 잘못된 제안을 유도하므로 갱신 필요 |
| F-14 | ADR-001 미구현 조항 | 09:30 스냅샷 스크리닝, Price Jitter 분할주문, 09:33 시장가 폴백, 15:15 타임컷, OpportunityCostLogger 연동 — 전부 미구현. README 로드맵에 반영됨 |

---

## 정상 확인된 항목 (합격)

- **파이프라인 강제**: Strategy는 Signal만 반환, 주문은 OrderEngine 단일 경로. 우회 코드 없음.
- **청산 상태머신**: 단일 `AtomicReference<LiquidationPhase>` 공유, FULL 승격 시 Trim 자체 양보, 중복 청산 차단, finally에서 종단 상태 수렴 — ADR 2.3과 일치 (F-6 에스컬레이션 제외).
- **중복 주문 방어**: `PendingOrderRule`(보유/미체결 검사) + `TradingScheduler` running 가드 + `OrderEngine` TradingMode 이중 게이트 — 1초 루프에서 중복 매수 불가.
- **peakEquity 단조 증가**: 상승 시에만 갱신, 예외 시 유지, 08:30 리셋과 분리 — ADR 2.2와 일치.
- **전일 캔들 조회**: 장중 미완성 당일봉 스킵 로직, 휴장일 대응 합리적.
- **보안**: 앱키/시크릿 환경변수 전용, 뉴스 XML 파서 XXE 방어(doctype 차단), 계좌번호 하드코딩 없음.
- **테스트 스위트**: 이 세션(Claude Code)에서는 Gradle 실행이 환경 제약으로 불가하여 **미실행**.
  마지막 실행 기록(2026-07-04, `auto_trading-build/test-results`)은 `LiquidationServiceTest` 4/4 통과.
  나머지 4개 테스트 클래스는 IDE(IntelliJ/VS Code)에서 `gradlew.bat test`로 재검증 필요.

---

## 권고 우선순위 (실전 전환 게이트)

1. ✅ **[Gate 1 — 안전장치 실동작] 완료 (2026-07-07)**: F-1 + F-2 + F-5(일부) + F-7 해소.
   신규: `RiskLimits`, `RiskMonitor`, `KisBalanceClient`(+`BalanceClient` 인터페이스),
   `DailyEquity`, `PortfolioState`(peakEquity 영속화 — ADR "영구 보존" 재시작 보장).
   테스트 23/23 통과 (RiskMonitorTest 9, KisPositionManagerTest 6, DailyLossRuleTest 4, LiquidationServiceTest 회귀 4).
2. 🟡 **[Gate 2 — 청산 실행] 코드 완료 (2026-07-08)**: F-3 실장 + 리허설 엔드포인트.
   남은 것: **모의계좌 강제청산 리허설 1회** (장중 실행 권장 — 장외에는 주문 거부가 "부분 실패" 경로로 보고되는지 확인하는 것도 유효한 리허설).
3. ✅ **[Gate 3 — 전략 완결] 타임컷 완료 (2026-07-08)**: F-4 `TimeCutScheduler` 구현.
   남은 것: 실현손익 기반 `consecutiveLossCount` 연동 (F-5 나머지 — 매도 체결 데이터가 이제 생기므로 구현 가능).
4. F-7, F-9, F-8 순으로 정리. F-6은 ADR-002 착수 시 함께.
