# trading-mvp 프로젝트 컨텍스트

## 이 프로젝트는

Spring Boot 기반 국내주식 자동매매 시스템. 한국투자증권(KIS) Open API로
**모의투자 계좌**에서 삼성전자(005930) 1종목을 변동성 돌파 전략으로 자동매매하며
파이프라인을 검증하는 것이 Phase 1 목표. 최종 목표는 뉴스·공시 이벤트 기반
자동 트레이딩 (로드맵은 `README.md`, 투자 판단 근거는 `docs/INVESTMENT-METHODOLOGY.md`).

## 절대 어기면 안 되는 아키텍처 규칙

1. **Strategy는 절대 주문을 직접 실행하지 않는다.** `evaluate()`는 `Signal`만
   반환한다. 주문은 무조건 `OrderEngine`을 통해서만 나간다.
2. **흐름은 항상 고정**: `Strategy -> Signal -> RiskEngine -> OrderEngine`.
   이 순서를 건너뛰는 코드는 절대 작성하지 않는다.
3. **새 리스크 룰을 추가할 때** `RiskEngine` 클래스를 수정하지 않는다.
   `RiskRule` 인터페이스를 구현하고 `@Component`만 붙이면 스프링이
   자동으로 `RiskEngine`에 주입한다.
4. **인터페이스 우선**: `MarketDataService`, `KisOrderClient`, `PositionManager`는
   모의투자/실전투자/백테스트용으로 구현체를 갈아끼울 수 있어야 한다.
   인터페이스 시그니처를 함부로 바꾸지 않는다.
5. **청산은 단일 상태머신**: 전량 청산과 부분 축소(Trim)는 `LiquidationService`의
   단일 `LiquidationPhase` 상태머신을 공유한다 (개별 플래그 분리는 ADR-001 위반).
   평시 익절/손절은 청산 경로가 아닌 `OrderEngine` 경로를 쓴다.
6. **Sleeve A(장기 추세추종)는 구현 보류** — 자금·로직을 얹는 제안은 ADR-001 위반.

## 현재 스코프 (이거 넘어서는 기능 제안하지 말 것)

- 종목: 삼성전자(005930) 1개만
- 전략: `VolatilityBreakoutStrategy` (변동성 돌파, K=0.5) — 매수 신호.
  출구는 `TimeCutScheduler`(평일 15:15 KST 보유분 전량 매도, Gate 3)
- 계좌: 한투 **모의투자** 계좌 (`@Profile("paper")`, 실전 전환은
  `docs/TRADING-RULES-AUDIT.md`의 CRITICAL 4건 해소 후)
- 주문: 시장가 1주 고정 (수량 로직은 v2)
- 알림: 텔레그램 (체결/에러/청산)
- 뉴스(`research` 패키지): 수집·분류만, 매매 미연동 (연동은 Phase 3)

## 7대 리스크 룰 (`com.trading.risk`, RiskEngine에 7개 주입됨)

| 룰 | 조건 | 실제 동작 상태 |
|---|---|---|
| `PendingOrderRule` | 보유 중/미체결 매수 존재 시 중복 매수 차단 | ✅ 활성 |
| `PositionLimitRule` | 종목당 비중 최대 10% | ✅ 활성 (Gate 1에서 분모 교정) |
| `MaxPositionCountRule` | 최대 보유 종목 5개 | ✅ 활성 |
| `MarketCloseRule` | 15:20 이후 신규 매수 금지 | ✅ 활성 (KST 타임존 가정, F-8) |
| `DailyLossRule` | -3% 매수 차단 / -5% 강제청산 | ✅ 활성 (Gate 1 — dailyPnl 실값 + `RiskMonitor` 상시 감시) |
| `GlobalEquityStopRule` | 전고점 대비 MDD 10% 초과 시 강제청산 | ✅ 활성 (Gate 1 — 현금 포함 equity) |
| `ConsecutiveLossRule` | 연속 손실 3회 시 1시간 중지 | ✅ 활성 (Gate 3 — `TradeResultTracker` 실현손익 스트릭 연동) |

> 강제청산 실행부(`KisBrokerageApiClient`)는 Gate 2에서 실구현 완료 —
> 단, **모의계좌 청산 리허설 1회 성공 전까지 Gate 2 완료 판정 아님**
> (`POST /api/trading/liquidation-drill`). F-번호와 상세 근거는 `docs/TRADING-RULES-AUDIT.md` 참고.

## 패키지 구조

`market`(시세) / `strategy`(신호생성) / `signal`(신호 모음) /
`risk`(검증·청산) / `order`(주문실행·체결) / `position`(계좌상태) /
`scheduler`(오케스트레이션) / `research`(뉴스 수집·감성분류) /
`dashboard`·`settings`·`control`(웹 운영 도구, localhost:8080)

자세한 설명은 `README.md` 참고.

## 구현 완료된 부분

- `KisApiClient` — KIS OAuth 인증/공통 HTTP 클라이언트 (모든 KIS 연동이 공유)
- `KisMarketDataService` (`market`) — 전일 일봉 + 당일 라이브 캔들 조회
- `KisOrderClientImpl` (`order`) — 시장가 주문 접수. 체결은 `FillPoller`(3초 주기)
  → `FillProcessor` → `FillStateUpdater` → `OrderFilledEvent`(텔레그램 알림)
- `KisPositionManager` (`position`) — JPA 기반 포지션/계좌 조회 +
  `ShadowPortfolio`(peakEquity 추적)
- `LiquidationService` (`risk`) — ADR-001 청산 상태머신 (전량/Trim 공유,
  FULL 승격 시 Trim 양보, 중복 청산 차단)
- `research` 패키지 — `NewsAggregatorService`(RSS 4개, 30분 주기) +
  `NewsSentimentAnalyzer`(키워드 기반 호재/악재 1차 분류) + 워치리스트 매칭
- `RiskMonitor`/`KisBalanceClient` (Gate 1) — 신호 독립 1초 상시 감시 + 잔고 실값 연동
- `KisBrokerageApiClient` (Gate 2) — 강제청산 실행부 실구현 (잔고/전량매도/미체결취소)
- `TimeCutScheduler` (Gate 3) — 평일 15:15 KST 보유분 전량 매도 (평시 OrderEngine 경로)
- `TradeResultTracker` (Gate 3) — 매도 체결 실현손익 → 연속손실 카운터 (`portfolio_state` 영속화)

## 미구현 / 알려진 결함 (제안·수정 시 주의)

1. 모의계좌 강제청산 리허설 미실행 — Gate 2 완료 판정 보류 (사용자 실행 필요)
2. `currentPrice = averagePrice` 근사 잔존 — 현재가 API 교체 예정
3. 타임컷은 15:15에 앱이 꺼져 있으면 해당일 건너뜀 + 휴장일 미인지 (거래일 캘린더는 로드맵 항목)
4. 연속손실 기록은 매도 체결 청크 단위 — v2 부분 체결 매도 도입 시 라운드트립 집계로 전환 필요

해소됨: F-1/F-2/F-7 (Gate 1, 2026-07-07) · F-3 (Gate 2, 2026-07-08) · F-4/F-5 (Gate 3, 2026-07-08)

Sprint 3 작업 순서는 `README.md`의 "다음 작업" 섹션 기준.

## 코딩 컨벤션

- 모든 외부 연동(한투 API)은 인터페이스 뒤에 숨기고, application.yml의
  profile(`real`/`paper`)로 구현체를 교체할 수 있게 한다.
- appkey/secretkey는 절대 코드에 하드코딩하지 않는다. application.yml +
  환경변수로 분리하고, `.gitignore`에 실제 키가 든 yml은 반드시 추가한다.
- 새 클래스를 추가하면 같은 패키지의 기존 클래스 스타일(생성자 주입,
  `@Component`)을 그대로 따른다.

## 빌드 주의사항

프로젝트 경로에 한글이 포함되어 있어 `build.gradle`에서 빌드 출력 경로를
`C:/Users/SAMSUNG/auto_trading-build`(ASCII 경로)로 우회한다.
빌드/테스트: `.\gradlew.bat build` / `.\gradlew.bat test`
