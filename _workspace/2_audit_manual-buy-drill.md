# 2_audit — 수동 매수 리허설(005930 10주) 엔드포인트 감사

감사일: 2026-08-12 / 브랜치: backtest/regime-filter-and-validation / 감사자: risk-auditor
감사 범위: `OrderEngine.java`, `TradingController.java`, `OrderEngineTest.java`, `TradingControllerTest.java`
(같은 워킹트리의 `ShadowPortfolio`/`PortfolioState`/`DailyEquityRepository` 변경은 peak-equity 작업분 — 이번 범위 밖)

## 한 줄 결론

**CRITICAL 0 · HIGH 0.** 흐름 규칙(Strategy→Signal→RiskEngine→OrderEngine)·리스크 룰 8종·청산
상태머신·비밀키 모두 위반 없음. 다만 **무인 실행 전제에서 MEDIUM 5건**(거짓 성공 응답 가능,
주문 예외 시 500, 개장 전/휴장일 가드 없음, 칸 현금 캡 우회, 스프링 배선 미검증)이 남는다.

## 감사표

| 심각도 | 항목 | 위치 | 내용 | 근거 |
|---|---|---|---|---|
| PASS | 흐름 우회 없음 | TradingController.java:198-208 | `Signal.buy` → `riskEngine.check` → **불합격 시 early return** → 합격 시에만 `executeManualBuy` 1회. 메서드 내 `orderEngine` 호출은 208줄 단 1곳(파일 전체 grep 확인) | CLAUDE.md 규칙 1·2 |
| PASS | RiskEngine/RiskRule 무수정 | `git status src/main/java/com/trading/risk/` = 변경 없음 | 8종 룰 전부 그대로 통과시킨다. 특례·우회 분기 없음 | CLAUDE.md 규칙 3 |
| PASS | 모드 게이팅 회귀 없음 | OrderEngine.java:78-90 | `isModeAllowed()` 추출은 diff상 **줄 단위 동일 이식**(조건·순서·로그 문구 동일). `executeManualBuy`도 같은 게이트를 탄다(70줄) | git diff 대조 |
| PASS | 수량 하드코딩 | TradingController.java:47-48, 208 | `MANUAL_BUY_QUANTITY=10` 상수. 바디는 `Map<String,String>`이고 `confirm`만 읽는다 — quantity/종목 입력 경로 없음 | 요구사항 |
| PASS | 자격증명 가드 | TradingController.java:189-191 | `kisProperties.isConfigured()` — liquidation-drill(163줄)과 동일 수준 | — |
| PASS | 비밀키 | 신규 코드 전체 | 하드코딩 없음. 테스트의 `test-key/test-secret`은 기존 픽스처 | 코딩 컨벤션 |
| PASS | 청산 상태머신 | LiquidationService 무변경 | 새 플래그·별도 상태 없음 | ADR-001 |
| PASS | real 격리(1차) | TradingController.java:213-215 | `getActiveProfiles()`에 paper 없으면 차단. **프로필 미설정 시 빈 배열 → 차단(fail-closed)**. `contains`이지만 원소 **정확 매치**라 "paperX" 류 우회 불가 | 테스트 307-317줄 |
| MEDIUM | 프로필 화이트리스트가 real 동시활성을 안 막음 | TradingController.java:214 | `paper,real` 동시 지정 시 통과한다(paper 포함만 검사). 실질 방어는 컨트롤러가 `ShadowPortfolioReconciler`(@Profile("paper"))에 의존해 real 단독에서는 빈 자체가 안 뜨는 것 — 그건 유효하나, 조건이 "paper 포함"이 아니라 "real 미포함"이어야 규칙에 충실 | 실전 전환은 사람 게이트 G2 선행 원칙 |
| MEDIUM | 성공 응답이 실제 접수를 보증하지 않음 | TradingController.java:208-210 vs OrderEngine.java:61-75 | `executeManualBuy`는 **void**. 컨트롤러의 RUNNING 검사(192줄)와 OrderEngine 모드 검사(70줄) 사이에 SAFE_MODE로 바뀌면(RiskMonitor·KisApiClient가 비동기로 전환) OrderEngine이 조용히 삼키는데 응답은 `success=true "접수했습니다"`가 나간다. 무인 실행 로그만 보면 포지션이 생긴 줄 안다 | 무인 시나리오 |
| MEDIUM | 주문 접수 실패가 구조화 응답이 아니라 500 | KisOrderClientImpl.java:103-117 → OrderEngine.java:74(try 없음) → Controller 208 | KIS 거부·타임아웃 시 RuntimeException이 컨트롤러 밖으로 전파 → HTTP 500(다른 실패는 전부 `success=false` JSON). 특히 **응답 유실(ambiguous)** 케이스는 실제로 접수됐을 수 있는데 호출자에겐 500만 보인다. 재시도 폭주는 `OrderFailureCooldownRule`이 막는다(부분 완화) | 무인 시나리오 |
| MEDIUM | 개장 전·휴장일 가드 없음 | TradingController.java:178-211 / MarketCloseRule.java:29-39 | 매수 경로의 시간 룰은 **마감 10분 전 이후 차단**뿐이고 `isTradingDay`·개장(09:00) 검사는 없다. 08:xx 예약 호출 시 시장가 주문이 그대로 브로커로 나간다(동시호가로 체결될 수도, 거부되어 쿨다운을 남길 수도 있다 — 어느 쪽인지 코드로는 확정 불가, **판단 보류**) | 무인 실행 시각 보장이 사람 몫 |
| MEDIUM | 칸(bucket) 현금 캡 우회 | OrderEngine.java:74 vs OrderSizingService.java:100-115 | `Signal.buy(code,reason)`은 **bucket=VB**(Signal.java:22-24). `BucketBudgetRule`은 "가용현금>0"만 보고(BucketBudgetRule.java:41-45), 실제 **수량 축소는 사이징에 있는데 그 경로를 건너뛴다** → VB 칸 잔액이 10주 대금보다 적어도 주문이 나간다. paper 한정·10주(≈70만원)라 실손은 없지만 "사이징만 우회"라는 설명보다 우회 범위가 넓다. 같은 이유로 `PositionLimitRule`은 매수 **전** 비중만 보므로 고정 10주의 사후 비중 초과를 막지 못한다(기존 룰 특성) | CLAUDE.md 지갑 칸 |
| MEDIUM | 스프링 배선 런타임 미검증 | TradingController.java:61-81 | 생성자 의존성 4개 추가. 정적으로는 순환 참조 없음(RiskEngine·OrderEngine·PositionManager 어느 것도 컨트롤러를 참조하지 않음)이고 paper에 전부 빈 존재(KisPositionManager @Profile("paper")). 단 저장소에 `@SpringBootTest`가 없어 **기동은 내일 paper 부팅이 최초 검증** | 무인 실행 전 기동 확인 필요 |
| LOW | 실적 데이터 오염 | Signal.java:22-24 → OrderHistory→Position | 리허설 매수가 VB(방식1) 칸으로 기록된다. 이후 청산 시 실현손익이 VB 성적·`ConsecutiveLossRule` 스트릭에 섞인다(모의 체결가 미조회 결함으로 기록 자체가 안 될 수도 있음) | CLAUDE.md 알려진 결함 5 |
| LOW | 공개 API 표면 확장 | OrderEngine.java:61 | `executeManualBuy`가 public이라 향후 Strategy가 호출하면 사이징 규율이 무너진다. 현재 호출부는 컨트롤러 1곳뿐(grep 확인), Javadoc 경고 있음 | 규칙 1 예방 |
| LOW | 무인 실행 알림 부재 | TradingController.java:206-210 | 리허설 개시 시 텔레그램 통지 없음(로그만). 체결 알림은 기존 경로로 나간다 | — |

## 검증 증거 (이번 감사에서 직접 실행)

```
명령: gradle test --tests com.trading.order.OrderEngineTest --tests com.trading.control.TradingControllerTest
결과: BUILD SUCCESSFUL
  OrderEngineTest        tests=10 failures=0 errors=0 skipped=0
  TradingControllerTest  tests=19 failures=0 errors=0 skipped=0
```
(전체 515건은 구현자 보고 — 본 감사에서 재실행하지 않음)

## 판정

→ **CRITICAL 0건 · HIGH 0건 — 배포 차단 사유 없음.**
무인 실행 전 사람이 보장할 것: ① 앱 기동 성공 + 모드 RUNNING 확인 ② 호출 시각을 **09:00 이후 장중**으로
③ 응답 `success=true`를 신뢰하지 말고 포지션/주문원장으로 확인(MEDIUM 2건 사유).
실전(real) 전환은 이 기능과 무관하게 사람 게이트 G2 선행.
