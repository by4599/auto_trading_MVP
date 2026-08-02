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

현재는 그 1단계로, Spring Boot + 한국투자증권(KIS) Open API로 **매매 유니버스(최대 20종목,
시드 005930)**를 **모의투자 계좌**에서 자동매매하며 파이프라인을 검증하고 있습니다. 전략은
변동성 돌파(VB) + 이동평균 정배열 돌파 + 눌림목 반등 스캘핑 3방식(지갑 칸 분리, paper 전용)이나,
**세 전략 모두 백테스트 불합격**이라 실전 승격은 불가 — 파이프라인·안전장치 검증 목적으로만 가동한다
(검증된 유일한 후보와 판정 이력은 [docs/BACKTEST-DESIGN.md](docs/BACKTEST-DESIGN.md)).

---

## 운영·개발 로드맵

| Phase | 목표 | 상태 |
|---|---|---|
| **1. 규칙 기반 MVP** | 시세→신호→리스크→주문 파이프라인을 모의투자로 검증. 뉴스는 수집·분류만 (매매 미연동) | 🟡 진행 중 |
| **2. 안전장치·출구·운영 실동작** (Sprint 3) | 리스크 룰 실값 연동(잔고 API), 강제청산 실행부 실장, 15:15 타임컷 + ATR 손절 + R 기반 주문 수량 ([방법론 §4](docs/INVESTMENT-METHODOLOGY.md)) + 데드맨 스위치·SAFE_MODE·재가동 게이트 ([운영 §8](docs/OPERATIONS.md)) | ✅ 완료 (Gate 1~3·P2-A·운영 A/B). **청산 리허설 깨끗한 1회만 남음** |
| **B. 백테스트 인프라** (Phase 2↔3 사이) | 캔들 적재, 백테스트 엔진, K=0.5 소급 검증, Walk-Forward ([백테스트 설계](docs/BACKTEST-DESIGN.md)) | ✅ 완료 (2026-07). **판정 ❌: 3방식 전부 불합격**, 손익비 재설계로 후보 1건만 §4 통과(잠정) |
| **3. 이벤트 → 투자 방향** | DART 공시 수집, 이벤트 택소노미 분류(LLM), 이벤트→밸류체인 매핑, 과거 반응 백테스트, 손익비 필터 ([방법론 §5](docs/INVESTMENT-METHODOLOGY.md)) | 🟡 수집부·B-4 엔진 완료, 매매 연동 예정 |
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
│      (모든 KIS 호출은 KisRateLimiter가 초당 한도로 균등 배분)│
│   -> SignalDispatcher.dispatch()   [VB·MA돌파·스캘핑 평가]   │
│   -> RiskEngine.check()            [8개 룰+필터 일괄 검사]   │
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
| `market` | 시세 조회 | `KisMarketDataService`(전일 일봉+당일 라이브) + `KisRateLimiter`(초당 한도 균등 배분) |
| `strategy` | 신호 생성 | VB(K=0.5)·MA 정배열 돌파·눌림목 스캘핑 3방식 — 매수 신호만. 출구는 타임컷·ATR 손절 완료 |
| `signal` | 신호 집계 | `SignalDispatcher` 완료 |
| `risk` | 신호 검증·청산 | 8개 룰 + 필터 4종 등록(아래 표), `LiquidationService` 상태머신 완료 |
| `order` | 주문 실행 | `KisOrderClientImpl` — 시장가, **R 사이징 수량**(`OrderSizingService`), 체결은 `FillPoller`→`FillProcessor` |
| `position` | 계좌/포지션 | `KisPositionManager`(신선도 플래그) + `ShadowPortfolio`(peakEquity) + `ShadowPortfolioReconciler`(주기 재동기화) |
| `research` | 뉴스·공시 | 뉴스 RSS 감성 분류 + DART 공시 수집 완료 (매매 미연동) |
| `scheduler` | 오케스트레이션 | `TradingScheduler`(유니버스 라운드로빈)·`TimeCutScheduler`(15:15 매도)·`RunStreakRecorder` |
| 알림 | 텔레그램 | `TelegramNotifier` — 체결/에러/청산, 장중~장마감에만 발송 |

### 리스크 룰 7대 + 확장(`BucketBudgetRule`) — 실제 동작 상태

| 룰 | 조건 | 현재 상태 |
|---|---|---|
| `PendingOrderRule` | 보유 중이거나 미체결 매수 존재 시 중복 매수 차단 | ✅ 활성 |
| `PositionLimitRule` | 종목당 비중 10% 초과 시 매수 차단 | ✅ 활성 — 분모 교정 완료 (Gate 1) |
| `MaxPositionCountRule` | 보유 종목 5개 이상 시 매수 차단 | ✅ 활성 |
| `MarketCloseRule` | 15:20 이후 신규 매수 금지 | ✅ 활성 (KST 타임존 가정) |
| `DailyLossRule` | 일일 손실 -3% 매수 차단, -5% 강제청산 | ✅ **활성** — 실값 연동 + `RiskMonitor` 상시 감시 (Gate 1) |
| `GlobalEquityStopRule` | 전고점 대비 MDD 10% 초과 시 강제청산 | ✅ **활성** — 현금 포함 총자산 기준, peakEquity 영속화 (Gate 1) |
| `ConsecutiveLossRule` | 연속 손실 3회 시 1시간 중지 | ✅ **활성** — `TradeResultTracker` 실현손익 스트릭 연동 (Gate 3) |
| `BucketBudgetRule` | 지갑 칸 잠금/예산 소진 시 매수 차단 | ✅ 활성 (paper 전용, `trading.bucket.enabled` OFF면 통과) |

계좌 단위 청산 트리거(일일 -5%·MDD 10%)는 매수 신호와 무관하게 **`RiskMonitor`가
1초 주기로 상시 감시**한다. 총자산은 KIS 잔고조회(예수금 포함)를 3초 캐시로 사용한다.

> **운영 신뢰성 강화 (2026-08)**: ① 모든 KIS 호출을 초당 한도(모의 2/실전 20,
> `kis.rate-limit-per-sec`)로 균등 배분하는 `KisRateLimiter` — EGW00201·SAFE_MODE 플래핑 근절.
> ② `RiskMonitor`·`StopLossMonitor`는 잔고 API 실패로 낡은 스냅샷이면 청산/손절 판정 스킵
> (`Account.isFresh()` — 낡은 값 헛발동 방지). ③ 10분 주기 `ShadowPortfolioReconciler`가
> 2주기 지속·신선·장중일 때 브로커-DB 불일치를 자동 보정. ④ 체결누락 desync는 취소불가=체결
> 신호로 실잔고 대사 자동복구(뿌리 수정은 예정). ⑤ 텔레그램은 장중~장마감에만 발송.

> ✅ **강제청산 실행부 구현 완료 (Gate 2)**: 트리거 시 미체결 일괄 취소 → 실잔고 조회 →
> 보유 전량 시장가 매도 → EMERGENCY_STOPPED. **모의계좌 리허설 깨끗한 1회 미완**
> (2026-07-30 시도했으나 KIS 레이트리밋 폭주로 확인 불가 — 레이트 조정자 배포 후 재시도).
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
[KisApiClient]  KIS OAuth 토큰 발급 완료, 만료: ...
[RiskEngine]    Loaded 12 risk rules: BucketBudgetRule, ConsecutiveLossRule, DailyLossRule,
                DisclosureCooldownRule, EntryTimeWindowRule, GlobalEquityStopRule, IndexRegimeRule,
                IndexTrendRule, MarketCloseRule, MaxPositionCountRule, PendingOrderRule, PositionLimitRule
[RiskMonitor]   계좌 감시 시작 — 일일손실 청산 -5.0%, MDD 한도 10.0%
[Reconciler]    기동 재동기화 완료 — 자동 가동(RUNNING)
```
> 12개 = 리스크 룰 8종(위 표) + 필터/이벤트 룰 4종(`EntryTimeWindow`·`IndexRegime`·`IndexTrend`·
> `DisclosureCooldown` — 기본 OFF, 백테스트 A/B로 채택된 것만 paper에서 ON).

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

> **참고**: 예전에는 프로젝트 경로에 한글('개발')이 포함돼 빌드가 깨졌으나,
> 2026-07 부모 폴더를 `workspace`(ASCII)로 rename해 근본 해소되었습니다.
> `build.gradle`의 `TRADING_BUILD_DIR` 빌드 출력 경로 우회는 더는 필수가 아니지만
> 무해하여 안전망으로 남겨 두었습니다.

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

> **현재 위치 (2026-08)**: Sprint 3(안전장치·출구·운영)와 백테스트 인프라는 완료됐고, 이제
> **운영 신뢰성 로드맵 Phase 1**을 진행 중이다 — ✅ 레이트 조정자·주기 재동기화·데이터 품질 게이트
> 완료, ⏳ **1.3 체결누락 뿌리 수정**(월요일 장중 진단, `_workspace/1.3_design_fill-tracking-diagnosis.md`).
> 이후 Phase 2(검증된 전략 확보)·Phase 3(실전 전환)은 백테스트 판정·사람 게이트가 선행한다.

Sprint 3 — 안전장치 실동작 ([검증 보고서](docs/TRADING-RULES-AUDIT.md) Gate 1~3), 아래는 완료 이력:

1. ~~**equity 산출 교정** (F-1, F-7)~~ ✅ **완료 (Gate 1, 2026-07-07)** — `KisBalanceClient` 잔고 연동, 3초 캐시
2. ~~**RiskMonitor 신설** (F-2)~~ ✅ **완료 (Gate 1, 2026-07-07)** — 1초 상시 감시 + dailyPnl 실값(F-5 일부)
3. ~~**강제청산 실행부 실장** (F-3)~~ 🟡 **코드 완료 (Gate 2, 2026-07-08)** — 모의계좌 리허설 **깨끗한 1회 미완**(2026-07-30 시도, 레이트리밋으로 확인 불가 → 레이트 조정자 배포 후 재시도). 위 curl 명령으로 통과 필요
4. ~~**15:15 타임컷** (F-4) + **연속손실 카운터** (F-5 나머지)~~ ✅ **완료 (Gate 3, 2026-07-08)** — `TimeCutScheduler`(평일 15:15 KST 보유분 전량 매도, RiskEngine→OrderEngine 평시 경로) + `TradeResultTracker`(실현손익 연속손실 스트릭, `ConsecutiveLossRule` 활성화)
5. 🟡 **주문 로직 현실화** ([방법론 §4.3](docs/INVESTMENT-METHODOLOGY.md)) — **부분 완료 (P2-A, 2026-07-08)**: ✅ R 사이징(`OrderSizingService` — 1R 역산·단주 내림·왜곡 ±20% 스킵) + ✅ ATR 손절(`StopLossArmer` 체결가 기준 장착, `StopLossMonitor` 1초 감시) + ✅ KST Clock 주입(F-8). 남은 것: 지정가 분할(Price Jitter — **ADR-001 3장 미결정 파라미터**(가격 간격·주문 개수) 결정 필요), 필터 훅 4종(시간 창·거래량·트레일링·지수 — 기본 OFF, [백테스트 §3.3](docs/BACKTEST-DESIGN.md)에서 A/B 후 채택)
6. **성과 기록 기반 마련** ([거버넌스 §8](docs/PERFORMANCE-GOVERNANCE.md)): 🟡 실현손익 영속화(`TradeResultTracker`→대시보드 실적)·연속 무중단 가동일(`RunStreakRecorder`) 완료. 남은 것: 수수료·세금 포함 주간 리포트(`PerformanceReporter`)
7. ~~**운영 신뢰성** ([운영 §8](docs/OPERATIONS.md))~~ ✅ **완료 (Phase A/B, 2026-07-17~18)** — 데드맨 스위치, SAFE_MODE + 기동 재동기화, 재가동 게이트(/start 직접 전환 금지), 거래일 캘린더, KIS 장애 자동 SAFE_MODE·회복 시 자동 재개
8. 🟡 `MarketCloseRule` Clock 주입 ✅완료(F-8, P2-A). 남은 것: 시세 파싱 실패 예외 처리(F-9)

Phase 2 완료 후, Phase 3 착수 전:

- ~~**백테스트 인프라 B-1~B-3**~~ ✅ **완료 (2026-07-11)** — 판정 **❌ 불합격**: 검증 PF 0.66·기대값 −0.33%·MDD 50.4%, 10개 Walk-Forward 윈도우 전부 PF<1.0, K 0.4~0.6 전부 미달 ([설계 문서 §7](docs/BACKTEST-DESIGN.md) 판정 상세). **현행 당일 변동성 돌파는 실전 승격 불가 — Phase 3 착수 전 전략 교체/재설계 판단 필요** (분봉 축적 후 시간창·거래량 필터 재검증이 기각 전 마지막 확인 항목)
- ~~**3방식(VB/EVENT/MIX) 재검증**~~ ✅ **완료 (2026-07-20)** — 판정 **❌ 둘 다 기각**: VB 유니버스 확장(대형주 6→중소형 포함 39종목)은 PF 0.66→0.60·MDD 50%→61.4%로 **악화**, EVENT 시가총액 세분화(LARGE/MIDSMALL 42조합)는 **CANDIDATE 0건**에 MIDSMALL이 오히려 LARGE보다 나쁜 반응 ([설계 문서 §12](docs/BACKTEST-DESIGN.md) 판정 상세). **3방식 모두 실매매 승격 불가 — 지갑 칸 게이트(EVENT/MIX 비활성) 그대로 유지**, 분봉 데이터 축적 완료 후 재검증이 다음 확인 항목

Phase 3 이후 ([투자 판단 방법론 §5 단계표](docs/INVESTMENT-METHODOLOGY.md),
컴포넌트 수준 실행 설계는 [추천→투자 파이프라인 설계](docs/RECOMMENDATION-TO-TRADE-DESIGN.md) 참고):

- **3a**: DART 공시 수집 + 이벤트 택소노미 분류 — LLM으로 뉴스/공시를 `{유형, 방향, 강도, 대상}`으로 구조화
- **3b**: 이벤트→밸류체인 매핑 테이블 + 재무 필터(ROE/부채비율/유동성 — 탈락용)
- **3c**: 이벤트 유형별 과거 주가 반응 백테스트 → 검증된 유형만 매매 신호로 승격
- **3d**: `RiskRewardRule` 신설 — 기대수익(이벤트 통계) vs 손절폭(ATR) 손익비 2:1 미만이면 진입 차단
- **4**: 팩터 스코어링·Capacity Scaling (ADR-002), Price Jitter 분할주문, `@Profile("real")` 실전 전환
