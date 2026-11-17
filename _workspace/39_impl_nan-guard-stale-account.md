# 39_impl — NaN 파라미터 차단(⑤) + 낡은 잔고 매수 금지(②)

> 2026-10-10(토) 10:1x KST · 리더 구현 · 사용자 승인 계획(①~⑤ 추천안대로) 중 작은 두 건 · 감사 `40_audit_nan-guard-stale-account.md`

## ⑤ 숫자 파라미터 NaN 차단 (37_audit M-2)

- 문제: `ParamCatalog.validate()` NUMBER 분기는 `Double.parseDouble` 뒤 범위만 비교했다. NaN은 `v < min`·`v > max`가 둘 다
  거짓이라 통과 → `risk.mddLimit`에 저장되면 `drawdown > NaN`이 늘 거짓이 돼 매수 차단·강제청산이 영구히 꺼진다.
- 수정: `src/main/java/com/trading/settings/ParamCatalog.java` NUMBER 분기에 `Double.isFinite` 검사 한 줄(+이유 주석).
  무한대는 원래도 범위에서 걸렸지만 이제 같은 메시지로 거부된다.
- 테스트: `src/test/java/com/trading/settings/ParamCatalogTest.java`(신규) — "NaN"·"+NaN"·"-NaN"·"Infinity"·"-Infinity" 거부,
  "0.08"·"0.1"·"0.03"·"0.30" 허용.

## ② 낡은 잔고로 신규 매수 금지 (28_audit M-3)

- 문제: 잔고 조회가 실패하면 `KisPositionManager`가 캐시·DB 폴백을 `Account.asStale()`로 넘긴다(`:66,80,183`). 청산·손절
  감시는 이 표시를 보고 판정을 건너뛰지만, 매수 쪽 룰(`DailyLossRule`·`GlobalEquityStopRule`)은 낡은 값을 그대로 읽었다.
- 수정:
  - 신규 `src/main/java/com/trading/risk/StaleAccountBuyGuardRule.java` — `@Component @Profile("!backtest")`, 매수 + 스위치 ON +
    `!account.isFresh()`이면 거부("잔고 정보가 낡음(조회 실패) — 신규 매수 보류"). 매도는 통과. `RiskEngine` 무수정.
  - 스위치 `trading.risk.stale-account-buy-guard`(기본 false) — `application-paper.yml`에 `true`(사용자 결정: 모의에서 바로 켬).
  - `RiskRuleNameResolver`에 사유 조각 추가, `static/js/diag-history.js`에 진단 화면 라벨 추가.
- 테스트: `StaleAccountBuyGuardRuleTest`(신규 4건), `PaperSafetyGuardsConfigTest`(신규 — 실제 paper yml 고정),
  `RiskRuleNameResolverTest` 1행 추가.

## 검증 증거

```
[검증 증거 — Red]  (룰은 항상 통과하는 껍데기로 두고 실행)
명령: gradle test --tests ParamCatalogTest --tests StaleAccountBuyGuardRuleTest --tests PaperSafetyGuardsConfigTest
      --tests RiskRuleNameResolverTest --console=plain (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-impl39)
종료 코드: 1
결과: 34개 중 28개 통과, 실패 6 — 정확히 예상한 6건:
  NaN·+NaN·-NaN 허용됨(3) · 낡은 스냅샷 매수 통과(1) · 사유 조각 UNKNOWN(1) · paper 스위치 없음(1)

[검증 증거 — Green]
명령: gradle test --console=plain (같은 빌드 폴더, 전체)
종료 코드: 0
결과: 905개 중 905개 통과, 실패 0 · 오류 0 · 건너뜀 0 (기존 890 + 신규 15), 결과 XML 149개
```

## 참고

- 운영 반영은 앱 재기동이 필요하다(코드·설정·정적 리소스 변경). 나머지 작업(③④①)과 함께 한 번에 재기동한다.
- 진단 화면 라벨표(`diag-history.js`)에는 `IndexTrendDataGateRule` 라벨이 원래 없다 — 이번 범위 밖이라 두었다(원문 사유가 대신 표시된다).
