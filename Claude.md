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

## 하네스: 자동매매 개발·운영

**목표:** 전략 판정·안전장치 감사·구현·증거 검증을 분리된 역할로 교차 확인시켜,
검증되지 않은 변경이 모의투자·실전으로 새지 않게 한다.

**트리거:** 이 프로젝트의 실질적 작업(전략 추가·수정, 백테스트 검증, 리스크 룰 변경,
주문·체결 파이프라인 수정, 운영 신뢰성 개선, 릴리즈 갭 해소)을 요청받으면
`trading-orchestrator` 스킬을 사용한다. 단순 질문·설명 요청은 직접 답한다.

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-07-22 | 초기 구성 (에이전트 4 + 스킬 5) | `.claude/agents/`, `.claude/skills/` | 하네스 도입 — 기존 `scope-guardian`은 문지기로 재사용 |
| 2026-07-22 | 자동 적용 설정 (SessionStart 훅 + 커밋 전 점검 훅 + `TRADING_BUILD_DIR`) | `.claude/settings.json`, `.claude/hooks/` | 매 세션 수동 상기 없이 하네스가 걸리도록. 빌드 경로 미설정으로 테스트가 전부 죽는 사고를 구조적으로 차단 |

## 빌드 · 실행 · 테스트 명령

예전에는 프로젝트 경로에 한글(`개발`)이 포함돼 Gradle test worker가 클래스패스를
percent-encode하지 못해 깨졌다. **2026-07 부모 폴더를 `workspace`(ASCII)로 rename해
근본 원인이 해소됐다** — 이제 아래 1번은 필수가 아니다(무해한 안전망). 2번은 그대로 유효:

1. **빌드 출력 경로** ASCII 강제 (`build.gradle`이 이미 처리 — 더는 필수 아님, 안전망 —
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

- 경로가 ASCII가 된 지금은 `TRADING_BUILD_DIR` 미설정이어도 테스트가 정상 동작한다
  (예전엔 미설정 시 한글 경로 폴백으로 **테스트가 전부 `ClassNotFoundException`으로
  죽었다** — 만약 그 증상이 재현되면 이 설정부터 확인).
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
  42조합 전부 CANDIDATE 미달 — 원래의 EVENT/MIX(공시 기반) 재료는 실매매 승격 불가로
  폐기 (BACKTEST-DESIGN §12). 같은 날 사용자 판단으로 방식2·3을 다른 전략으로
  교체했고, 2026-07-22 소급 백테스트 결과도 둘 다 불합격 — 아래 지갑 칸 항목 참고
- 계좌: 한투 **모의투자** 계좌 (`@Profile("paper")`, 실전 전환은
  `docs/TRADING-RULES-AUDIT.md`의 CRITICAL 4건 해소 후)
- 주문: 시장가, 수량은 R 사이징(`OrderSizingService` — 1R=계좌 1% ÷ ATR 손절폭, 단주 내림).
  지정가 분할(Price Jitter)은 ADR-001 미결정 파라미터 해소 후
- 지갑 칸 실험 (2026-07-19, `com.trading.bucket`): 방식별 자금 칸 분리 —
  VB(방식1·돌파)/EVENT(방식2)/MIX(방식3) 각 1,000만원 한도.
  paper 전용(`trading.bucket.enabled` — **backtest에서 켜지 말 것**, B-3 결정성).
  칸 ON이면 R 사이징의 "계좌"가 칸 자산(배분금+실현손익)으로 바뀌고 칸 가용 현금으로
  수량 캡. 이름표 흐름: Signal→OrderHistory→Position→TradeResult (null=VB 레거시).
  ⚠ **2026-07-20 방식2·3 전략 교체 (커밋 c2ce60e)**: 원래의 공시 이벤트(EVENT)·혼합(MIX)
  재료가 B-4에서 전량 기각되자, 사용자 판단으로 방식2를 **이동평균 정배열 돌파**
  (`MovingAverageBreakoutStrategy` — MA5>MA20>MA60>MA120 정배열 + MA20 상향 돌파),
  방식3을 **눌림목 반등 스캘핑**(`ScalpingStrategy` — 롤링창 고점 대비 pullback 후
  rebound 시 진입, 목표익절 `takeProfitPct`)으로 교체해 모의투자에 가동했다.
  (enum 상수명 EVENT/MIX는 DB 컬럼 호환 때문에 그대로 유지 — displayName만 새 의미.)
  ⚠ **2026-07-22 소급 백테스트 완료 — 둘 다 불합격 (BACKTEST-DESIGN §13)**:
  MA돌파 PF 0.55(591건), 스캘핑 PF 0.33·MDD 68.4%(1,062건, 파라미터 4종 ±20%
  민감도도 전부 PF 0.25~0.36 — 구제 불가). VB 회귀 확인도 PF 0.71로 재불합격.
  **3방식 전부 실전 승격 불가(게이트 G2 미발동)** — 파이프라인 검증 목적의
  모의투자만 계속하며, 세 전략 재설계 여부는 사용자 판단 대기.
  ⚠ **2026-07-22 Exit Lab / Risk Lab — 손익비 재설계 (BACKTEST-DESIGN §14)**: 진입 고정,
  출구를 다일 보유+트레일링으로 바꿔 스윕(`--backtest.mode=exit-lab`). 손익비가 뒤집혀
  MA 정배열 진입은 넓은 54종목·수백 건에서 PF 1.48~1.53·기대값 양(+)의 **실재 엣지**를
  보였으나 MDD ~21%로 불합격(막힌 곳은 기대값이 아니라 리스크). VB 돌파는 넓은 유니버스에서
  붕괴. → **Risk Lab(`--backtest.mode=risk-lab`)에서 사이징 축소로 해소**: MA 정배열 진입 +
  다일 트레일링(ATR1.0·최대20일·트레일 arm1%/trail3%) + **0.5R·동시5** 프로필이 **PF 1.96·
  기대값 +1.30%·MDD 9.9%·781건으로 §4 첫 통과**(10창 중 8창 양(+), 레짐 편중 아님). 기준선
  `docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml` 기록.
  ⚠ **회귀 앵커의 정본은 문서가 아니라 `docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml`이다 (2026-08-17)**
  — 앵커 수치를 문서·코드에 복제하지 말고 그 파일을 볼 것. risk-lab 실행이 이 파일을 읽어
  **자동 대조**하고 리포트의 `## 회귀 앵커 대조` 절에 일치/드리프트를 찍는다(`BaselineDriftReporter`).
  드리프트가 나오면 창·유니버스·비용을 바꾼 실행인지 먼저 보고, 같은 조건인데 다르면
  데이터 커버리지를 의심할 것. 기준을 갱신할 일이 생기면 **그 yml만 다시 찍으면 된다**
  (`--backtest.write-baseline=true`, 손으로 편집 금지).
  ⚠ 사고 기록: 최초 앵커(07-22·07-24)는 캔들이 판정 창 끝보다 2거래일 모자란 채로 찍혀
  780건·총수익 129.36%였다 → 2026-08-17 완전한 데이터로 재기록(781건·124.28%, PF·MDD·§4 판정 불변).
  원인인 백필 7일 슬랙은 **해소**됐다(커버리지 관문 `CandleCoverageChecker` — 기준선을 쓰는
  실행은 데이터가 모자라면 채점 전에 중단). 근거 `_workspace/7_quant_baseline-drift.md`.
  ⚠⚠ **2026-07-25 약세장 스트레스에서 이 후보는 기각됐다 (BACKTEST-DESIGN §14.3)**:
  캔들을 2019-04까지 소급 확장하고 판정 창을 3년→6.5년(2020-01~2026-07, 24창)으로 넓히자
  **RR1이 PF 1.96→1.26·MDD 9.9%→33.5%로 붕괴, 5개 프로필 전원 불합격**. 파괴자는 2022 금리
  쇼크(4분기 연속 손실 합성 -30%, 같은 기간 KOSPI -24.9%보다 더 잃음, 약 21개월 침수).
  게다가 **코로나(2020-03)는 채점조차 안 됐다** — 6/3/3 워크포워드가 폭락을 학습 구간으로
  흡수(채점 최초일 2020-07-01), 즉 최악 구간을 빼고도 불합격이다. 사이징 축소는 약세장
  MDD를 못 잡고(1R 축소만 부분 효과 36→23.1%), **동시보유 축소는 역효과**(동시5→2에서
  33.5→37.9%) — §14.1의 "집중이 안전"은 레짐 착시였다. 결론: **엣지가 없는 게 아니라
  하락 추세 레짐에서 뒤집힌다**(2024~2026 상승장이 §14.1 성적을 견인). 한계는 전부 낙관
  방향(생존편향: 2020~2022 상장폐지 0건).
  ⚠ **비용 민감도(§14.2)**: 버티는 한계 왕복 0.50%, 실전 슬리피지 여유 0.045%p로 얇음.
  → **§14.1 기준선 yml은 3년 창 기록으로 그대로 두되, 실전 후보로 취급하지 말 것.**
  ⚠ **2026-07-25 지수 추세 필터로 부분 회생 (BACKTEST-DESIGN §14.4)**: §14.3의 사인(하락 추세
  휩쏘)을 정면 조준해 **"지수가 MA120 아래면 신규 진입 금지"**(신규 `IndexTrendRule` — `RiskRule`
  구현체, `RiskEngine` 무수정, 기본 OFF)를 A/B했다. 같은 6.5년 창에서 **G1(MA120): PF 1.26→1.51 ·
  MDD 33.5%→13.3% · 총수익 +62%→+122%**로 §4 통과(G0 회귀 앵커 24창 전부 정확 재현, 선견편향
  감사 검산 완료). 2022 4연속 손실(-30%)이 -4.41%로 끊겼고, **과필터 아님**(트레이드 -25%에 그치고
  2024~26 상승 수익은 오히려 +7.2%p 증가). MA200(G2)은 반등 초입을 놓쳐 MDD 16.9%로 불합격.
  ⚠ **단 "조건부·잠정"** — 남은 MDD 13.3%는 사실상 2024-08-05 급락 한 창이고(필터가 못 막는 종류,
  한도까지 여유 1.7%p), §4 민감도(±20%) 미측정, 유효 표본은 1192건보다 작다.
  ⚠ **부수 발견**: 기존 갭다운 지수 필터는 B-3 최초 실행부터 **무발동**이었다(러너가 KOSPI를
  메모리에 안 올려 항상 판단 불가) → BACKTEST-DESIGN §7의 "지수 레짐 기각" 판정 무효. paper는
  켠 적 없어 실계좌 영향 없음.
  남은 미검증: 분봉 정밀 · 민감도 ±20% · 크래시형 MDD. **실전 전환은 ADR-001(다일 보유) 재논의 +
  게이트 G2(사람) 선행**. paper 기본값·리스크 룰·지갑 칸 불변.
- 진입 교체 트랙 (2026-08-03, BACKTEST-DESIGN §15) — §13에서 3방식이 전부 불합격하자 **진입만
  고전 기법으로 교체**해 §14 검증 경로(출구 P3 + 사이징 + 약세장 창 + 지수 필터)에 그대로 태웠다:
  전략1(VB) → `DonchianBreakoutStrategy`(직전 20일 고가 돌파 + 종가>MA120),
  전략3(스캘핑) → `RsiMeanReversionStrategy`(+`RsiCalculator`, 추세 안 RSI(2)<10 과매도, **종가 진입**).
  둘 다 기본 OFF(`trading.donchian.enabled`/`trading.rsi.enabled`) — 백테스트에서만 켠다.
  ⚠ RSI(2)는 `DailyBarSimulator`에 **종가 진입 경로**(`checkMeanReversionEntry`)를 새로 요구했다 —
  기존 진입은 돌파 전용(고가 발화·이분탐색)이라 평균회귀 신호를 못 잡는다. 당일 진입분은 종가
  매수라 당일 손절·트레일 판정을 하지 않는다(선견편향 차단).
  ⚠ **판정 (§15.5)**: **돈치안 = 조건부·잠정 통과** — 약세장 6.5년·24창에서 지수 MA120 필터와 함께
  PF 1.90 · 기대값 +1.36% · MDD 14.4% · 총수익 +358%(과필터 아님: 트레이드 -26%인데 수익 증가).
  **RSI(2) = 보류** — MA200 한 곳만 턱걸이(MDD 14.2%)이고 총수익이 +63%로 자릿수가 달라 전략3
  후보 근거 없음. **돈치안도 실전 후보 아님**: 모든 불합격의 단일 원인이 MDD 한도(15%) 근접이고
  여유 0.6%p뿐 — 파라미터 ±20%는 4/5(종목 추세96에서 22.9%), 비용은 왕복 +0.09%p만 얹어도 탈락
  (§14.2 MA보다 얇음). 엣지(PF 1.5~2.0)는 전 축에서 강건. 다음: 사이징 재스윕 · 추세기간 지도 ·
  **실측 슬리피지**(로드맵 1.3 선행). 실전은 ADR-001 재논의 + 게이트 G2(사람) 선행.
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
> **모의계좌 리허설 깨끗한 1회 성공으로 Gate 2 완료 (2026-08-19)**
> (`POST /api/trading/liquidation-drill`). F-번호와 상세 근거는 `docs/TRADING-RULES-AUDIT.md` 참고.
>
> **데이터 품질 게이트 (2026-08)**: `RiskMonitor`(청산 트리거)·`StopLossMonitor`(손절/익절)는
> 잔고 API 실패로 낡은 스냅샷일 때 판정을 건너뛴다(`Account.isFresh()`) — 옛 값 헛발동 방지.
> 매수 차단 룰은 낡으면 보수적 차단이라 예외. 모든 KIS 호출은 `KisRateLimiter`가 초당 한도로 균등 배분.

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
- `KisApiClient`가 모든 KIS REST 연동(OAuth 토큰 포함)이 공유하는 공통 HTTP 클라이언트 —
  모든 호출은 `KisRateLimiter`(초당 한도 균등 배분, `market`)를 통과한다.
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
- 운영 신뢰성 강화 (2026-08, 브랜치 `backtest/regime-filter-and-validation` 커밋 e1e095d~285a1b9·cb5988d) —
  데이터·호출 신뢰성 결함 일괄 해소:
  ① **체결누락 desync 자동복구** — 취소가 "취소할 수량 없음/원주문번호 없음"으로 거부되면
     (=이미 체결) 실잔고와 대사해 포지션 정렬·주문 종결 (`FillStateUpdater.reconcileFilledFromBalance`,
     `KisOrderCancelClient.classify`)
  ② **토큰 만료 인식** — KIS가 만료를 HTTP 500(EGW00123)으로 주는 것을 401과 동일 취급해 즉시 재발급 (`KisApiClient`)
  ③ **텔레그램 장중 전용** — 거래일 09:00~15:30에만 전송, 장외는 로그만 (`TelegramNotifier` + `MarketCalendarService`)
  ④ **KIS 전역 레이트 조정자** `KisRateLimiter` — 모든 호출을 초당 한도로 균등 배분해
     EGW00201·SAFE_MODE 플래핑 근절. **한도는 계좌 단위: 모의 1건/초 · 실전 18건/초 ·
     토큰발급 1건/초** (KIS 공지 "API 호출 유량 안내" 2026.04.20 기준, 2026-08-04 확인 —
     예전 값 2/20에서 하향됐다). `kis.rate-limit-per-sec`, paper yml에 1로 명시
  ⑤ **주기적 안전 재동기화** — `ShadowPortfolioReconciler.reconcile`이 2주기 지속+신선값+장중일 때만
     브로커 기준 자동 보정, 첫 감지는 알림만 (§5.3 개정)
  ⑥ **데이터 품질 게이트** — `RiskMonitor`·`StopLossMonitor`는 낡은(폴백) 스냅샷이면 청산/손절 판정
     스킵 (`Account.isFresh()`, 2026-07-30 -11.19% 헛청산 오판 재발 방지)

## 미구현 / 알려진 결함 (제안·수정 시 주의)

1. ~~모의계좌 강제청산 리허설~~ ✅ **해소 (2026-08-19) — Gate 2 완료.** 12:26:49 개시 →
   미체결 취소 → 012330 1주 시장가 매도 접수(ordNo 0000026981) → 12:27:20 EMERGENCY_STOPPED 종결.
   체결은 12:37:31 실잔고 대사로 확인(브로커 보유 0주). 2026-07-30 실패 원인이던 레이트리밋 폭주는
   레이트 조정자(2026-08) 배포로 재현되지 않았다. (항목 번호는 아래 참조 유지를 위해 그대로 둔다)
2. 지정가 분할(Price Jitter) 미구현 — ADR-001 3장 파라미터(가격 간격·주문 개수) 결정 선행
3. 타임컷은 15:15에 앱이 꺼져 있으면 해당일 건너뜀 (거래일 캘린더는 `market-calendar.yml`로 해소됨)
4. 연속손실 기록은 매도 체결 청크 단위 — 부분 체결 매도 시 라운드트립 집계로 전환 필요
   (R 사이징으로 수량 > 1 매도가 가능해져 발생 확률 상승)
5. **체결누락 — 진단 종결, 모의 환경 한정 결함으로 확정 (2026-08-10)** — 모의 체결조회
   (VTTC8001R)는 주문번호 필터를 빼고 하루 단위로 조회해도 **실제 체결에 빈 목록**을 준다
   (rt_cd=0인데 count=0. 8/4~8/7 우리 DB 체결 52건 대비 브로커 조회 0건 — BACKTEST-DESIGN §16.6).
   파라미터 문제가 아니므로 **조회 교정으로는 못 고친다.**
   ⚠ 귀결: **모의에서는 매도 체결가를 얻을 수 없다** → 실현손익(`trade_result`)·칸별 성적·
   왕복 슬리피지 측정이 전부 막힌다. 과거분 복구도 불가(브로커 원장 조회가 비어 있음).
   현재는 ①(취소불가=체결) 자동복구 + 주기적 재동기화가 **유일한 체결 확인 수단**(≈10분 지연).
   남은 선택지는 실시간 체결통보(WebSocket `H0STCNI9`) 또는 실전 전환 후 재확인 —
   후자를 택했다(ADR-001 개정 2026-08-10: 슬리피지 관문을 실전 이후로 이관).

해소됨: F-1/F-2/F-7 (Gate 1, 2026-07-07) · F-3 (Gate 2) · F-4/F-5 (Gate 3) · F-8 (P2-A, 2026-07-08)
· 부분 체결 매수 손절 미장착 (2026-07-20 — `OrderPartialFilledEvent` 신설, 부분 체결분도
  `StopLossArmer`가 장착. 릴리즈 검증용 `RunStreakRecorder`(연속 무중단 가동일 기록,
  `GET /api/trading/run-streak`)도 같은 날 추가)
· 체결누락 브로커-DB desync 복구·레이트리밋 플래핑·낡은데이터 헛청산 (2026-08 — 위 "구현 완료된 부분"
  운영 신뢰성 강화 참고. 단 체결누락 뿌리는 위 5번으로 잔존)
· **보정 포지션 손절선 누락** (2026-08-04 — `ShadowPortfolioReconciler.armMissingStops()`:
  브로커 기준 보정은 체결 이벤트가 없어 `StopLossArmer` 경로를 안 타 `stopPrice=null`로 남았다
  (실측: 034020 13주 무방비, 그날 신규 생성 3회). 이제 대조할 때마다 "보유분은 반드시 손절선을
  갖는다"를 불변식으로 강제한다 — 기준가는 브로커 평균단가, 기존 손절선은 건드리지 않는다)

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
