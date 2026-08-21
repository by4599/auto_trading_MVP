---
name: risk-rule-audit
description: "자동매매 안전장치 감사 절차. 주문 흐름 우회, 리스크 룰 8종 동작, 청산 상태머신(ADR-001), 프로필 격리(backtest가 텔레그램·실계좌로 새는지), 비밀키 하드코딩을 점검할 때 반드시 이 스킬을 사용. 코드 변경 후 안전성 확인, '리스크 룰 잘 도는지 봐줘', '실전 전환해도 되나', 커밋·배포 전 점검, 재감사·부분 감사 요청 시에도 사용."
---

# risk-rule-audit — 안전장치 감사 절차

이 시스템은 실제 돈이 걸린 시스템이다. 감사의 목적은 "기능이 되는가"가 아니라
**"안전장치를 우회할 구멍이 생겼는가"**를 찾는 것이다. 기능은 실패하면 눈에
보이지만, 안전장치는 실패해도 조용하다 — 사고가 날 때까지.

**이 스킬을 쓸 때 코드를 고치지 않는다.** 위반을 찾아 근거와 함께 보고한다.

## 0. 감사 범위 정하기

```bash
git diff --name-only          # 이번 변경분
git diff --name-only master   # 브랜치 전체
```

저장소 **루트**의 낡은 `.java` 파일(`Account.java`, `RiskEngine.java` 등)은
감사 대상이 아니다 — 리팩터링 이전 잔재이고 빌드에 포함되지 않는다.
진짜 소스는 `src/main/java/com/trading/**` 뿐이다.

## 1. 흐름 감사 — 가장 중요

주문은 **반드시** 이 순서로만 나가야 한다:

```
Strategy → Signal → RiskEngine → OrderEngine
```

| 확인 | 방법 | 위반 시 |
|---|---|---|
| Strategy가 주문을 직접 내는가 | `strategy` 패키지에서 `OrderEngine`·`KisOrderClient` 참조 검색 | **CRITICAL** |
| `evaluate()`가 Signal 외의 것을 하는가 | 각 Strategy의 `evaluate()` 본문 확인 | CRITICAL |
| RiskEngine을 건너뛰는 주문 경로가 있는가 | `OrderEngine.execute()` 호출부 전수 확인 | CRITICAL |

```bash
grep -rn "OrderEngine\|KisOrderClient" src/main/java/com/trading/strategy/
```

예외: 평시 익절/손절과 타임컷은 청산 경로가 아니라 `OrderEngine` 경로를 쓴다
(설계상 의도된 것이다).

## 2. 리스크 룰 8종 감사

| 룰 | 확인할 것 |
|---|---|
| `PendingOrderRule` | 보유/미체결 시 중복 매수 차단 |
| `PositionLimitRule` | 종목당 비중 10% 상한, 분모가 현금 포함 equity인가 |
| `MaxPositionCountRule` | 최대 5종목 |
| `MarketCloseRule` | 15:20 이후 신규 매수 금지, **KST 고정 Clock 주입**인가 |
| `DailyLossRule` | -3% 매수 차단 / -5% 강제청산, dailyPnl이 실값인가 |
| `GlobalEquityStopRule` | 전고점 대비 MDD 10% 초과 시 청산, equity에 현금 포함인가 |
| `ConsecutiveLossRule` | 연속 손실 3회 시 1시간 중지 |
| `BucketBudgetRule` | 지갑 칸 예산 소진 시 차단 (paper 전용) |

**핵심 확인 2가지:**

1. 새 룰이 `RiskRule` 구현 + `@Component`로 등록됐는가 —
   `RiskEngine` 클래스를 직접 수정했으면 **위반**(자동 주입 설계를 깬 것)
2. `LocalTime.now()` 같은 실시간 직접 호출이 남아 있는가 — `Clock` 주입이어야
   테스트와 백테스트가 결정적이다

```bash
grep -rn "LocalTime.now()\|LocalDate.now()\|LocalDateTime.now()" src/main/java/com/trading/ | grep -v "clock"
```

## 3. 청산 상태머신 감사 (ADR-001)

전량 청산과 부분 축소(Trim)는 `LiquidationService`의 **단일 `LiquidationPhase`
상태머신**을 공유해야 한다.

- 새 boolean 플래그로 청산 상태를 따로 관리하는 코드가 생겼으면 **ADR-001 위반**
- FULL(전량)이 발동하면 Trim이 양보하는가
- 중복 청산이 차단되는가

```bash
grep -rn "LiquidationPhase\|isLiquidating\|liquidat" src/main/java/com/trading/risk/ | head -30
```

## 4. 프로필 격리 감사

백테스트가 현실로 새면 가짜 주문이 실제 알림·계좌에 닿는다.

| 확인 | 기대 상태 |
|---|---|
| 텔레그램 격리 | `TradingEventListener`에 `@Profile("!backtest")` |
| 스케줄링 | `@EnableScheduling`이 `SchedulingConfig`(`@Profile("!backtest")`)에 있음 |
| 지갑 칸 | `trading.bucket.enabled`가 backtest에서 OFF |
| DB | 백테스트는 전용 `backtest-db` |
| 계좌 | 현재 스코프는 **모의투자(paper)** 전용. `real` 프로필 활성화 흔적이 있으면 즉시 보고 |

> 과거 사고: OS 환경변수가 백테스트 텔레그램 격리를 무력화한 적이 있다.
> 설정값(yml)만 보지 말고 **환경변수로 덮일 수 있는 경로**까지 확인한다.

## 5. 비밀키 감사

```bash
grep -rniE "appkey|secretkey|app_secret|token" src/main/java src/main/resources --include=*.java --include=*.yml | grep -viE "getenv|\$\{|environment|property"
```

- 키가 코드·커밋된 yml에 있으면 **CRITICAL**. 발견 즉시 보고하고, 키 교체(rotate)를
  사용자에게 안내한다
- `.gitignore`에 `application-paper-local.yml`, `application-real.yml`, `*.env`가
  들어 있는지 확인

## 6. 보고 형식 (필수)

```
[리스크 감사] 범위: exit-lab 관련 변경 12파일 (2026-07-22)
| 심각도 | 항목 | 위치 | 내용 |
|---|---|---|---|
| HIGH | Clock 미주입 | TrailingStopTracker.java:88 | LocalDate.now() 직접 호출 — 백테스트 비결정 |
| MEDIUM | 파일 크기 | BacktestOrchestrator.java | 800줄 근접 |
→ CRITICAL 0 · HIGH 1 · MEDIUM 1
→ HIGH 해소 전까지 "완료" 아님
```

심각도 기준:

| 등급 | 뜻 |
|---|---|
| CRITICAL | 돈이 새는 경로 — 흐름 우회, 실계좌 노출, 비밀키 |
| HIGH | 안전장치 무력화 — 룰 미주입, Clock 미주입, 격리 파손 |
| MEDIUM | 규칙 위반이나 즉시 손실은 없음 |
| LOW | 스타일·가독성 |

## 7. 판단이 모호할 때

의도된 설계인지 위반인지 확신이 안 서면 **양쪽 근거를 병기하고 판단을 보류**한다.
임의로 "무해하다"고 결론짓지 않는다. 이 프로젝트에서 조용한 오판은
시끄러운 오탐보다 훨씬 비싸다.

## 8. 실전(real) 전환 관련 질문을 받으면

코드가 완벽해도 감사관이 실전 승격을 결정하지 않는다. 반드시 이렇게 답한다:

> 실전 전환은 `docs/TRADING-RULES-AUDIT.md`의 CRITICAL 항목 해소 +
> 모의계좌 청산 리허설 성공 + **사람의 승인(게이트 G2)**이 모두 선행해야 한다.
