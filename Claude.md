# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 이 프로젝트는

Spring Boot 기반 국내주식 자동매매 시스템. 한국투자증권(KIS) Open API로
**모의투자 계좌**에서 삼성전자(005930) 1종목을 변동성 돌파 전략으로 자동매매하며
파이프라인을 검증하는 것이 Phase 1 목표. 최종 목표는 뉴스·공시 이벤트 기반
자동 트레이딩 (로드맵은 `README.md`, 투자 판단 근거는 `docs/INVESTMENT-METHODOLOGY.md`).

## 스코프 지킴이 (세션 시작 시 필수 — 갭 체크 먼저)

구현 작업을 시작하기 전에 반드시 루트의 `SCOPE_GUARDIAN.md`를 먼저 읽고
"[릴리즈 갭 체크]"를 출력한다. 갭 체크 없이 새 기능 구현으로 바로 들어가지 않는다.

- 새 기능/리팩토링/개선 요청은 SCOPE_GUARDIAN.md 2번 판단 프로세스를 통과시킨다.
  릴리즈 필수가 아니면 구현하지 말고 `BACKLOG.md`에 기록한다.
- 릴리즈 체크리스트 항목의 추가/삭제는 **사용자가 명시적으로 승인할 때만** 한다.
  대화 중 필수 항목이 새로 드러나면 표에 바로 넣지 말고 먼저 사용자에게 확인한다.
- 깊은 코드 대조가 필요하면 `scope-guardian` 서브에이전트를 호출한다.

## 빌드 · 실행 · 테스트 명령

프로젝트 경로에 한글(`개발`)이 포함되어 있어 Gradle test worker가 클래스패스를
percent-encode하지 못해 깨진다. 두 가지를 항상 지킬 것:

1. **빌드 출력 경로**를 ASCII 경로로 강제 (`build.gradle`이 이미 처리 —
   `TRADING_BUILD_DIR` 환경변수 또는 기본값 `C:/Users/SAMSUNG/auto_trading-build`)
2. **`./gradlew.bat`이 이 환경(Git Bash)에서 걸릴 수 있다** — 걸리면 아래처럼
   캐시된 Gradle 배포본을 직접 호출한다 (배포본 경로는 `gradle-wrapper.properties`의
   버전에 맞춰 `~/.gradle/wrapper/dists/`에서 확인):

```bash
TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build \
  "C:/Users/SAMSUNG/.gradle/wrapper/dists/gradle-9.2.0-bin/<hash>/gradle-9.2.0/bin/gradle" \
  test --console=plain
```

```cmd
REM 정석 경로 (PowerShell/cmd에서는 보통 정상 동작)
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat test --tests "com.trading.risk.LiquidationServiceTest"

REM 모의투자 실행
run-paper.bat
REM 또는
.\gradlew.bat bootRun --args="--spring.profiles.active=paper"

REM 백테스트 실행 (엔진은 com.trading.backtest, 별도 backtest-db 사용)
.\gradlew.bat bootRun --args="--spring.profiles.active=backtest"
```

- `TRADING_BUILD_DIR` 미설정 시 한글 경로로 폴백되어 **테스트가 전부
  `ClassNotFoundException`으로 죽는다** — 반드시 설정할 것.
- Java 25 + 인라인 Mockito는 구체 클래스(예: `TradingUniverseService`) 목킹이
  불안정하다 → 테스트는 리포지토리 등 **인터페이스를 목**으로, 서비스는 실객체로
  구성하는 패턴을 따른다 (`mock(XxxRepository.class)` — 기존 테스트 다수가 이 패턴).
- `gradle.properties`에 `org.gradle.daemon=false` — 빌드마다 데몬을 새로 포크해
  10~20초 소요된다. 정상이다.
- 대시보드 정적 리소스(`static/`) 변경은 `bootRun` 재시작이 필요하다
  (빌드 출력에서 서빙되며, `application.yml`의 `no-cache: true`는 브라우저
  캐시만 무력화할 뿐 재빌드를 대신하지 않는다).

## 저장소 루트의 낡은 중복 파일 (읽지도 편집하지도 말 것)

저장소 **루트**에 `Account.java`, `RiskEngine.java`, `TradingScheduler.java`,
`KisApiClient.java` 등 40여 개 `.java` 파일과 `application-paper.yml`이
git에 커밋되어 있다. 이들은 최초 커밋(`2325b9d`) 이후 한 번도 갱신되지 않은
**리팩터링 이전 잔재**이며, 지금의 실제 소스는 전부 `src/main/java/com/trading/**`
(패키지 구조)와 `src/main/resources/**`에 있다. 루트 파일들은 `src/` 밖에 있어
Gradle 빌드에 포함되지 않지만 이름이 같아 혼동하기 쉽다.
**작업 시 항상 `src/` 하위 파일을 찾아 수정한다.** 루트 중복 파일 정리는
이 작업의 범위를 벗어나면 먼저 사용자에게 삭제 여부를 확인한다.

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

- 종목: `trading_universe` 테이블 (최초 시드 005930, 최대 20종목).
  편입/제외는 대시보드 UI에서 **사람이 직접** (수동 게이트 G1) — 뉴스 워치리스트와 별개.
  KIS 모의 레이트리밋 때문에 스케줄러는 틱당 1종목 라운드로빈 (N종목 = 종목당 N초 간격)
- 전략: `VolatilityBreakoutStrategy` (변동성 돌파, K=0.5) — 매수 신호.
  출구는 `TimeCutScheduler`(평일 15:15 KST 보유분 전량 매도, Gate 3)
  ⚠ **B-3 백테스트 불합격 (2026-07-11)**: 검증 PF 0.66, 전 윈도우 PF<1.0 —
  실전 승격 불가, 전략 교체/재설계 판단 대기 (BACKTEST-DESIGN §7).
  모의투자는 파이프라인 검증 목적으로만 계속 운영.
  ⚠ **3방식 재검증도 기각 (2026-07-20)**: VB 유니버스 확장(중소형 33종목 추가)은
  PF 0.60·MDD 61.4%로 오히려 악화, EVENT 시가총액 세분화(LARGE/MIDSMALL)도
  42조합 전부 CANDIDATE 미달 — 방식1/2/3 실매매 구현 여전히 보류 (BACKTEST-DESIGN §12)
- 계좌: 한투 **모의투자** 계좌 (`@Profile("paper")`, 실전 전환은
  `docs/TRADING-RULES-AUDIT.md`의 CRITICAL 4건 해소 후)
- 주문: 시장가, 수량은 R 사이징(`OrderSizingService` — 1R=계좌 1% ÷ ATR 손절폭, 단주 내림).
  지정가 분할(Price Jitter)은 ADR-001 미결정 파라미터 해소 후
- 지갑 칸 실험 (2026-07-19, `com.trading.bucket`): 방식별 자금 칸 분리 —
  VB(방식1·돌파)/EVENT(방식2·이벤트)/MIX(방식3·혼합) 각 1,000만원 한도.
  paper 전용(`trading.bucket.enabled` — **backtest에서 켜지 말 것**, B-3 결정성).
  칸 ON이면 R 사이징의 "계좌"가 칸 자산(배분금+실현손익)으로 바뀌고 칸 가용 현금으로
  수량 캡. 이름표 흐름: Signal→OrderHistory→Position→TradeResult (null=VB 레거시).
  EVENT/MIX는 B-4 합격 재료 확보 후 사람이 yml에서 켠다 (게이트 G2)
- 알림: 텔레그램 (체결/에러/청산)
- 뉴스(`research` 패키지): 수집·분류·**추천 표시**까지 — 매매 미연동 (연동은 Phase 3)
- 공시(DART): 유니버스∪워치리스트 대상 30분 주기 수집·표시 전용.
  이벤트 백테스트(B-4) 표본이므로 **자동 삭제 금지**. DART_API_KEY 미설정 시 조용히 스킵

## 7대 리스크 룰 + 확장 (`com.trading.risk`, RiskEngine 자동 주입)

| 룰 | 조건 | 실제 동작 상태 |
|---|---|---|
| `PendingOrderRule` | 보유 중/미체결 매수 존재 시 중복 매수 차단 | ✅ 활성 |
| `PositionLimitRule` | 종목당 비중 최대 10% | ✅ 활성 (Gate 1에서 분모 교정) |
| `MaxPositionCountRule` | 최대 보유 종목 5개 | ✅ 활성 |
| `MarketCloseRule` | 15:20 이후 신규 매수 금지 | ✅ 활성 (P2-A — KST 고정 Clock 주입, F-8 해소) |
| `DailyLossRule` | -3% 매수 차단 / -5% 강제청산 | ✅ 활성 (Gate 1 — dailyPnl 실값 + `RiskMonitor` 상시 감시) |
| `GlobalEquityStopRule` | 전고점 대비 MDD 10% 초과 시 강제청산 | ✅ 활성 (Gate 1 — 현금 포함 equity) |
| `ConsecutiveLossRule` | 연속 손실 3회 시 1시간 중지 | ✅ 활성 (Gate 3 — `TradeResultTracker` 실현손익 스트릭 연동) |
| `BucketBudgetRule` | 지갑 칸 잠금/예산 소진 시 매수 차단 | ✅ 활성 (paper 전용 — `trading.bucket.enabled` OFF면 통과) |

> 강제청산 실행부(`KisBrokerageApiClient`)는 Gate 2에서 실구현 완료 —
> 단, **모의계좌 청산 리허설 1회 성공 전까지 Gate 2 완료 판정 아님**
> (`POST /api/trading/liquidation-drill`). F-번호와 상세 근거는 `docs/TRADING-RULES-AUDIT.md` 참고.

## 패키지 구조 및 큰 흐름

```
TradingScheduler (1초 루프, 유니버스 라운드로빈)
  -> MarketDataService.getRecentCandles()          [market]
  -> SignalDispatcher -> Strategy.evaluate()        [signal, strategy]  → Signal만 반환
  -> RiskEngine.check()  — RiskRule 구현체 전원 순회 [risk]
  -> 통과 시 OrderEngine.execute()                  [order]
  -> KisOrderClientImpl 주문 접수 -> FillPoller(3초) -> FillProcessor -> FillStateUpdater
  -> OrderFilledEvent(AFTER_COMMIT) -> TelegramNotifier
  -> Position/Account 갱신                          [position]
```

- `market` 시세 조회 / `strategy` 신호 생성 / `signal` 신호 집계 /
  `risk` 검증·강제청산 상태머신 / `order` 주문 실행·체결 파이프라인 /
  `position` 계좌·포지션 상태(`ShadowPortfolio`의 peakEquity 추적 포함) /
  `scheduler` 오케스트레이션(`TradingScheduler`, `TimeCutScheduler`) /
  `universe` 매매 대상 관리(게이트 G1) / `research` 뉴스·DART 공시 수집·분류
  (매매 미연동) / `bucket` 지갑 칸(방식별 자금 분리, paper 전용) /
  `backtest` 백테스트 엔진(`@Profile("backtest")`, 전용 DB·Clock) /
  `dashboard`·`settings`·`control`(웹 운영 도구, localhost:8080)
- 각 KIS 연동 인터페이스(`MarketDataService`, `KisOrderClient`, `PositionManager`,
  `BrokerageApiClient` 등)는 `paper`/`real`/`backtest` 프로필별로 구현체를
  갈아끼운다 — 새 구현체를 추가할 때도 인터페이스 시그니처는 고정.
- `KisApiClient`가 모든 KIS REST 연동(OAuth 토큰 포함)이 공유하는 공통 HTTP 클라이언트.
- 문서 지도: 로드맵·구현 현황은 `README.md`, 투자 판단 방법론은
  `docs/INVESTMENT-METHODOLOGY.md`, 백테스트 엔진·합격 기준은
  `docs/BACKTEST-DESIGN.md`, 장애 대응·재가동은 `docs/OPERATIONS.md`,
  손실 시 자금 축소·강등 규칙은 `docs/PERFORMANCE-GOVERNANCE.md`,
  리스크 룰 검증 이력(F-번호)은 `docs/TRADING-RULES-AUDIT.md`,
  멀티 슬리브 설계 결정은 `ADR-001-multi-sleeve-risk-architecture_1.md`.

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
- `RecommendationService` (`research`) — 관심 종목 뉴스 감성 집계 →
  매수 후보/관망/주의 추천 (대시보드 표시 전용, 매매 미연동)
- P2-A (2026-07-08) — `AtrCalculator`(ATR 14) + `OrderSizingService`(R 수량 역산) +
  `StopLossArmer`/`StopLossMonitor`(체결가 기준 ATR 손절 장착·1초 감시) + `ClockConfig`(KST)
- `universe` 패키지 (2026-07-09) — `trading_universe` 매매 대상 관리 (시드 005930,
  상한 20, 웹 UI 편입/제외) + `TradingScheduler` 라운드로빈 + 대시보드 유니버스 카드
- DART 공시 수집 (2026-07-09, Phase 3a 수집부) — `DartDisclosureService`(30분 주기,
  접수번호 중복 방지) + `HttpDartApiClient`(corpCode ZIP StAX 파싱)
  + 대시보드 공시 카드. HTTP는 `DartApiClient` 인터페이스 뒤 (Mockito 제약 대응)
- B-4 이벤트 백테스트 (2026-07-11, Phase 3c 엔진) — `DisclosureEventClassifier`
  (공시 택소노미 13유형) + `EventBacktestPipeline`(`--backtest.mode=events`:
  공시 3년 소급 백필 → 유형별 D+1/5/10/20 통계 → `event_type_registry` CANDIDATE 표기).
  **PROMOTED 승격은 사람만 (게이트 G2)**. 실행은 DART_API_KEY 발급 대기
- 테마 파급 통계 (2026-07-14, B-4 확장) — `SpilloverStatsBacktester`: 앵커 대형주
  공시 → 같은 테마 밸류체인 종목들의 D+N 초과수익 (표본 단위 = 앵커 이벤트 1건,
  체인 횡단면 중앙값으로 접어 교차 상관 부풀림 차단). 테마는 `backtest.event-themes`의
  `-anchor`/`-chain` 키 쌍(파일럿: 반도체), 레지스트리 키 `SPILL:테마:유형`.
  상세: BACKTEST-DESIGN.md §10
- 진입 트리거 실험 (2026-07-14, B-4 확장) — `EntryTriggerBacktester`: 같은 파급
  이벤트 표본에서 즉시/눌림반등/돌파 진입을 격리 비교 (청산 D+10 종가 고정,
  발동 기한 D+5, 발동률 별도 집계). v1 일봉 근사 — 분봉 정밀 재검증 전제,
  리포트 전용(레지스트리 미기록). 상세: BACKTEST-DESIGN.md §11
- 백테스트 인프라 B-1~B-3 (2026-07-10, `com.trading.backtest`) — `candle_history` 적재
  (`CandleBackfillService` 3년 일봉+KOSPI, `MinuteCandleCollector` 15:40 당일 분봉 전방 축적)
  + 일봉 근사 엔진(`BacktestRunner`/`DailyBarSimulator` — 이분탐색 진입가, 비관적 손절,
  왕복 비용 0.41%, `MutableClock` @Primary, 전용 `backtest-db`) + B-3 검증
  (`WalkForwardEngine` 6/3/3 + K 민감도 + 필터 A/B → `logs/backtest/REPORT-*.md`,
  합격 시 `docs/BACKTEST-BASELINE.yml`). 실행: `--spring.profiles.active=backtest`.
  K는 `StrategyParameters`(기본 0.5), 필터 5종은 `FilterProperties`(기본 OFF —
  단, **paper는 A/B 채택으로 트레일링 1% + 공시 쿨다운 5일 ON**, BACKTEST-DESIGN §9.
  시간창·거래량 필터는 분봉 축적 후 검증). 상세: BACKTEST-DESIGN.md §6 v1 구현 노트.
  ⚠️ `@EnableScheduling`은 `SchedulingConfig`(@Profile("!backtest"))에 있다 —
  백테스트 결정성 때문에 애플리케이션 클래스로 되돌리지 말 것
- 운영 신뢰성 Phase A/B (2026-07-17~18) — SAFE_MODE 기동 재동기화 시퀀스 +
  재가동 게이트(`/api/trading/resume`, `/start` 직접 전환 금지) + 데드맨 스위치
  (외부 하트비트 ping) + 거래일 캘린더(`market-calendar.yml`) + KIS API 장애 시
  자동 SAFE_MODE 전환 + 연결 회복 시 자동 재개(RUNNING) + 앱 기동 시 자동 RUNNING
- 지갑 칸 실험 (2026-07-19, `com.trading.bucket`) — VB/EVENT/MIX 방식별 1,000만원
  자금 칸 분리, `BucketBudgetRule` 신설. 위 "현재 스코프" 항목 참고
- 3방식 재검증 (2026-07-20) — `LiquidityScreener`(일평균 거래대금 필터, 백테스트 전용)로
  VB 유니버스를 중소형 33종목까지 확장 재검증 + `EventBacktestPipeline`에 LARGE/MIDSMALL
  시가총액 세분화 재집계 추가. 판정은 위 "현재 스코프" 항목 참고 — 둘 다 기각, 게이트 불변.
  이 과정에서 백테스트 텔레그램 격리가 OS 환경변수로 무력화되는 사고 발견 —
  `TradingEventListener`에 `@Profile("!backtest")` 추가로 구조적 차단 (BACKTEST-DESIGN §12)

## 미구현 / 알려진 결함 (제안·수정 시 주의)

1. 모의계좌 강제청산 리허설 미실행 — Gate 2 완료 판정 보류 (사용자 실행 필요)
2. 지정가 분할(Price Jitter) 미구현 — ADR-001 3장 파라미터(가격 간격·주문 개수) 결정 선행
3. 타임컷은 15:15에 앱이 꺼져 있으면 해당일 건너뜀 (거래일 캘린더는 `market-calendar.yml`로 해소됨)
4. 연속손실 기록은 매도 체결 청크 단위 — 부분 체결 매도 시 라운드트립 집계로 전환 필요
   (R 사이징으로 수량 > 1 매도가 가능해져 발생 확률 상승)

해소됨: F-1/F-2/F-7 (Gate 1, 2026-07-07) · F-3 (Gate 2) · F-4/F-5 (Gate 3) · F-8 (P2-A, 2026-07-08)
· 부분 체결 매수 손절 미장착 (2026-07-20 — `OrderPartialFilledEvent` 신설, 부분 체결분도
  `StopLossArmer`가 장착. 릴리즈 검증용 `RunStreakRecorder`(연속 무중단 가동일 기록,
  `GET /api/trading/run-streak`)도 같은 날 추가)

Sprint 3 작업 순서는 `README.md`의 "다음 작업" 섹션 기준.

## 코딩 컨벤션

- 모든 외부 연동(한투 API)은 인터페이스 뒤에 숨기고, application.yml의
  profile(`real`/`paper`/`backtest`)로 구현체를 교체할 수 있게 한다.
- appkey/secretkey는 절대 코드에 하드코딩하지 않는다. application.yml +
  환경변수로 분리하고, `.gitignore`에 실제 키가 든 yml은 반드시 추가한다
  (`application-paper-local.yml`, `application-real.yml`, `*.env` 이미 등록됨).
- 새 클래스를 추가하면 같은 패키지의 기존 클래스 스타일(생성자 주입,
  `@Component`)을 그대로 따른다.
- 테스트에서 구체 클래스를 Mockito로 목킹하지 않는다 (Java 25 인라인 목 제약).
  리포지토리 등 인터페이스만 목으로 만들고 서비스는 실객체로 조립한다.

## 사용자에게 피드백·설명할 때 (매우 중요)

**10살 아이에게 설명한다는 생각으로 쓴다.** 사용자는 코드 전문가가 아니다.

- 영어·전문용어(SAFE_MODE, PF, MDD, endpoint, RestClient 등)를 그대로 쓰지 말고,
  꼭 필요하면 쉬운 우리말 뜻을 괄호로 붙인다. 예: "SAFE_MODE(새로 사는 건
  멈추고 지키기만 하는 상태)".
- 결론을 한 줄로 먼저, 그다음 쉬운 설명. 표·비유를 적극 활용한다.
- 숫자·기록을 물으면 "몇 건 샀고 얼마 벌었다/잃었다"처럼 결과 위주로 말한다.
- "그게 뭐야?"라는 되물음이 나오면 설명이 어려웠다는 신호 — 더 쉽게 다시 쓴다.
