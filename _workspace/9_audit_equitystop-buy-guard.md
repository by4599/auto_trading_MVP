# 9_audit — GlobalEquityStopRule `isBuy()` 가드 감사

감사자: `risk-auditor` (2026-09-12) · 코드 미수정, 위반 탐지만 수행
※ 감사관 도구에 Write가 없어 리더가 보고 내용을 그대로 옮겨 적었다.

## 판정: CRITICAL 0 · HIGH 0 · MEDIUM 1 · LOW 2 → 배포 가능

이전 감사(`2_audit`, `5_audit`)의 CRITICAL·HIGH 이월 없음.
본 변경은 `5_audit`이 제기했던 **HIGH를 해소**한다.

## 6개 지시 항목 판정

| # | 항목 | 판정 | 근거 |
|---|---|---|---|
| 1 | 매수 게이트 미약화 | ✅ | `GlobalEquityStopRule.java:31` 가드는 `!isBuy()`에만 반응. `Signal.java:7,35` 타입이 BUY/SELL 둘뿐이라 제3상태 없음. 기존 매수 테스트 3건이 **가드 유/무 양쪽에서 모두 통과** |
| 1b | 미검증 전고점 분기 매수 차단 유지 | ✅ | `GlobalEquityStopRuleTest.java:67-73` 통과. 가드가 `isPeakUnverified()` 분기보다 위지만 매수는 통과시키므로 분기 도달 |
| 2 | 가드 위치·순서 부작용 | ✅ | `validate`는 부작용 없는 순수 판정 — `getPeakEquity()`/`isPeakUnverified()`는 단순 getter(`ShadowPortfolio.java:87-88,136`). 매도에서 `peak<=0`·`current<=0` 가드를 건너뛰어도 양쪽 다 결과가 `pass()`로 동일 |
| 3 | 청산 경로 무관 | ✅ | `LiquidationService.java:21,61,165,171` — `BrokerageApiClient` 직접 호출, RiskEngine·OrderEngine 미경유. `RiskMonitor.java:109,131`도 `triggerForceLiquidation()` 직행 |
| 4 | 14개 룰 전수 | ✅ | 14/14 전원 가드 보유. `BucketBudgetRule.java:33`만 `!properties.isEnabled() \|\| !signal.isBuy()` 결합형 |
| 5 | 백테스트 측정-코드 동일성 | ✅ | 측정 시 변경은 `if (!signal.isBuy()) return RiskResult.pass();` 1줄(`7_quant:22`, `8_quant:45`)로 현행 diff의 실행 라인과 **문자 단위 동일**. 나머지 2줄은 주석·공백 |
| 6 | 비밀키 노출 | ✅ | `git diff \| grep -iE "appkey\|secret\|token\|password\|api[-_]?key\|cano\|acnt"` → 0건 |

## 감사자 독립 검증 (구현자 보고 미신뢰)

```
전체 스위트: 93파일 tests=618 failures=0 errors=0 skipped=0 / EXIT=0
Red-Green: git stash로 가드 제거 → 5 tests completed, 2 failed
  - GlobalEquityStopRuleTest.java:97  (MDD 초과 매도)
  - GlobalEquityStopRuleTest.java:105 (미검증 전고점 + MDD 초과 매도)
  → 매수 3건은 RED에서도 통과 = 가드가 매수 게이트를 안 건드린다는 직접 증거
복구: diff --strip-trailing-cr → CONTENT IDENTICAL
```

**결함의 실재성** — `RiskEngine`을 경유하는 매도 호출부는 4곳이며 **전부 막히고 있었다**:
`StopLossMonitor.java:138-139` · `TimeCutScheduler.java:167-168` ·
`MaxHoldScheduler.java:150-151` · `DailyBarExitSimulator.java:168-169`(백테스트).

## 지적 사항

| 심각도 | 항목 | 위치 | 내용 |
|---|---|---|---|
| MEDIUM | 청산 개시 창의 동시 매도 (판단 보류) | `LiquidationService.java:43-58` | `phase`는 `:43`에서 즉시 설정되나 `changeMode(FORCE_LIQUIDATING)`는 비동기 태스크 안 `:58`에서 실행된다. 그 사이 mode는 RUNNING이라 `StopLossMonitor.java:80`·`TimeCutScheduler.java:135`·`MaxHoldScheduler.java:99`의 mode 가드를 통과해 매도가 나갈 수 있다. **이번 변경 이전에는 MDD>10% 구간에서 이 룰이 그 매도를 우발적으로 막고 있었다** — 기존 결함이며 이번 변경이 만든 것이 아니다 |
| LOW | 중복 매도 방지의 엔진 차원 안전망 부재 | `PendingOrderRule.java:35` | 매수만 본다. 매도 중복 방지는 세 호출부에 **동일 로직 3벌 복제**(`StopLossMonitor:156-162`·`TimeCutScheduler:161`·`MaxHoldScheduler:146,160-165`). 새 매도 호출부가 가드를 빠뜨려도 엔진이 못 잡는다 |
| LOW | 감사 중 커밋 범위 증가 | 문서 3건 | 감사 시작 시 워킹트리는 java 2건뿐이었으나 진행 중 문서 3건이 병렬로 추가됐다 — 리더가 문서를 코드와 맞춘 것. 커밋 범위 확인 완료 |

### MEDIUM 양쪽 근거 (임의로 무해 결론 내리지 않음)

- **무해 쪽**: 매도는 노출을 줄이는 방향이고, `LiquidationService.java:61-62`가
  `cancelAllPendingOrders()` → `getActualAccountAsset()` 순서라 취소 후 실잔고를 다시 읽는다.
  `:171` `getActualHoldingQuantity` 재조회도 있다. 같은 창이 `DailyLossRule` -5% 청산 경로에는
  **원래부터** 존재했고(그 구간은 MDD<10%일 수 있어 이 룰이 막은 적 없음), 사고 없이
  Gate 2 리허설을 통과했다.
- **위험 쪽**: 우발적 완화가 사라져 MDD 경로에서도 이제 그 창이 노출된다. 실측된 적은 없다.
- **해소 방향**: `triggerForceLiquidation`에서 `changeMode`를 submit 이전으로 올리거나,
  세 매도 호출부에 `isAnyLiquidationInProgress()` 확인 추가. **이번 커밋 범위 밖 · 별건 처리 권고.**

## 매도를 막아야 마땅한 룰이 있는가 → 없다

14개 룰 전부 "진입 게이트"로 설계됐고, 매도 차단은 이 아키텍처에서 **범주적으로 잘못**이다.
모든 출구(타임컷·손절·익절·최대보유)가 `Signal → RiskEngine → OrderEngine`을 지나기 때문이다.
코드가 스스로 그렇게 말한다 — `OrderFailureCooldownRule.java:15`:
"매도는 막지 않는다 — 손절·타임컷 등 방어 경로를 지연시키면 손실이 커진다."

## 아키텍처 6규칙 · 프로필 격리

규칙1·2 ✅(전략에 `OrderEngine` 호출 0건) · 규칙3 ✅(`RiskEngine` 무수정) · 규칙4 ✅ ·
규칙5 ✅(`LiquidationPhase` 참조가 `LiquidationService` 밖에 0건) · 규칙6 ✅

이 룰은 `@Profile({"paper","backtest"})`라 백테스트에 실제 영향이 있다. 두 창 A/B로 앵커 불변 확인:
기준선 창 5지표 일치(781/1.959/0.0130/0.0987/0.5186), 약세장 24창 §14.4 MA120 소수점까지 동일.
`docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml` 미변경, `trading.bucket.enabled` OFF 유지.

⚠ `7_quant §4(3)`의 단서를 감사 소견으로 승계한다 — **기준선이 무사했던 것은 설계상 보장이 아니라
시점의 운에 가깝다.** 유니버스·창·사이징이 바뀌면 RR1급 프로필에서도 매도가 막힐 수 있었다.
가드는 그 잠재 변동성을 제거한 것이므로 **앵커 안정성 측면에서도 개선**이다.

## 실전(real) 전환 관련

이 변경은 실전 승격 근거가 **아니다**. 3방식 전부 §4 미달이며 돈치안·MA 후보도 MDD 한도 여유가 얇다.
**실전 전환은 ADR-001(다일 보유) 재논의 + 사람 게이트 G2 선행**이 변함없이 필요하다.

## 감사 중 감사자가 변경한 것

Red-Green 검증을 위해 `git stash push/pop` 1회. 그 결과 `GlobalEquityStopRule.java`의 줄바꿈이
LF→CRLF로 정규화됐다(`core.autocrlf=true`, 저장소 기본 동작). **내용은 동일**
(`diff --strip-trailing-cr` 무출력). 코드는 고치지 않았다.
리더가 커밋 직전 `git diff -w`로 소스 삭제 줄 0건을 재확인하고 전체 테스트를 다시 실행했다
(618/618, 종료 코드 0).
