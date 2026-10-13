# 45_impl — 칸(슬리브)별 낙폭 상한 (ADR-001 §2.2 개정 2026-08-07)

> 2026-10-10(토) KST · trading-implementer · 사용자 승인 설계(리더 지시) 구현 · **커밋 안 함** · 운영 반영은 앱 재기동 필요
> 빌드 폴더 `C:/Users/SAMSUNG/auto_trading-build-impl45` (운영 폴더 미접촉)

## 0. 릴리즈 갭 체크 (요약)

- 이 작업은 CLAUDE.md 규칙 6의 "⚠ 개정 중 아직 코드에 없는 것: 슬리브별 낙폭 상한(A동 −12% / B동 −20%) — 실전 전 필수
  (`_workspace/30_audit` M-4)"를 메운다. 릴리즈 체크리스트 8항목에는 직접 해당하지 않는 **실전 승격(G2) 선행 조건**이다.
  체크리스트 표는 바꾸지 않았다.

## 1. 한 줄 요약

1분마다(장중·신선한 잔고·활성 칸만) 칸 자산 = 배분금 + 실현손익 + 보유 평가손익을 계산해, 칸 최고 기록 대비 낙폭이
A동 12% / B동 20%에 **2회 연속** 닿으면 **그 칸만** 잠근다 → 텔레그램 → 그 칸 보유를 평시 매도 경로
(Signal → RiskEngine → OrderEngine)로 판다. 잠긴 칸의 매수는 새 룰 `SleeveLockRule`이 막고, 해제는 사람만(REST, 사유 필수).

## 2. 바뀐 파일

| 파일 | 구분 | 역할 |
|---|---|---|
| `bucket/SleeveDrawdownProperties.java` | 신규 | 한도 설정(기본 0.12/0.20 = ADR 값). 0 이하·1 이상·NaN이면 기동 실패 |
| `bucket/SleeveRealized.java` | 신규 | 실현손익 묶음(금액 + 기록·추정·측정 불가 건수 + 마지막 측정 불가 날짜) |
| `bucket/SleeveRealizedSource.java` | 신규 | 작은 인터페이스 — bucket이 dashboard를 직접 의존하지 않게 하는 경계 |
| `bucket/SleeveStateStore.java` | 신규 | portfolio_state 영속화(최고 기록·잠금·굳힌 실현손익), 여러 줄 쓰기는 `saveAll` 한 번 |
| `bucket/SleeveRealizedLedger.java` | 신규 | 굳힌 몫 + 최근 몫. 30일 지난 거래를 분봉이 살아 있을 때 굳힌다 |
| `bucket/SleeveEquity.java` | 신규 | 칸 자산 결과 record |
| `bucket/SleeveEquityCalculator.java` | 신규 | 칸 자산 계산 또는 판정 보류 사유 |
| `dashboard/SleeveRealizedPnlAdapter.java` | 신규 | 위 인터페이스 구현 — TradeResult 우선, 없으면 `SellPriceEstimator`·`TradePairer`로 추정, 짝짓기 캐시 |
| `dashboard/TradePairer.java` | 수정(+9/−5) | `Unmeasurable`에 `bucket`(짝지은 매수의 칸, 짝 없음 null) 추가 — 칸별 측정 불가 개수용 |
| `risk/SleeveDrawdownGuard.java` | 신규 | 판정 핵심 — 최고 기록·2회 연속 확인·잠금·해제(전부 synchronized) |
| `risk/SleeveDrawdownMessages.java` | 신규 | 텔레그램 문구(쉬운 말) |
| `risk/SleeveDrawdownMonitor.java` | 신규 | `@Profile("paper")` 1분 감시(이름 없는 `@Scheduled` = 기본 1스레드) |
| `risk/SleeveLiquidator.java` | 신규 | 잠긴 칸 보유 정리 — 평시 매도 경로 + 이중 매도 방지 |
| `risk/SleeveLockRule.java` | 신규 | `RiskRule` `@Component @Profile("!backtest")` — 잠긴 칸 매수만 거부 |
| `control/SleeveController.java` | 신규 | `GET /api/buckets/sleeve-status`, `POST /api/buckets/{bucket}/unlock` |
| `risk/RiskRuleNameResolver.java` | 수정(+1) | 사유 조각 "칸 손실 상한" → SleeveLockRule |
| `static/js/diag-history.js` | 수정(+1) | 진단 화면 라벨 |
| `application-paper.yml` | 수정(+7) | `trading.bucket.sleeve-drawdown.a-sleeve-limit: 0.12` / `b-sleeve-limit: 0.20` |
| 테스트 10개 클래스 + `InMemoryPortfolioState`(맵 기반 리포지토리 목) | 신규 | 아래 7절 |
| `PaperSafetyGuardsConfigTest`, `RiskRuleNameResolverTest` | 수정(+1 테스트, +1 행) | yml 고정 · 사유 조각 고정 |

무수정 확인: `RiskEngine`, `LiquidationService`, `PositionManager`·`KisOrderClient`·`MarketDataService` 시그니처,
지시받은 금지 파일 10개, `CLAUDE.md`, `BucketAccountService`(사이징용 칸 자산).

## 3. 동작 흐름 (`SleeveDrawdownMonitor.java:73-118`)

1. 칸 나누기 OFF·KIS 미설정 → 아무것도 안 함. 장 밖·운전 모드가 RUNNING/SAFE_MODE 아님·잔고 낡음/실패 → 건너뛰고 **연속 확인 전부 초기화**(`:75-81`).
2. 칸마다: **잠긴 칸**은 남은 보유를 다시 판다(`:96-98`, 활성 여부와 무관). 아니면 배분금 > 0인 활성 칸만(`:100`).
3. 굳힐 몫이 있으면 굳힘(`:102`) → 칸 자산 계산 → 보류면 그 칸 연속 확인 초기화 + 사유 로그(바뀔 때만)(`:103-108`).
4. `guard.evaluate` → `LOCKED_NOW`면 그 칸 보유 매도(`:115-117`).

## 4. 설계 결정과 근거

| # | 결정 | 근거 (file:line) |
|---|---|---|
| D1 | 칸 자산 = 배분금 + 실현 + 보유 평가 | `SleeveEquity.java` · `SleeveEquityCalculator.java:66-90` |
| D2 | 실현손익: TradeResult(매도가>0) 우선, 그 매도는 추정에서 제외(이중 계산 금지), 없으면 분봉 추정, 못 하면 0원+개수 | `SleeveRealizedPnlAdapter.java:82-116,123,157` · TradeResult는 체결가>0일 때만 저장됨 `FillStateUpdater.java:324-329` |
| D2a | "어느 매도의 기록인가" = 그 매도 주문 접수 시각 ~ 체결 확인+1분 사이에 같은 종목 기록이 있나 | 정상 경로는 체결 반영과 같은 트랜잭션에서 기록 → 시각이 겹친다. 대사 경로 매도(체결가 미상)는 기록이 없어 추정으로 간다 |
| D2b | 매도가 0원 TradeResult는 쓰지 않음 | 2026-07-30 헛청산 5건(−5,014,000원, 실제 아님). 그대로 쓰면 VB 칸이 −50% → 즉시 헛잠금 + 이름표 없는 보유 매도 |
| D3 | **(설계 추가) 30일 지난 거래는 굳힌다** | 분봉은 60일 뒤 삭제(`MinuteCandleRetention.java:38`). 안 굳히면 이긴 거래가 0으로 바뀌는 날 가짜 낙폭(헛발동). `SleeveRealizedLedger.java:31,54-66` |
| D4 | **(설계 확장) 오늘 판 거래가 측정 불가면 그 칸은 오늘 판정 보류** | 분봉은 장 마감 뒤(15:40) 쌓여 장중 매도는 늘 측정 불가. 0으로 넣으면 이긴 청산=가짜 낙폭, 진 청산=손실 은폐. `SleeveEquityCalculator.java:69-71`. 지난날의 측정 불가는 영영 못 구하므로 승인 설계대로 0원+개수 |
| D5 | 최고 기록: 처음 = 배분금, 잠기지 않은 동안만, **연속 2회 확인된 값(둘 중 낮은 값)으로만** 상승 | (설계 보강) 한 번 튄 값이 영구 오염하는 결함 6의 교훈. `SleeveDrawdownGuard.java:126-136`, 잠긴 동안 판정 안 함 `:67-68` |
| D6 | **(설계 추가) 배분금이 바뀌면 최고 기록도 같은 금액만큼 이동** | 배분금을 400만→300만으로 줄이면 낙폭 24%로 헛잠금+강제 매도가 된다. `:145-158` |
| D7 | 발동: 낙폭 ≥ 한도(ADR "도달 시")가 **2회 연속**. 보류·장외·오류 회차는 연속을 끊는다 | `:74-86` · `SleeveDrawdownMonitor.java:75-81,89,105`. 하루 건너 두 번은 연속이 아니다(헛발동 방지 우선) |
| D8 | 잠금 저장 → 텔레그램 → 매도 순서. 저장 실패면 매도도 안 함(다음 2회 연속에서 재시도) | `:167` — 잠금 없이 팔면 룰이 재매수를 못 막는다. 알림 실패는 삼킴 |
| D9 | `SleeveLockRule`: 매수만 거부, 매도·다른 칸 통과, 상태 조회 실패 시 매수 보류(fail-closed), 칸 OFF면 통과 | `SleeveLockRule.java:43-56` |
| D10 | 해제: 사유 필수(공백·200자 초과 거부, 줄바꿈→공백), 잠김 확인, **칸 자산을 정확히 계산할 수 있을 때만**, 최고 기록 = 지금 칸 자산 | `SleeveController.java:85-106,169-173` · `SleeveDrawdownGuard.java:110-119` |
| D11 | 백테스트 무영향 | 잠금 룰·상태 저장소 `@Profile("!backtest")`, 나머지 `@Profile("paper")`, 감시기는 칸 OFF면 아무것도 안 함. 백테스트 RiskEngine 룰 목록 불변 |

리더 확인이 필요한 가정(코드는 아래대로 했다):
1. **칸 시작일 = `experiment-start`(2026-07-20) 공통.** A동은 10-01 전 거래가 없어 사실상 같다.
2. **최고 기록 시작값 = 배분금**("첫 계산값" 아님) — 개시 뒤 배포 전까지 이미 진 손실도 낙폭에 들어간다(ADR "자체 낙폭" 취지).
3. **경계값은 `>=`**(ADR "도달"). 지시문 "초과"와 다르지만 실수 비교라 실질 차이는 없다.
4. 위 D3·D4·D5(연속 확인)·D6은 승인 설계에 없던 보강이다 — 모두 헛발동·조용한 통과를 막는 방향.

## 5. 자산 계산 방식과 한계

- 실현손익 = 굳힌 몫(portfolio_state) + [굳힌 날, 내일) 몫(TradeResult + 추정). 보유 평가 = Σ DB 수량 × (신선한 잔고 현재가 − DB 평단).
- 보류 조건(그 회차 판정 안 함): 보유가 있는데 잔고 낡음/없음 · DB 보유가 증권사 잔고에 없음 · 수량 불일치 · 현재가/평단 없음 ·
  오늘 판 거래 측정 불가 · 계산값 비숫자.
- 한계(전부 상태 조회 `GET /api/buckets/sleeve-status`의 `estimated`·`warning`·`unmeasurableTrades`·`deferredReason`으로 드러남):
  1. 추정치다 — 매도 시각 최근접 분봉 종가, 수수료·세금 미반영 총손익. 운영 DB에는 일봉이 없어 분봉이 없으면 측정 불가.
  2. **청산이 있던 날은 그 시점부터 장 마감까지 그 칸 판정이 쉰다**(D4). 다음 개장 09:00·09:01에 두 번 확인되면 잠긴다 — 최대 하루 지연.
     그동안 종목별 손절·계좌 일일손실·누적 8% 가드는 그대로 돈다.
  3. 이름표 없는 보유(브로커 대조로 생긴 행)는 VB로 센다 — A동 보유가 이름표를 잃으면 A동 노출이 덜 잡히고 VB에 잡힌다(시스템 관례).
  4. 증권사에만 있고 DB에 없는 보유, 주문 기록 없이 사라진 보유(주문 저장 실패·HTS 수동 매매)는 반영 못 한다.
  5. 굳힌 기간에 뒤늦게 체결 기록이 들어온 매도(30일 넘게 미체결로 남았던 경우)는 빠진다 — 현실적으로 드묾.
  6. 대사 경로 매도가 보유를 다 비우지 않은 경우 주문 체결가에 매수 평단이 박히지만, 기록 판정은 TradeResult 존재로 하므로 추정으로 간다.
  7. FIFO(추정) vs 평단(TradeResult) 기준 차이는 라운드트립이 끝나면 같아진다.
- 배포 직후 헛발동 점검(읽기 전용 GET 1회, 쓰기 0): `/api/performance/by-bucket?days=120` → VB 추정 실현 **+11,900원(13건)**,
  A동은 끝난 거래 없음. 계산상 VB ≈ 1,001만(배분 1,000만), A동 = 400만 → 낙폭 ≈ 0. EVENT·MIX는 꺼져 있어 판정 대상 아님.

## 6. 이중 매도 방지 근거

잠긴 칸은 매 회차(1분) 다시 들르지만 `SleeveLiquidator.sellOne`이 아래에서 멈춘다:
1. `SleeveLiquidator.java:76` — 같은 종목에 미체결 매도(ACCEPTED·PARTIAL_FILLED)가 있으면 안 낸다(타임컷과 같은 기준 `TimeCutScheduler.java:261`).
2. `SleeveLiquidator.java:80` — **신선한** 증권사 잔고에 그 종목이 0이면 안 낸다(체결됐는데 DB가 대사 전인 약 10분, 결함 5).
3. 매도는 `:85-91` Signal.sell → RiskEngine.check → OrderEngine.execute만 탄다(수량 = DB 보유 전량). LiquidationService 미사용.
- 역확인(아래 7절): 1번을 끄면 `resells_remaining_holding_only_without_pending_sell`, 2번을 끄면 `does_not_resell_when_broker_is_already_flat`이 실패했다.
- 남는 틈: CANCEL_REQUESTED 상태는 미체결로 세지 않는다(타임컷과 동일). 그 사이 재주문이 나가도 2번 검사와 증권사 주문가능수량 검사가 받친다.

## 7. Red-Green 증거

```
[검증 증거 — Red]  (같은 시그니처의 껍데기 클래스로 실행)
명령: gradle test --console=plain --tests *SleeveDrawdownPropertiesTest ... *RiskRuleNameResolverTest (12개 클래스)
      TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-impl45
종료 코드: 1
결과: 95개 중 37개 통과, 실패 58 (통과 37 = "아무것도 안 함"·"매도 통과" 같은 부정 케이스 + 기존 룰 표 행)

[검증 증거 — Green, 대상]
명령: 위 12개 + TradeStatsServiceTest(TradePairer 변경 회귀)
종료 코드: 0
결과: 111개 중 111개 통과, 실패 0

[역확인 1 — 일부러 망가뜨림: 미체결 매도 검사 끔 · 0원 TradeResult 허용 · 최고 기록 연속 확인 끔]
종료 코드: 1 · 31개 중 실패 4 — 정확히 예상한 4건(재매도 방지 1 · 0원 기록 1 · 최고 기록 2) → 원복
[역확인 2 — 증권사 보유 0 검사 끔]
종료 코드: 1 · 10개 중 실패 1 — does_not_resell_when_broker_is_already_flat → 원복 (MUTATION 표식 잔존 0건 grep 확인)

[검증 증거 — 최종 전체]  (결과 폴더 삭제 후 실행, 모든 수정 이후)
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-impl45 LOGGING_FILE_NAME=.../test.log gradle test --console=plain
종료 코드: 0 (BUILD SUCCESSFUL in 29s)
결과: 결과 XML 164개 합산 1006개 중 1006개 통과, 실패 0 · 오류 0 · 건너뜀 0 (기존 932 + 신규 74)
```

신규 74 = SleeveDrawdownProperties 3 · SleeveStateStore 6 · SleeveRealizedLedger 6 · SleeveEquityCalculator 9 ·
SleeveRealizedPnlAdapter 7 · SleeveDrawdownGuard 14 · SleeveLockRule 6 · SleeveDrawdownMonitor 10 · SleeveDrawdownWiring 3 ·
SleeveController 8 · PaperSafetyGuardsConfig +1 · RiskRuleNameResolver +1.

## 8. 남은 위험·미확인

- **운영 반영 = 재기동 필요**(코드·yml·정적 리소스). 실제 장중 동작·텔레그램 수신·스케줄 스레드 소요 시간은 미확인.
  짝짓기는 캐시(체결 주문·기록·날짜가 같고 10분 이내면 재계산 안 함)라 평소엔 가볍지만, 새 체결 직후 1회는 분봉을 다시 읽는다.
- **risk-auditor 감사 요청을 직접 보내지 못했다** — 이 세션에 SendMessage 도구가 없다. 리더가 라우팅해야 한다.
- CLAUDE.md 규칙 6의 "⚠ 슬리브별 낙폭 상한 — 코드에 없음" 문구 갱신, `docs/OPERATIONS.md` 해제 절차 기재는 리더 몫(이번 범위·권한 밖).
- 실전(real) 프로필에는 감시기가 없다(paper 전용, 지시대로). 잠금 룰·저장소만 생긴다 — 실전 전환 감사 때 다시 볼 것.
- 계좌 누적 8% 가드는 A동 약 −11.9%에서 먼저 걸릴 수 있다(37_audit §6) — 이 상한은 안쪽 방어선이고, B동을 다시 켤 때 의미가 커진다.
- 범위 밖 발견(손대지 않음): `BucketAccountService`(사이징용 칸 자산)는 여전히 실현손익만 쓰고 매도가 0원 기록도 더한다
  (`BucketAccountService.java:43,52`) — VB를 다시 켜면 사이징 칸 자산이 약 −500만 왜곡된다.
- `ScheduledPlacementTest`의 MUST_STAY_DEFAULT 목록에 `SleeveDrawdownMonitor#monitor`를 넣지 않았다(다른 작업 파일). 같은 클래스의
  "나머지는 전부 기본 스레드" 테스트가 이미 이 메서드를 검사해 통과했다.
- 작업 중 실수: 결과 합산에 heredoc을 한 번 써서 멈췄다(지시받은 금지 사항). 내가 띄운 python(PID 29200, 15:02)·ps·grep(15:05)만
  종료했다. **12:29에 생긴 ps.exe(8736)·grep.exe(30448·12504)는 다른 세션 것이라 그대로 두었다** — 멈춘 채 남아 있을 수 있다.

---

## 9. 감사 후속 (46_audit M-2 · M-5 · M-3) — 2026-10-11

> 리더 지시: M-2(필수)·M-5·M-3만 고친다. M-1(청산일 보류)은 사용자 결정으로 지금 동작 유지, M-4·M-6·LOW는 손대지 않음.
> 위 2~8절에 적힌 `SleeveRealizedPnlAdapter`·`TradePairer`·`SleeveDrawdownMonitor` 줄 번호는 이 절 기준으로 바뀌었다.
> 운영 앱·운영 DB·API는 이번에 한 번도 건드리지 않았다(조회도 0회). 커밋 안 함.

### 9.1 M-2 감시 스레드 부하 — 어댑터 재설계 (`SleeveRealizedPnlAdapter.java`)

| 감사 권고 | 한 일 | 근거 |
|---|---|---|
| ① 장중 시간 만료 없애기 | 10분 만료를 **완전히 없앴다**. 짝짓기는 지문(체결 주문·기록·날짜)이 바뀔 때만 다시 한다 | `:159-174` |
| ② 요청 기간 안 매도만 추정 | 분봉은 **요청 기간·그 칸·기록 없는 매도**의 조각만 읽는다(`:113-117`). 짝짓기 소진은 전 기간 그대로(`TradePairer.match`). 구한 추정은 **그날 동안 기억**하고(날짜가 바뀌면 비움), 오늘 판 매도의 "측정 불가"만은 기억하지 않는다(15:40 분봉이 쌓이면 바로 잡히게) | `:142-155` |
| ③ 회차당 지문 1회 | 같은 회차(5초 안)의 두 번째 호출부터는 직전 조회를 다시 쓴다 — 칸 2개 + 굳히기가 한 번의 DB 조회를 나눠 쓴다 | `:56,161` |
| ④ 300ms 넘는 회차 기록 | 감시기 `monitor()`가 소요 시간을 재서 300ms를 넘으면 INFO `[칸 낙폭] 회차 소요 Nms` | `SleeveDrawdownMonitor.java:43,75-81` |
| 체결 주문 전체 훑기 | `findByFilledQuantityGreaterThan`(인덱스 없음, 26,847행 전체) 대신 **기존 상태 인덱스**(`idx_order_history_status`)를 타는 `findByStatusIn(체결 가능 6개 상태)` + 체결 수량 > 0 거르기. **스키마 변경 없음** | `:65-66,178-183` |

- 같은 결과인 근거: 체결 수량이 생기는 전이는 PARTIAL_FILLED·FILLED·CANCEL_REQUESTED(취소 중 체결)뿐이고 CANCELLED·CANCEL_FAILED로 남는다.
  ACCEPTED·FAILED는 체결 수량이 늘 0이다(`OrderHistory` 상태 전이 메서드). 실패 매도 24,217행은 인덱스에서 걸러진다.
- 이를 위해 `TradePairer`를 **짝짓기(`match`, DB 안 읽음)** 와 **가격 추정(`price`)** 으로 나눴다. 대시보드 `pair()`는 둘을 이어 부를 뿐이다.
- 예상 효과(측정 아님): 평시 회차의 분봉 조회 0회(이미 구한 추정은 기억), order_history는 회차당 1회·인덱스 조회. 새 체결이 생기면
  짝짓기는 메모리에서 다시 하되 분봉은 새 매도 것만 읽는다. 하루의 첫 회차와 배포 첫날 굳히기(다음 거래일 10-12 월 기준
  07-20~09-11 매도, 시스템 도구로 계산)에서만 분봉을 몰아서 읽는다.
- 남은 것: 대시보드 `TradeStatsService`는 여전히 `findByFilledQuantityGreaterThan`을 쓴다(웹 요청 스레드·30초 캐시 — 감시 스레드와 무관해 그대로 둠).

### 9.2 M-5 주인 없는 옛 매수 조각 — 칸 우선 짝짓기 (`TradePairer.java`)

- 규칙: 매도는 **그 매도 직전에 마지막으로 체결된 그 종목 매수의 칸**(주인 칸) 조각부터 선입선출로 쓰고(`:108,111`), 모자라면 다른 칸
  조각을 쓰되 **칸 넘김(crossBucket) 표시**를 남긴다(`:123-143`, 판정 `:133`). 체결 수량 0인 주문은 짝에도 주인 칸 판단에도 안 낀다(`:102`).
  주문 경로(`KisOrderClientImpl`·매도 Signal)는 건드리지 않았다.
- 전제 근거(코드 확인): `PendingOrderRule.validate` — 매수 신호에 대해 ① Position 수량 > 0이면 "이미 보유 중" 거부 ② 같은 종목 ACCEPTED 매수가
  있으면 거부. 그래서 같은 종목의 다음 매수는 앞 보유가 0이 된 뒤에만 나간다 = 한 종목은 한 번에 한 칸.
- 표시: `EstimatedTrade.crossBucket`·`TradePairer.Unmeasurable.crossBucket` 필드, 대시보드 응답 머리말 `crossBucketPieces`(건수,
  `TradeStatsService.java:153,158`), 짝짓기 규칙 문구(`:55-58`) 갱신. 칸 장부는 다시 짝지을 때 WARN 로그(`SleeveRealizedPnlAdapter.java:200`).
  칸 넘김 조각의 칸은 예전처럼 **매수 쪽 칸**을 쓴다 — A동 상한에 엉뚱한 값을 넣지 않으려는 선택(B동 칸은 한도 여유가 크다).
- **대시보드 결과가 달라지는 경우**: 같은 종목에 주문 없이 사라진 옛 조각이 있을 때만이다(실측 066570 같은 경우) — 이제 뒤의 매도가 그 옛 조각
  대신 자기 칸 조각과 짝지어진다(더 정확한 방향). 기존 `TradeStatsServiceTest` 16건은 한 줄도 안 바꾸고 통과했다(평범한 경우는 결과 동일).
  새 테스트는 그 파일이 이미 371줄이라 `TradeStatsCrossBucketTest`로 따로 뒀다.
- **이 규칙이 틀리는 경우** (보고 — 고치지 않음):
  1. **같은 칸 안의 옛 조각**: 주인 칸 안에서는 여전히 오래된 조각부터 쓴다. 그 칸이 같은 종목을 다시 사고팔면 실현손익이 "최근 매수가 − 옛 매수가"
     × 조각 수량만큼 어긋난다. 라운드트립이 거듭돼도 차이가 서로 상쇄돼 **한 조각 크기를 넘지 않는다**(Σ(매도−앞 매수) − Σ(매도−제 매수) = 마지막 매수 − 옛 매수).
     더 고치려면 "새 매수가 체결되는 순간 그 종목의 남은 조각은 주인 없는 것으로 버린다"(PendingOrderRule상 매수 시점 보유는 0이므로)가 가능하나,
     아래 2번 같은 겹침에서 진짜 조각을 버리는 위험이 있어 이번엔 하지 않았다.
  2. **취소 중(CANCEL_REQUESTED) 매수가 실제로는 체결된 경우**: PendingOrderRule ②는 ACCEPTED만 보므로, 그 틈에 다른 칸의 매수가 통과해 두 칸이 같은
     종목을 함께 들 수 있다 → 매도는 나중 칸 조각부터 쓰고 앞 칸 조각은 칸 넘김으로 표시된다(수량은 맞고 표시가 남는다).
  3. **주문 기록 없이 생긴 보유**(브로커 대조로 생성·HTS 수동 매매): 주인 칸은 그 종목의 마지막 매수 주문 칸이 되므로, 옛 라운드트립의 칸으로 잘못 붙을 수 있다.
     맞는 조각이 없으면 측정 불가로 센다.

### 9.3 M-3 꺼진 칸 장부 — 활성·잠김과 무관하게 굳힌다 (`SleeveDrawdownMonitor.java:108`)

- 선택: **배분금 > 0인 칸은 매 회차 굳히기를 먼저 부른다**(잠김 확인·활성 확인보다 앞). "다시 켤 때 최고 기록을 다시 잡기"는 택하지 않았다.
- 근거: ① 더 단순하다 — 굳히기는 이미 있는 하루 한 번꼴 동작이고 같은 날 다시 불러도 아무것도 안 한다. 재활성화를 알아채려면 "마지막으로 켜져 있던 때"를
  따로 저장해야 한다(배분·활성 설정은 재기동으로만 바뀌어 메모리 감지는 불가). ② 더 안전하다 — 다시 잡기는 꺼져 있는 동안 생긴 **진짜 손실**(그 칸 보유의
  손절 매도 등)까지 지워 버리지만, 굳히기는 장부를 맞게 유지해 진짜 손실만 낙폭에 남긴다. ③ 잠긴 칸도 함께 굳혀 오래 잠겼다 풀 때 재설정값이 틀어지지 않는다.

### 9.4 Red-Green 증거

```
[Red — 수정 전 구현으로 새 테스트 실행]
명령: gradle test --console=plain --tests *SleeveRealizedPnlAdapterTest --tests *SleeveRealizedPnlAdapterLoadTest
      --tests *TradeStatsServiceTest* --tests *SleeveDrawdownMonitorTest --tests *SleeveDrawdownMonitorLedgerTest
      (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-impl45)
종료 코드: 1 · 46개 중 실패 13 — 정확히 새 동작 테스트들:
  부하 6(시간 만료·상태 인덱스·회차당 1회·새 매도만 분봉·오늘 측정 불가 재시도·기간 밖 미추정) · A동 고아 매수 1 ·
  대시보드 칸 우선 2(당시 TradeStatsServiceTest 안) · 꺼진 칸 굳히기·잠긴 칸 굳히기·재활성화 헛잠금·회차 소요 기록 4.
  공용 픽스처로 옮긴 기존 감시기 테스트 10건과 기존 대시보드 16건은 통과(동작 보존).

[Green — 대상]
명령: 위 5개 + SleeveDrawdownWiringTest · SleeveControllerTest · SleeveRealizedLedgerTest · SleeveEquityCalculatorTest
종료 코드: 0 · 72개 중 72개 통과

[역확인 — 새 코드에서 주인 칸 우선을 끔(옛 선입선출과 같아짐)]
1차(테스트 이동 전): 종료 코드 1 · 26개 중 실패 3 → 원복
2차(TradeStatsCrossBucketTest로 이동 후): 종료 코드 1 · 11개 중 실패 3
  (A동 고아 매수 · 대시보드 고아 매수 · 칸 넘김 표시; '평범한 반복 매매는 그대로' 1건은 통과) → 원복, MUTATION 표식 0건 grep 확인

[최종 전체 — 결과 폴더 삭제 후, 모든 수정·이동 이후]
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-impl45 LOGGING_FILE_NAME=.../test.log gradle test --console=plain
종료 코드: 0 (BUILD SUCCESSFUL in 31s)
결과: 결과 XML 169개 합산 1024개 중 1024개 통과 · 실패 0 · 오류 0 · 건너뜀 0
```
(1024 = 감사 시점 리더 실행 1010 + 이번 신규 14: 어댑터 정확성 +2 · 부하 6 · 대시보드 칸 우선 3 · 감시기 장부/소요 4 − 옮긴 캐시 테스트 1)

### 9.5 이번에 바꾼 파일

- 운영 코드: `dashboard/TradePairer.java`(짝짓기·추정 분리, 칸 우선) · `dashboard/EstimatedTrade.java`(+crossBucket) ·
  `dashboard/TradeStatsService.java`(+crossBucketPieces, 규칙 문구) · `dashboard/SleeveRealizedPnlAdapter.java`(재설계) ·
  `risk/SleeveDrawdownMonitor.java`(굳히기 위치, 회차 소요 기록)
- 테스트: `dashboard/SleeveAdapterFixture.java`(신규 공용) · `dashboard/SleeveRealizedPnlAdapterTest.java`(픽스처로 정리 + 2건) ·
  `dashboard/SleeveRealizedPnlAdapterLoadTest.java`(신규 6건) · `dashboard/TradeStatsCrossBucketTest.java`(신규 3건) ·
  `risk/SleeveMonitorFixture.java`(신규 공용) · `risk/SleeveDrawdownMonitorTest.java`(픽스처로 정리, 동작 동일) ·
  `risk/SleeveDrawdownMonitorLedgerTest.java`(신규 4건). `TradeStatsServiceTest.java`는 결국 **무수정**(git 변경 없음).
- 손대지 않음: 리더가 고치는 중인 `HttpTimeouts`·`TelegramNotifier`·`DeadmanHeartbeat`·뉴스/DART·관련 테스트, `auto-start-paper` 파일,
  주문 경로, `RiskEngine`·`LiquidationService`, 연동 인터페이스.

### 9.6 남은 위험

- 실제 장중 소요 시간은 재기동 뒤 `[칸 낙폭] 회차 소요` 로그로 확인해야 한다(300ms 이하면 안 찍힌다). 배포 첫날 첫 회차는 굳히기 때문에 찍힐 수 있다.
- 9.2의 같은 칸 옛 조각 오차(한 조각 크기 상한)는 남는다. 칸 넘김 건수가 대시보드·로그에 보이면 그 종목의 주문 기록을 사람이 확인할 것.
- M-1·M-4·M-6·LOW는 지시대로 손대지 않았다(B동 재가동 전 필수 항목은 리더가 BACKLOG에 기록).

---

## 10. 재감사 후속 (46b N-1 · N-3 · N-2) — 2026-10-11

> 리더 지시: N-1(MEDIUM, 필수)·N-3·N-2만 고친다. N-4(고아 탐지 WARN)·N-5(무효화 시험)·N-6(승계 정책)은 손대지 않음(리더가 BACKLOG).
> 운영 앱·운영 DB·API 접근 0, 커밋 안 함, `auto-start-paper` 파일 미열람.

### 10.1 N-1 굳히기 실패가 잠긴 칸 재매도를 막던 결합 (`risk/SleeveDrawdownMonitor.java:107,137-144`)

- 수정: 굳히기만 `freezeQuietly`로 감싸 실패하면 WARN `[칸 장부] {칸} 굳히기 실패 — 이번 회차는 넘기고 나머지(재매도·판정)는 계속한다`를
  남기고 같은 회차를 이어 간다(잠긴 칸 재매도 → 활성 칸 판정). 다음 회차에 다시 시도한다.
- 전제 확인(코드): ① `SleeveRealizedLedger.freezeIfDue`는 마지막 `store.saveCheckpoint` 한 번으로만 상태를 바꾸고, 그 저장은 5줄을
  `saveAll` 한 번(한 트랜잭션)으로 쓴다(`SleeveStateStore.saveCheckpoint`) — 앞에서 실패하면 아무것도 안 남고, 저장이 실패하면 통째로 되돌아간다.
  ② `realized()`는 `cp.frozen().plus(window(cp.until(), 내일))`(`SleeveRealizedLedger.java:46-50`) — 굳히기가 실패해 `until`이 그대로면
  "이후 몫" 구간이 그만큼 넓어질 뿐 합계는 같다. 굳히기가 늦어져도 되는 여유는 분봉 보존 60일 − 굳히는 시점 30일 = 약 30일.
- 46 시점부터 있던 "굳히기 실패 시 활성 칸 판정도 그 회차 멈춤"도 같이 풀렸다.

### 10.2 N-3 매수가 검사 NaN 통과 (`dashboard/TradePairer.java:155`)

- `buyPrice <= 0` → `!(buyPrice > 0)`. NaN이면 비교가 늘 거짓이라 옛 검사는 통과시켰다 → 손익 NaN → 그 칸 계산이 "숫자 아님"으로 보류되고,
  굳힌 값에 박히면 영구 보류가 될 수 있었다. 이제 측정 불가(매수 체결가 없음)로 센다.

### 10.3 N-2 5초 재사용 (`dashboard/SleeveRealizedPnlAdapter.java:75,128-130,172-173,190-193` · `control/SleeveController.java:100`)

- ① **해제 경로는 재사용하지 않는다**: 원천 인터페이스에 기본 메서드 `refreshOnNextCall()`(기본은 아무것도 안 함, `bucket/SleeveRealizedSource.java:22`)을
  더하고, 어댑터는 이를 받으면 다음 호출에서 5초 재사용을 건너뛰고 DB 지문을 다시 확인한다. 해제(`SleeveController.unlock`)가 칸 자산 계산 바로 앞에서 부른다.
  상태 조회(GET)와 1분 감시 회차는 부르지 않아 **감시 경로 동작은 그대로**다(시험 `status_does_not_request_refresh`·기존 `one_database_read_per_round`).
  - 가장 작은 변경으로 고른 이유: 계산기·장부 메서드에 "새로 읽기" 인자를 꿰는 대신 원천에 요청 하나만 추가했다. 바뀐 공개 시그니처는
    **`SleeveController` 생성자 인자 하나(원천 주입)** 뿐이다 — 해제 경로가 원천에 닿을 다른 길이 없어서다. 인터페이스 쪽은 기본 메서드라
    기존 구현(시험의 람다들)이 그대로 컴파일된다. 연동 인터페이스(PositionManager·KisOrderClient·MarketDataService)는 무변경.
  - 감시 스레드가 요청을 먼저 소비해도 안전하다: 그 회차의 조회도 해제 요청 뒤에 일어난 것이라, 해제 계산이 그것을 다시 써도 해제 시점 이후 값이다.
- ② **시계가 뒤로 가면 재사용하지 않는다**: 경과가 음수면 같은 회차로 보지 않고 다시 읽는다(`withinRound`).

### 10.4 Red-Green 증거

```
[Red — 컴파일용 껍데기(원천 기본 메서드·컨트롤러 생성자 인자만, 호출·동작 없음)로 새 시험 실행]
명령: gradle test --console=plain --tests *SleeveDrawdownMonitorLedgerTest --tests *SleeveRealizedPnlAdapterTest
      --tests *SleeveRealizedPnlAdapterLoadTest --tests *SleeveControllerTest --tests *SleeveDrawdownWiringTest
      (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-impl45)
종료 코드: 1 · 36개 중 실패 6 — 정확히 새 동작 시험:
  N-1 2(굳히기 실패에도 잠긴 칸 재매도 · 활성 칸 판정) · N-3 1(NaN 매수가) · N-2 3(해제 전 새로 읽기 요청 · 새로 읽기면 재사용 건너뜀 · 시계 역행 재조회).
  대조 시험 '상태 조회는 새로 읽기 요청 안 함'은 통과(감시·조회 경로 무변경 확인).

[Green — 대상: 위 5개 + SleeveDrawdownMonitorTest · TradeStatsCrossBucketTest · TradeStatsServiceTest]
종료 코드: 0 · 65개 중 65개 통과

[최종 전체 — 결과 폴더 삭제 후, 모든 수정 이후]
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-impl45 LOGGING_FILE_NAME=.../test.log gradle test --console=plain
종료 코드: 0 (BUILD SUCCESSFUL in 33s)
결과: 결과 XML 169개 합산 1031개 중 1031개 통과 · 실패 0 · 오류 0 · 건너뜀 0  (1024 + 신규 7)
```

### 10.5 바뀐 파일

- 운영 코드: `risk/SleeveDrawdownMonitor.java`(굳히기 격리) · `dashboard/TradePairer.java`(NaN 1줄) ·
  `dashboard/SleeveRealizedPnlAdapter.java`(새로 읽기 요청·시계 역행) · `bucket/SleeveRealizedSource.java`(기본 메서드 1개) ·
  `control/SleeveController.java`(원천 주입 + 해제 직전 새로 읽기 요청)
- 시험: `risk/SleeveDrawdownMonitorLedgerTest.java`(+2) · `dashboard/SleeveRealizedPnlAdapterTest.java`(+1) ·
  `dashboard/SleeveRealizedPnlAdapterLoadTest.java`(+2) · `control/SleeveControllerTest.java`(원천 대역으로 조립 + 2)
- 파일 최대 250줄(운영 `SleeveRealizedPnlAdapter`)·233줄(시험 `SleeveControllerTest`).

### 10.6 남은 것

- N-4·N-5·N-6·PendingOrderRule CANCEL_REQUESTED 틈은 지시대로 손대지 않았다(리더 BACKLOG).
- 굳히기가 계속 실패하는 날은 WARN이 1분마다 칸마다 남는다(기존 감시 오류 로그와 같은 빈도) — 재기동 뒤 `[칸 장부] … 굳히기 실패`가 보이면 원인을 볼 것.
