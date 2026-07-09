# trading-mvp — 뉴스 기반 국내주식 자동매매 시스템

공시·수급·정책·뉴스 등 시장에 유의미한 정보를 수집하고, 검증된 방법론으로
투자 방향(종목·목표가·손절가)을 정한 뒤, **규칙에 의해** 자동으로 트레이딩하는 것이 최종 목표입니다.

> **투자 판단의 근거**(전문 투자자·증권사가 종목을 발굴하고 목표가·손절가를 산정하는 방식과
> 그중 자동화할 것의 선별)는 [docs/INVESTMENT-METHODOLOGY.md](docs/INVESTMENT-METHODOLOGY.md)에 정의되어 있다.
> 핵심 설계 원칙: ① 출구(손절·사이징)가 입구(종목 발굴)보다 먼저 ② 백테스트 없는 규칙은
> 채택하지 않는다 ③ 정성 판단(EPS 추정 등)은 자동화하지 않고 컨센서스 데이터로 대체한다.
>
> **지속적으로 돈을 못 벌 때의 대응**(성과 판정 기준, 자금 축소 → 모의 강등 → 폐기의
> 5단계 상태 머신, 프로젝트 전체 손실 예산 15%, 이익 인출·복리 상한)은
> [docs/PERFORMANCE-GOVERNANCE.md](docs/PERFORMANCE-GOVERNANCE.md)에 사전 확정되어 있다.
> 손실 구간에서는 이 규칙을 재량으로 완화하지 않는다.
>
> **시스템이 죽었을 때의 대응**(데드맨 스위치, SAFE_MODE 재기동 시퀀스, 재가동 게이트,
> 휴장일·VI·하한가 등 시장 예외, 런북)은 [docs/OPERATIONS.md](docs/OPERATIONS.md),
> **규칙 검증의 도구**(백테스트 엔진·Walk-Forward·합격 기준)는
> [docs/BACKTEST-DESIGN.md](docs/BACKTEST-DESIGN.md)에 정의되어 있다.

현재는 그 1단계로, Spring Boot + 한국투자증권(KIS) Open API로 삼성전자(005930) 1종목을
**모의투자 계좌**에서 변동성 돌파 전략으로 자동매매하며 파이프라인을 검증하고 있습니다.

---

## 운영·개발 로드맵

| Phase | 목표 | 상태 |
|---|---|---|
| **1. 규칙 기반 MVP** | 시세→신호→리스크→주문 파이프라인을 모의투자로 검증. 뉴스는 수집·분류만 (매매 미연동) | 🟡 진행 중 |
| **2. 안전장치·출구·운영 실동작** (Sprint 3) | 리스크 룰 실값 연동(잔고 API), 강제청산 실행부 실장, 15:15 타임컷 + ATR 손절 + R 기반 주문 수량 ([방법론 §4](docs/INVESTMENT-METHODOLOGY.md)) + 데드맨 스위치·SAFE_MODE·재가동 게이트 ([운영 §8](docs/OPERATIONS.md)) | ⬜ 예정 |
| **B. 백테스트 인프라** (Phase 2↔3 사이) | 캔들 적재, 백테스트 엔진, **현행 K=0.5 소급 검증**, Walk-Forward ([백테스트 설계](docs/BACKTEST-DESIGN.md)) — Phase 3의 전제 조건 | ⬜ 예정 |
| **3. 이벤트 → 투자 방향** | DART 공시 수집, 이벤트 택소노미 분류(LLM), 이벤트→밸류체인 매핑, 과거 반응 백테스트, 손익비 필터 ([방법론 §5](docs/INVESTMENT-METHODOLOGY.md)) | ⬜ 예정 |
| **4. 멀티 슬리브 & 실전** | 팩터 스코어링, ADR-001 Sleeve 구조(단타 30% 한도), ADR-002 Trim 선정, `@Profile("real")` 실전 전환 | ⬜ 예정 |

> **실전 전환 게이트**: ① [docs/TRADING-RULES-AUDIT.md](docs/TRADING-RULES-AUDIT.md)의 CRITICAL 4건(F-1~F-4) 해소
> ② 백테스트 소급 검증 통과([백테스트 설계 §4](docs/BACKTEST-DESIGN.md))
> ③ 24/7 운영 환경(VPS) 이전 + 강제청산 리허설([운영 §1, §7](docs/OPERATIONS.md)) —
> 셋 모두 충족 전에는 실전 계좌로 전환하지 않는다.

---

## 시스템 구성

```
┌─ 트레이딩 파이프라인 (1초 루프) ──────────────────────────────┐
│ TradingScheduler                                              │
│   -> KisMarketDataService.getRecentCandles()  [KIS REST]     │
│   -> SignalDispatcher.dispatch()   [VolatilityBreakout 평가] │
│   -> RiskEngine.check()            [7개 리스크 룰 일괄 검사] │
│   -> 통과 시 OrderEngine.execute()                           │
│   -> KisOrderClientImpl            [주문 접수, ACCEPTED 저장]│
│   -> FillPoller (3초 주기)         [체결 조회]               │
│   -> FillProcessor -> FillStateUpdater  [Position 반영]      │
│   -> OrderFilledEvent (AFTER_COMMIT)    [Telegram 알림]      │
└───────────────────────────────────────────────────────────────┘

┌─ 리서치 모듈 (매매 미연동, Phase 3에서 연동) ─────────────────┐
│ NewsAggregatorService (30분 주기)                             │
│   -> 국내 금융 RSS 4개 수집 (edaily/newspim/헤럴드/inews24)  │
│   -> 워치리스트 종목명 매칭 -> NewsSentimentAnalyzer          │
│      (키워드 기반 호재/악재 1차 분류, 최종 판단은 사람)       │
│   -> DB 저장, 30일 초과분 자동 삭제                           │
└───────────────────────────────────────────────────────────────┘

┌─ 운영 도구 ───────────────────────────────────────────────────┐
│ 대시보드(localhost:8080) / 설정 페이지 / TelegramNotifier     │
└───────────────────────────────────────────────────────────────┘
```

전략(Strategy)은 절대 주문을 직접 실행하지 않는다.
`Signal`만 반환하고, `RiskEngine` 통과 후 `OrderEngine`이 주문을 낸다.

---

## 구현 현황

| 패키지 | 역할 | 상태 |
|---|---|---|
| `market` | 시세 조회 | `KisMarketDataService` — 전일 일봉 + 당일 라이브 캔들 |
| `strategy` | 신호 생성 | `VolatilityBreakoutStrategy` (K=0.5) — 매수 신호만, 출구는 Sprint 3 |
| `signal` | 신호 집계 | `SignalDispatcher` 완료 |
| `risk` | 신호 검증·청산 | 7개 룰 등록 (아래 표), `LiquidationService` 상태머신 완료 |
| `order` | 주문 실행 | `KisOrderClientImpl` — 시장가 1주 고정(v1), 체결은 `FillPoller` |
| `position` | 계좌/포지션 | `KisPositionManager` + `ShadowPortfolio`(peakEquity 추적) |
| `research` | 뉴스 수집·분류 | RSS 수집 + 키워드 감성 분류 완료 (매매 미연동) |
| `scheduler` | 오케스트레이션 | `TradingScheduler` 완료, 중복실행 가드 포함 |
| 알림 | 텔레그램 | `TelegramNotifier` — 체결/에러/청산 알림 완료 |

### 7대 리스크 룰 — 실제 동작 상태

| 룰 | 조건 | 현재 상태 |
|---|---|---|
| `PendingOrderRule` | 보유 중이거나 미체결 매수 존재 시 중복 매수 차단 | ✅ 활성 |
| `PositionLimitRule` | 종목당 비중 10% 초과 시 매수 차단 | ✅ 활성 — 분모 교정 완료 (Gate 1) |
| `MaxPositionCountRule` | 보유 종목 5개 이상 시 매수 차단 | ✅ 활성 |
| `MarketCloseRule` | 15:20 이후 신규 매수 금지 | ✅ 활성 (KST 타임존 가정) |
| `DailyLossRule` | 일일 손실 -3% 매수 차단, -5% 강제청산 | ✅ **활성** — 실값 연동 + `RiskMonitor` 상시 감시 (Gate 1) |
| `GlobalEquityStopRule` | 전고점 대비 MDD 10% 초과 시 강제청산 | ✅ **활성** — 현금 포함 총자산 기준, peakEquity 영속화 (Gate 1) |
| `ConsecutiveLossRule` | 연속 손실 3회 시 1시간 중지 | 🔴 **비활성** — 매도 체결 데이터 필요 (Gate 3) |

계좌 단위 청산 트리거(일일 -5%·MDD 10%)는 매수 신호와 무관하게 **`RiskMonitor`가
1초 주기로 상시 감시**한다. 총자산은 KIS 잔고조회(예수금 포함)를 3초 캐시로 사용한다.

> ✅ **강제청산 실행부 구현 완료 (Gate 2)**: 트리거 시 미체결 일괄 취소 → 실잔고 조회 →
> 보유 전량 시장가 매도 → EMERGENCY_STOPPED. 아직 **모의계좌 리허설 1회 미실행** —
> 아래 명령으로 리허설을 통과해야 Gate 2 완료로 판정한다:
>
> ```cmd
> curl -X POST localhost:8080/api/trading/liquidation-drill -H "Content-Type: application/json" -d "{\"confirm\":\"CONFIRM_LIQUIDATE\"}"
> ```
>
> 전체 검증 결과: [docs/TRADING-RULES-AUDIT.md](docs/TRADING-RULES-AUDIT.md)

---

## 모의투자 실행 방법

### 1. KIS Open API 앱키 발급

[KIS Developers](https://apiportal.koreainvestment.com/) → 회원가입 → 앱키 발급 (모의투자용)

### 2. 환경변수 설정

**영구 설정 (권장):**

```cmd
setx KIS_APPKEY      "발급받은_앱키"
setx KIS_SECRETKEY   "발급받은_시크릿키"
setx KIS_ACCOUNT_NO  "계좌번호-01"
setx TELEGRAM_BOT_TOKEN  "봇토큰"    REM 선택
setx TELEGRAM_CHAT_ID    "채팅ID"    REM 선택
```

`setx` 후 새 터미널을 열어야 적용됩니다.

### 3. 실행

```cmd
run-paper.bat
```

또는 직접:

```cmd
.\gradlew.bat bootRun --args="--spring.profiles.active=paper"
```

### 정상 기동 확인 로그

```log
[KisAuthManager]  OAuth 토큰 발급 성공 — 만료: ...
[TradingScheduler] 루프 시작 — 종목: [005930]
[RiskEngine] Loaded 7 risk rules: DailyLossRule, GlobalEquityStopRule, ...
[KisPositionManager] [운영 주의] DailyLossRule / ConsecutiveLossRule 비활성 상태 ...
```

---

## 빌드 & 테스트

```cmd
REM 빌드
.\gradlew.bat build

REM 전체 테스트
.\gradlew.bat test

REM 리스크 엔진 집중 테스트
.\gradlew.bat test --tests "com.trading.risk.LiquidationServiceTest"
```

> **참고**: 프로젝트 경로에 한글이 포함되어 있어 `build.gradle`에서
> `layout.buildDirectory.set(file('C:/Users/SAMSUNG/auto_trading-build'))`로
> 빌드 출력 경로를 ASCII 경로로 우회하고 있습니다.

---

## 아키텍처 제약 (절대 규칙)

1. **Strategy → Signal → RiskEngine → OrderEngine** 순서 고정. 절대 우회 금지.
2. `RiskRule` 추가 시 `RiskEngine` 수정 없이 `@Component`만 붙이면 자동 수집.
3. 모의/실전 구현체 전환은 `@Profile("paper")` / `@Profile("real")` 로 분리.
4. 앱키/시크릿키는 절대 코드 하드코딩 금지 — 환경변수 전용.
5. 전량 청산과 부분 축소(Trim)는 `LiquidationService`의 단일 상태머신을 공유한다
   (개별 플래그 분리는 ADR-001 위반). 평시 익절/손절은 청산 경로를 우회하고
   `OrderEngine` 경로를 쓴다.
6. Sleeve A(장기 추세추종)는 구현 보류 — 자금·로직을 얹는 제안은 ADR-001 위반.

---

## 다음 작업 (우선순위 순)

Sprint 3 — 안전장치 실동작 ([검증 보고서](docs/TRADING-RULES-AUDIT.md) Gate 1~3):

1. ~~**equity 산출 교정** (F-1, F-7)~~ ✅ **완료 (Gate 1, 2026-07-07)** — `KisBalanceClient` 잔고 연동, 3초 캐시
2. ~~**RiskMonitor 신설** (F-2)~~ ✅ **완료 (Gate 1, 2026-07-07)** — 1초 상시 감시 + dailyPnl 실값(F-5 일부)
3. ~~**강제청산 실행부 실장** (F-3)~~ 🟡 **코드 완료 (Gate 2, 2026-07-08)** — 모의계좌 리허설 1회만 남음 (위 운영 주의 블록의 curl 명령) ← **완료 후 Gate 3 (타임컷)으로**
4. ~~**15:15 타임컷** (F-4) + **연속손실 카운터** (F-5 나머지)~~ ✅ **완료 (Gate 3, 2026-07-08)** — `TimeCutScheduler`(평일 15:15 KST 보유분 전량 매도, RiskEngine→OrderEngine 평시 경로) + `TradeResultTracker`(실현손익 연속손실 스트릭, `ConsecutiveLossRule` 활성화)
5. 🟡 **주문 로직 현실화** ([방법론 §4.3](docs/INVESTMENT-METHODOLOGY.md)) — **부분 완료 (P2-A, 2026-07-08)**: ✅ R 사이징(`OrderSizingService` — 1R 역산·단주 내림·왜곡 ±20% 스킵) + ✅ ATR 손절(`StopLossArmer` 체결가 기준 장착, `StopLossMonitor` 1초 감시) + ✅ KST Clock 주입(F-8). 남은 것: 지정가 분할(Price Jitter — **ADR-001 3장 미결정 파라미터**(가격 간격·주문 개수) 결정 필요), 필터 훅 4종(시간 창·거래량·트레일링·지수 — 기본 OFF, [백테스트 §3.3](docs/BACKTEST-DESIGN.md)에서 A/B 후 채택)
6. **성과 기록 기반 마련** ([거버넌스 §8](docs/PERFORMANCE-GOVERNANCE.md)): order_history에 신호 시점가·수수료·세금 기록 + `PerformanceReporter` 주간 리포트 — 이후 강등/승격 판정의 데이터 원천
7. **운영 신뢰성** ([운영 §8](docs/OPERATIONS.md)): `server.address=127.0.0.1`(즉시), 데드맨 스위치, SAFE_MODE + 기동 재동기화 시퀀스, 재가동 게이트(/start 직접 전환 금지), 거래일 캘린더
8. 시세 파싱 실패 시 예외 처리 (F-9), `MarketCloseRule` Clock 주입 (F-8 — 백테스트 엔진도 이 리팩터를 요구)

Phase 2 완료 후, Phase 3 착수 전:

- **백테스트 인프라 B-1~B-3** ([설계 문서](docs/BACKTEST-DESIGN.md)): 현행 K=0.5가 검증 안 된 값이므로, 소급 검증 결과에 따라 전략 유지/교체를 Phase 3 전에 판단한다

Phase 3 이후 ([투자 판단 방법론 §5 단계표](docs/INVESTMENT-METHODOLOGY.md),
컴포넌트 수준 실행 설계는 [추천→투자 파이프라인 설계](docs/RECOMMENDATION-TO-TRADE-DESIGN.md) 참고):

- **3a**: DART 공시 수집 + 이벤트 택소노미 분류 — LLM으로 뉴스/공시를 `{유형, 방향, 강도, 대상}`으로 구조화
- **3b**: 이벤트→밸류체인 매핑 테이블 + 재무 필터(ROE/부채비율/유동성 — 탈락용)
- **3c**: 이벤트 유형별 과거 주가 반응 백테스트 → 검증된 유형만 매매 신호로 승격
- **3d**: `RiskRewardRule` 신설 — 기대수익(이벤트 통계) vs 손절폭(ATR) 손익비 2:1 미만이면 진입 차단
- **4**: 팩터 스코어링·Capacity Scaling (ADR-002), Price Jitter 분할주문, `@Profile("real")` 실전 전환
