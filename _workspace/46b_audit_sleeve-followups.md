# 46b_audit — ① 칸별 손실 상한 "감사 후속"(M-2·M-5·M-3) 재감사

> 2026-10-11(일) · risk-auditor · 코드 수정 0 · gradle 0 · 앱(PID 22036)·운영 DB·API 접근 0 · `auto-start-paper.bat` 미열람.
> 리더가 보고 전문의 요지를 이 경로에 보존했다. 대상: `45_impl_sleeve-drawdown-caps.md` 9절 수정분(운영 코드 5 · 시험 7).

## 0. 판정 — CRITICAL 0 · HIGH 0 · MEDIUM 1 · LOW 5 → 커밋 가능

조건: **N-1을 재기동 전에 고칠 것**(2~3줄 + 시험 1건, 재확인은 그 diff만).

| 46 ID | 상태 | 근거 |
|---|---|---|
| M-2 감시 스레드 부하 | **해소(코드로 확인)**. 실제 소요 시간은 재기동 뒤 확인 | §2-3 |
| M-5 주인 없는 옛 조각 | **부분 해소** — 칸 사이 잘못 붙는 문제는 해소, 같은 칸 안 잔여 오차는 N-4 | §2-2 |
| M-3 꺼진 칸 장부 | **해소** — 단 새 결합 N-1, 정책 질문 N-6 | §2-4 |
| M-1 청산일 판정 보류 | 현행 유지(사용자 결정). 이번 diff 무변경 | `SleeveEquityCalculator.java:69-71` |
| M-4·M-6·L-1~L-8 | BACKLOG "B동 재가동 전 필수" 기록 확인 | BACKLOG.md |

30_audit M-4는 "부분 해소 대기"(미커밋·미배포). real 프로필엔 감시기가 없다 — 실전 전환은 TRADING-RULES-AUDIT CRITICAL 해소 · 청산 리허설 · **사람 게이트 G2** 선행.

## 1. 판정표

| 심각도 | ID | 항목 | 위치 | 내용 |
|---|---|---|---|---|
| MEDIUM | N-1 | M-3 부작용: 굳히기 오류가 잠긴 칸 재매도를 막음 | `SleeveDrawdownMonitor.java:108-112`, 예외 처리 `:95-103` | 굳히기(`:108`)가 잠김 분기(`:109-112`) 앞으로 옮겨져, 굳힐 몫이 있는 날 굳히기가 예외를 내면 그 칸의 "잠긴 뒤 남은 보유 다시 팔기"가 그 회차 통째로 건너뛰어진다(예외가 이어지면 하루 종일). 46 시점엔 잠김 분기가 먼저라 없던 결합. 매수 차단·종목 손절·계좌 가드는 계속 돌아 즉시 손실은 없다. 수정: 굳히기만 자체 try/catch로 감싸 로그 후 계속 — 실패해도 칸 자산은 "굳힌 몫 + 이후 몫"으로 정확(`SleeveRealizedLedger.java:47-51`), 46 때부터의 "굳히기 실패 시 활성 칸 판정도 멈춤"도 같이 풀린다. 시험 "굳히기가 실패해도 잠긴 칸 재매도는 나간다" 추가 |
| LOW | N-2 | M-2: 5초 재사용이 DB 지문 확인까지 건너뜀 | `SleeveRealizedPnlAdapter.java:159-161` · `SleeveController.java:96-101` | 감시 회차 안에선 안전(체결 반영과 감시기가 같은 1스레드, 잠금·최고 상승은 2회 연속 확인). 남는 틈: ① 사람의 해제 요청이 회차·조회 직후 5초 안에 오고 그 사이 체결(특히 잠금 정리 매도)이 반영되면 실현손익은 옛 값·보유는 새 값 → 최고 기록이 그 손실만큼 높게 박혀 다음 거래일 헛잠금 가능 ② 시계가 뒤로 가면 재사용 기간이 길어짐. 권고: 해제 경로는 재사용 건너뜀, 경과 음수면 재조회 |
| LOW | N-3 | M-5 리팩터링: 매수가 검사가 약해짐(NaN 통과) | `TradePairer.java:155` | 옛 `price > 0`은 NaN 거부, 새 `buyPrice <= 0`은 통과 → 손익 NaN → 그 칸 계산 보류, 굳힌 값에 박히면 영구 보류 가능. 1줄 수정 `!(m.buyPrice() > 0)` |
| LOW | N-4 | M-5 잔여: 같은 칸 고아 조각 + 밝혀지지 않은 4번째 경우 | `TradePairer.java:101-113,123-143` · `FillProcessor.java:39,285` · `ShadowPortfolioReconciler.java:76` | (a) 같은 칸 고아: 오차 상한 "고아 수량 × (최근 매수가 − 고아 매수가)", 최고 기록이 오르기만 해 나중에 가짜 낙폭이 될 수 있고 표시·경고가 없다. (b) 대사 보정이 매도 체결 표시보다 먼저 보유를 0으로 만들고 같은 날 다른 칸이 그 종목을 사면 새 규칙이 틀린다(옛 FIFO가 맞음) — 지금은 A동만 사서 영향 0. 권고: "열린 조각이 남은 채 새 매수 체결" WARN + BACKLOG |
| LOW | N-5 | 무효화 정확성 일부를 지키는 시험 없음 | `SleeveRealizedPnlAdapter.java:65-66,143-147,186-198` · `SleeveAdapterFixture.java:92-103` | 픽스처가 늘 FILLED라 ① 체결수량>0인 CANCELLED·PARTIAL_FILLED·CANCEL_REQUESTED·CANCEL_FAILED 포함 ② 같은 주문의 체결 수량·시각 변경 시 재짝짓기 ③ 날짜 변경 시 추정 기억 비우기를 지키는 시험이 없다. 지금 코드는 맞다(코드 확인) |
| LOW | N-6 | 판단 보류(사람 결정): 꺼진 칸의 옛 전략 손익이 영구 보존 | `SleeveDrawdownMonitor.java:108` · `SleeveRealizedLedger.java:54-67` | M-3 선택(재설정 대신 굳히기)은 맞다. 그 결과 B동을 새 전략으로 켜면 옛 전략(MA 돌파·스캘핑) 실현손익을 영구 승계해 −20% 판정의 출발점이 된다. M-6과 함께 "승계 vs 새 출발"을 정할 것 — BACKLOG B동 항목 |

범위 밖 기존 결함(집계 제외): `PendingOrderRule.java:50-54`는 ACCEPTED 매수만 본다 — 앞 매수가 CANCEL_REQUESTED이거나 모의 체결조회 결함으로 부분 체결이 안 보인 채 취소 확정되면 같은 종목 재매수가 통과해, 두 칸이 같은 종목을 동시에 들거나 같은 칸 포지션이 의도의 2배가 될 수 있다(드묾).

## 2. 핵심 근거

### 2-1. 흐름·경계
- 보호 대상 HEAD 대비 diff 0: RiskEngine·LiquidationService·RiskRule·PendingOrderRule, order/·strategy/·signal/·position/·market/·scheduler/, SchedulingConfig·application.yml·application-backtest.yml.
- SleeveLiquidator·SleeveDrawdownGuard 무수정 — 정리 매도는 여전히 Signal.sell → RiskEngine → OrderEngine. 감시기는 기본 1스레드(ScheduledPlacementTest 2/2).
- 프로필: 어댑터·감시기 `@Profile("paper")` 그대로, TradePairer는 정적 도구. 백테스트 룰 목록 시험(SleeveDrawdownWiringTest) 3/3. 인터페이스 시그니처 불변, 저장소 메서드 추가 없음(기존 `findByStatusIn`), 스키마 변경 없음.

### 2-2. M-5
- 전제: `PendingOrderRule.java:42-47`(보유>0 거부)·`:50-54`(ACCEPTED 매수 거부) → 정상 흐름에서 한 종목은 한 번에 한 라운드. 매도 시점의 열린 조각 = 현재 라운드(주인 칸) + 고아.
- 짝의 칸은 언제나 매수 쪽 칸(`TradePairer.java:134`) — 주인 칸 규칙은 "어느 조각을 먼저 쓰나"만 바꾼다.
- 크기: 경우 1(같은 칸 고아) A동 기준 종목당 수만~수십만 원, 단독으로 잠금을 못 일으키나 시점을 몇 %p 당기거나 늦출 수 있음(A동 끝난 거래 0건이라 지금 노출 0) · 경우 2(취소 중 틈) 부분 매도일 때만 나눔이 틀어지고 표시 남음(지금 영향 0) · 경우 3(주문 없는 보유) A동 판정엔 닿지 않음.
- 배포 즉시 헛잠금 경로 없음(A동 실현손익 0, VB 여유 약 200만 원 대비 066570 재귀속 최대 약 10만 원).
- 066570은 운영 DB 미접근이라 실데이터 대조 못 함 — 같은 모양 시험 2건(`SleeveRealizedPnlAdapterTest:121-134`, `TradeStatsCrossBucketTest:32-47`)으로 옳은 방향 확인.
- 대시보드가 바뀌는 경우는 "066570형 하나"보다 넓다(고아·경우 2·경우 3+고아·4번째 경우). 평범한 데이터에선 0(TradeStatsServiceTest 16건 무수정 통과). 응답 모양 변화: `crossBucketPieces` 추가, `matchingRule` 문구, 측정 불가 목록 순서(화면 영향 없음).

### 2-3. M-2
- 지문 완전성: 주문(id·매수/매도·체결 수량·체결가·접수/체결 시각·칸) · 기록(종목·매도 시각·수량·손익) · 날짜. 상태는 빠졌으나 결과에 안 쓰임.
- 그날 추정 기억 안전: 지난날 분봉은 나중에 생기지 않음(`MinuteCandleCollector.java:78-85`), 분봉 삭제는 60일 지난 것뿐(30일 지나면 이미 굳힘), 날짜 바뀌면 기억 비움(`:143-147`). 오늘 측정 불가 비기억(`:153`) 의도대로. 자정엔 지문의 날짜로 재짝짓기.
- 6개 상태 전수 대조(OrderStatus 8개): ACCEPTED·FAILED는 생성 시 체결 수량 0(`OrderHistory.java:77-109`), 체결 수량을 바꾸는 메서드 3개 중 어느 것도 그 둘에 남기지 않음 → 체결수량>0에서 가능한 상태 = PARTIAL_FILLED·FILLED·CANCEL_REQUESTED·CANCELLED·CANCEL_FAILED·TIMEOUT = 조회 목록과 일치. 일괄 수정 쿼리 없음.

| 항목 | 46 시점 | 지금 |
|---|---|---|
| order_history 조회 | 매 호출 전체 훑기(26,847행), 분당 2회 | 회차당 1회, 상태 인덱스로 체결 가능한 행만(약 300행) |
| 재짝짓기 | 10분마다 + 체결마다(장중 하루 약 39회) | 체결·날짜 변경 시만 |
| 분봉 조회 | 재짝짓기마다 약 118회·약 2.3만 행 | 평시 0회(오늘 측정 불가 매도만 회차당 1회), 하루 첫 회차에 30일 창만 |
| 배포 첫날 | — | 4개 칸 07-20~09-11 굳히기 1회(체결 매도 116건 상한, 한 번뿐) |

H2 실행 계획(인덱스 실사용)은 확인하지 않았다.

### 2-4. M-3
- 꺼진 칸 굳히기의 부수효과: portfolio_state에 EVENT·MIX 칸마다 5줄, INFO 하루 1회(키 최대 29자 < 컬럼 50자). 판정·잠금·매도·텔레그램 경로 없음(`SleeveRealizedLedger.java:54-67`) — `inactive_buckets_are_not_evaluated`·`reactivated_bucket_is_not_falsely_locked`가 지킨다.
- 재설정을 택하지 않은 판단은 맞다(ADR-001:56-57 "자체 낙폭"과 일치, 재설정은 꺼진 동안의 진짜 손실을 지운다).

### 2-5. 시험 증거
- 결과 XML 169개 합산: tests 1024 · failures 0 · errors 0 · skipped 0(리더 값과 동일). 시각 00:33:44~00:34:06. 마지막 소스 수정 00:33:07(TradePairer), 컴파일 00:33:43 — 최종 소스로 돈 결과. `TradeStatsServiceTest` 16건 무수정 일치.
- Red(46개 중 13개 실패)는 산출물이 남지 않아 재확인 불가 — 시험 코드로 판단하면 13건 모두 옛 동작에서 실패하는 구조(부하 6·A동 고아 1·대시보드 2·감시기 4), 대조군 2건 포함, 산식 46 = 8+6+18+10+4, 13 = 6+1+2+4.

### 2-6. 규칙
- 바뀐·새 파일 최대 232줄(운영)·208줄(시험), 함수 최대 약 25줄, 매개변수 최대 5개. 비밀값은 픽스처 가짜 값뿐.

## 3. 커밋 범위 권고
① 커밋은 단독 컴파일된다(BucketTestSupport·MutableClock·Account.asStale·TradeResult.live 모두 HEAD에 있음).
- 운영 코드·설정 20개: bucket/ Sleeve{DrawdownProperties, Equity, EquityCalculator, Realized, RealizedLedger, RealizedSource, StateStore} · control/SleeveController · dashboard/{SleeveRealizedPnlAdapter, TradePairer, EstimatedTrade, TradeStatsService} · risk/Sleeve{DrawdownGuard, DrawdownMessages, DrawdownMonitor, Liquidator, LockRule} · risk/RiskRuleNameResolver · application-paper.yml · static/js/diag-history.js
- 시험 18개: bucket/{InMemoryPortfolioState, SleeveDrawdownPropertiesTest, SleeveEquityCalculatorTest, SleeveRealizedLedgerTest, SleeveStateStoreTest} · control/SleeveControllerTest · dashboard/{SleeveAdapterFixture, SleeveRealizedPnlAdapterLoadTest, SleeveRealizedPnlAdapterTest, TradeStatsCrossBucketTest} · risk/{SleeveDrawdownGuardTest, SleeveDrawdownMonitorLedgerTest, SleeveDrawdownMonitorTest, SleeveDrawdownWiringTest, SleeveLockRuleTest, SleeveMonitorFixture, RiskRuleNameResolverTest} · PaperSafetyGuardsConfigTest
- 섞지 말 것: `auto-start-paper.bat`. 리더 문서 4개(BACKLOG.md, Claude.md, SCOPE_GUARDIAN.md, docs/OPERATIONS.md)와 `_workspace/45_impl`·`46_audit`·`46b_audit`는 별도 문서 커밋. N-1 수정은 `SleeveDrawdownMonitor.java`와 새 시험 1건이 같은 커밋에 들어간다.

## 4. 재기동 후 확인 목록
1. `[RiskEngine] Loaded N risk rules`가 1개 늘고 SleeveLockRule 포함
2. 10-12(월) 첫 장중 회차 `[칸 장부] … 굳힘` — 4개 칸(TREND·VB·EVENT·MIX) 모두(EVENT·MIX 줄이 M-3 동작 증거), 굳힌 금액·측정 불가 건수 기록
3. `[칸 낙폭] 회차 소요 Nms` — 하루 첫 회차에만 나오면 정상, 평시에 반복되거나 1,000ms 넘으면 HIGH로 재감사
4. `[칸 낙폭] … 감시 오류` 0건(특히 EVENT·MIX)
5. `[칸 장부] 칸 넘김 짝 N건` WARN이 나오면 종목을 적고 주문 기록을 사람이 확인(066570 근처 예상)
6. 장중 `GET /api/buckets/sleeve-status` 1회 — VB 약 1,001만/최고 1,000만(066570 재귀속으로 수만 원 변동 가능), TREND 400만 + 평가손익, EVENT·MIX 비활성, 잠김 전부 false
7. `GET /api/performance/by-bucket?days=120`을 바뀌기 전(VB 추정 +11,900원·13건, 45_impl §5)과 비교, `crossBucketPieces` 확인 — 차이는 066570형 고아로만 설명돼야 함
8. (선택, DB 사본) `SELECT status, COUNT(*) FROM order_history WHERE filled_quantity > 0 GROUP BY status`에 FAILED·ACCEPTED 0건, `EXPLAIN`으로 `idx_order_history_status` 사용 여부
9. 잠금 없으면 칸 텔레그램 0건
10. A동 첫 청산일 "판정 보류 — 오늘 판 거래의 매도 가격을 아직 모름" → 다음 거래일 첫 회차 "판정 재개"
11. 위 확인 뒤 CLAUDE.md 규칙 6과 30_audit M-4 상태 갱신

## 5. 감사하지 못한 것 · 참고
- 실제 장중 소요 시간, H2 인덱스 사용 여부, 066570 실데이터, Red 재실행(산출물 없음).
- 감사 중 heredoc 한 번으로 명령이 멈춰 대기 프로세스 5개(python3 30620·python 18544·ps 16092·grep 34576·9592)가 남았다 — 감사관 권한으로 종료가 거부됐고, 입력 대기라 앱·시험 영향 없음. 정리 여부는 사용자 결정.

## 6. 사용자에게 전할 말 (쉬운 말)
칸별 가계부를 고친 세 군데는 제대로 고쳐졌다. 1분마다 처음부터 다시 계산하던 걸 "바뀐 게 있을 때만" 하게 해서 감시 담당이 훨씬 가벼워졌고, 다른 칸의 옛 기록이 A동 성적에 잘못 붙던 일과 꺼 둔 칸의 기록이 시간이 지나 사라지던 일도 막혔다. 다만 고치면서 "가계부 정리가 실패하면 잠긴 칸에 남은 주식을 다시 파는 일도 그날 같이 멈추는" 작은 연결 고리가 생겼다 — 돈이 바로 새는 문제는 아니고(손절 같은 다른 안전장치는 계속 돈다) 두세 줄이면 고칠 수 있어 재기동 전에 고친다.

## 7. 리더 후속 조치
- N-1(필수)·N-3(1줄)·N-2(해제 경로 재사용 건너뜀 + 음수 경과 재조회)는 같은 파일이라 구현 담당에게 한 번에 맡긴다 → 대상·전체 시험 → 이 감사관이 그 diff만 재확인 → ① 커밋.
- N-4(고아 탐지 WARN)·N-5(무효화 시험)·N-6(B동 재가동 시 승계 vs 새 출발)·PendingOrderRule CANCEL_REQUESTED 틈은 BACKLOG에 추가.

## 8. 재확인 — N-1·N-3·N-2 수정 diff (2026-10-11 05:4x) — CRITICAL 0 · HIGH 0 · MEDIUM 0 · LOW 1 → 커밋 가능

대상: `45_impl` 10절 "재감사 후속"(운영 5 · 시험 4). 코드 수정 0 · gradle 0 · 앱·운영 DB·API 접근 0.

| 심각도 | ID | 항목 | 위치 | 판정 |
|---|---|---|---|---|
| (해소) | N-1 | 굳히기 실패가 잠긴 칸 재매도·판정을 막던 결합 | `SleeveDrawdownMonitor.java:107,137-144` | 해소 — `freezeQuietly`로 감싸 실패해도 재매도(`:108-111`)→판정(`:112-128`) 계속. 전제 확인: 굳히기는 마지막 저장 전엔 상태를 안 바꾸고, 저장은 `SleeveStateStore.java:135-143`의 `saveAll` 한 트랜잭션. 칸 자산(`SleeveRealizedLedger.java:47-51`)은 "굳힌 몫 + 이후 몫"이라 합계 불변. 여유 약 30일. WARN은 계속 실패해도 칸마다 분당 1줄(예전 스택 전문보다 조용). 권고(막지 않음): 그날 첫 실패만이라도 스택 전문 + 텔레그램 |
| (해소) | N-3 | 매수가 검사의 NaN 통과 | `TradePairer.java:155` | 해소 — `m.buyPrice() == null \|\| !(m.buyPrice() > 0)`. 시험 `SleeveRealizedPnlAdapterTest.java:153-164` |
| (해소, 잔여 1) | N-2 | 5초 재사용이 해제 경로에서 낡은 값을 씀 | `SleeveRealizedPnlAdapter.java:75,127-130,172-173,189-193` · `SleeveController.java:100` · `SleeveRealizedSource.java:18-23` | 해소 — 해제만 `refreshOnNextCall()`, 감시·조회 경로 불변(`status_does_not_request_refresh`·`one_database_read_per_round`), 음수 경과는 재조회(`withinRound`). default 메서드라 기존 람다 그대로, 원천 구현은 paper 어댑터 하나뿐 — `SleeveDrawdownWiringTest` 3/3 |
| LOW | N-2′ | 해제 경로에 남은 타이밍 틈 | `SleeveController.java:100-101` · `KisPositionManager.java:62-74` | 해제가 "새로 읽기 요청" 뒤에 잔고를 읽어(3초 캐시가 지났으면 증권사 호출, 최대 약 1초) 그 사이 감시 회차가 요청을 먼저 써 버리면 예전 N-2와 같은 어긋남이 다시 날 수 있다(극히 드묾). 권고: 잔고를 먼저 읽고 → 요청 → 계산 |

- 시험 증거: XML 169개 · 1031건 · 실패·오류·건너뜀 0(리더 값과 동일). 실행 05:32:09~05:32:32, 마지막 소스 수정 05:31:06(SleeveController) → 컴파일 05:31:37~38. 클래스별 건수 일치(장부 6 · 어댑터 9 · 부하 8 · 컨트롤러 10 · 배선 3), Red 36건 중 6건 실패 산식 일치. 새 시험 7건 중 대조군 1건 외에는 고치기 전 코드에서 실패하는 구조.
- 커밋 범위: §3의 38개 파일 그대로(직전 감사 이후 바뀐 src 9개 모두 목록 안, 새 파일 없음). `auto-start-paper.bat` 제외.
- 재기동 후 확인 추가: ① `[칸 장부] … 굳히기 실패` WARN 0건 — 며칠 이어지면 약 30일 뒤 M-3 위험이 되살아나므로 원인 조사 ② 잠금을 풀게 되면 응답 `newPeak` = 직후 `GET /api/buckets/sleeve-status`의 그 칸 자산.
- 전달 사고: 감사관의 보고 도구는 한 번만 보낼 수 있어 재전송이 거부됐고, 리더가 작업 기록 끝에서 판정 부분만 잘라 읽었다.

### 리더 후속 (05:5x)
- **N-2′ 반영**: 감사관이 제시한 순서 그대로 `SleeveController.unlock`을 고쳤다 — `Account account = snapshotQuietly();` → `realizedSource.refreshOnNextCall();` → `calculator.compute(target, account)`. 이제 다른 호출이 요청을 먼저 쓸 수 있는 때는 감시 회차가 도는 동안(잠금 대기)뿐이고, 그동안 체결 반영은 같은 1스레드라 끼어들 수 없다.
- [검증 증거] 새 빌드 디렉터리(`auto_trading-build-lead49`)에서 전체 `gradle test --console=plain` → 종료 코드 0, 1031개 중 1031개 통과(XML 169개, 실패·오류·건너뜀 0).
- 감사관이 남긴 대기 프로세스 5개(python3 30620 · python 18544 · ps 16092 · grep 34576 · grep 9592)는 아직 살아 있다 — 정리는 사용자 판단.
