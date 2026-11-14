# 60_audit — 잔고 낡음 감시 · 취소 중 매수 차단 · 1주 강제 매수 스위치 (2026-11-14)

[리스크 감사] 범위: 미커밋 작업 트리 중 3건 (risk-rule-audit 절차)
1. 신규 `risk/StaleBalanceWatchdog.java` (+ `StaleBalanceWatchdogTest`)
2. `risk/PendingOrderRule.java` (+ `RiskRuleNameResolver`, `PendingOrderRuleTest`, `RiskRuleNameResolverTest`)
3. `order/OrderSizingService.java` + `risk/RiskLimitsProperties.java` (+ `backtest/ExecutionKnobs`, `StopWidthLab`)

빌드·테스트는 재실행하지 않았다(요청자 보고: 1183/1183 통과). 이 문서의 판정은 코드 대조 근거다.

## 결론

→ **CRITICAL 0 · HIGH 0 · MEDIUM 2 · LOW 7**
→ paper 배포를 막는 항목 없음. 단 **M-1이 해소되기 전에는 `sizingMinOneShare`를 paper에서 켜면 안 된다.**
→ 실전(real) 전환과는 무관 — 실전은 `docs/TRADING-RULES-AUDIT.md` CRITICAL 해소 + 게이트 G2(사람) 선행.

## 통과 확인 (위반 없음)

| 확인 | 근거 |
|---|---|
| 흐름 우회 없음 — 세 변경 모두 주문을 내지 않음 | `StaleBalanceWatchdog.java:54-95`는 읽기+알림만. strategy 패키지의 OrderEngine 참조는 주석 1건(`VolatilityBreakoutStrategy.java:13`) |
| `RiskEngine` 무수정 | `git status`에 `RiskEngine.java` 없음. 새 조건은 기존 `PendingOrderRule` 안에 추가 |
| `PendingOrderRule` 새 조건이 매도를 막지 않음 | `PendingOrderRule.java:36-38` (매도는 첫 줄에서 통과) + `PendingOrderRuleTest.never_blocks_sell` |
| 백테스트 결정성 무영향 (취소 중 상태가 생기지 않음) | CANCEL_REQUESTED로 바꾸는 곳은 `FillStateUpdater.java:152`·`KisBrokerageApiClient.java:65`뿐이고 `FillStateUpdater`·`FillProcessor`·`FillPoller`는 `@Profile("paper")`(`FillStateUpdater.java:45`, `FillProcessor.java:31`, `FillPoller.java:20`). backtest 패키지에 CANCEL_REQUESTED 참조 0건. 백테스트 영향은 매수 판정마다 조회 1회 늘어나는 속도 비용뿐 |
| 감시견 프로필 격리 | `StaleBalanceWatchdog.java:25` `@Profile("paper")` + 스케줄링 자체가 `SchedulingConfig.java:34` `@Profile("!backtest")` |
| 감시견 동시성 | 상태(`staleSince`, `alerted`)는 `synchronized observe/reset`(`:70`, `:92`) 안에서만 읽고 쓴다. 호출 스레드도 기본 스케줄러 1개(`SchedulingConfig.java:43-46`) |
| `sizingMinOneShare` 기본 OFF에서 기존 동작과 동일 | `OrderSizingService.java:87` `forcedOneShare = quantity<1 && false` → 항상 false. `:91`의 0주 스킵, `:98`의 `!false && distortion>한도`는 기존 식과 같다 |
| paper에서 켜질 경로 없음 | `RiskLimitsProperties`는 평범한 `@Component`(`:12-13`, `@ConfigurationProperties` 아님 → yml 바인딩 불가). `ParamCatalog.java:20-63`에 해당 키 없음 → 대시보드 저장·`app_setting` 복원 불가. 유일한 setter 호출부 `ExecutionKnobs.java:86-88`은 `@Profile("backtest")`(`:16`). 저장소 전체 검색 결과 호출부는 `StopWidthLab.java:82,98`뿐 |
| 백테스트 랩이 스위치를 되돌림 | `StopWidthLab.java:58-59` finally → `restoreDefaults()`의 `:98` `setSizingMinOneShare(false)` |

## 발견 항목

| 심각도 | 항목 | 위치 | 내용 | 근거 |
|---|---|---|---|---|
| MEDIUM | M-1 1주 강제 매수의 위험 상한이 없다 — `PositionLimitRule` 10%는 상한 역할을 못 한다 | `PositionLimitRule.java:22-23`, `OrderEngine.java:94`, `OrderSizingService.java:86-102`, `:105-119` | ① `PositionLimitRule`은 **지금 보유 비중**만 본다(`account.getPositionWeight`). 새로 사는 종목은 0%라 무조건 통과하고, 주문 수량은 리스크 룰 통과 **뒤** `OrderEngine`에서 정해진다(`:94` sizeBuy) — 룰은 수량을 보지 못한다. ② 스위치 ON이면 왜곡 한도 검사도 빠지므로(`:98`) 1주 리스크/1R 배수에 위가 없다. 예: TREND 칸 400만원·0.25R=1만원, 140만원짜리 종목 ATR 4%(5.6만원)×1.0 → 1주 리스크 **5.6R**, 비중 **35%**(10% 한도의 3.5배). ③ 남는 실제 상한은 칸 가용 현금(`:105-119`, paper 칸 ON일 때만)과 백테스트 현금 검사(`BacktestOrderClient.java:108`)뿐 — "1주 가격 ≤ 현금"이라는 약한 상한이다. 갭 하락이면 손절폭보다 더 잃는다. ④ 같은 구조 결함은 스위치와 무관하게 평시 사이징에도 있다(손절폭이 좁으면 R 사이징 수량의 금액이 10%를 넘을 수 있는데 막는 곳이 없다) | CLAUDE.md 리스크 룰 표 "종목당 비중 최대 10%". 현재 paper OFF·켤 경로 없음이라 즉시 손실 없음 → MEDIUM. **paper에서 켜기 전 "1주 리스크 ≤ k·R" 같은 상한 + 사람 승인 필수** |
| MEDIUM | M-2 취소 중 매수 차단은 짧은 창만 막는다 — 모의 체결조회 결함으로 "취소 창 체결"이 CANCELLED 0주로 굳는 틈은 그대로 | `FillProcessor.java:81-97`, `FillStateUpdater.java:185-203`, `PendingOrderRule.java:42-62` | 모의 체결조회는 실제 체결에도 빈 목록을 준다(CLAUDE.md 결함 5). 그러면 CANCEL_REQUESTED 주문은 다음 폴(약 3초)에 `finalizeAfterCancel(…, 0, …)`로 **CANCELLED·체결 0주**가 되고 포지션이 생기지 않는다. 취소 창에서 실제로 일부가 체결됐다면 브로커엔 보유가 있는데 DB엔 없어 1번(보유)·2번(ACCEPTED)·3번(CANCEL_REQUESTED) 검사가 모두 통과 → **재매수 가능**. 이 틈은 주기 재동기화(10분 주기, 2주기 지속 필요 ≈ 20분)가 메울 때까지 열려 있다. 이번 변경이 만든 틈은 아니다(기존 결함). 이번 조건은 "체결조회 API 자체가 실패해 CANCEL_REQUESTED에 머무는" 경우를 막는 데 실효가 있다 | BACKLOG [2026-10-11] 대응 범위의 한계. 판정: 이번 변경은 개선이며 회귀 아님 |
| LOW | L-1 감시견이 기본 1스레드에 잔고 호출을 하나 더 얹는다 (추가분은 작음) | `StaleBalanceWatchdog.java:53,63`, `KisPositionManager.java:68-84`, `KisApiClient.java:53-54,250-258`, `KisRateLimiter.java:39-54` | 실패 시 캐시를 갱신하지 않으므로(`KisPositionManager.java:76-84`) 장애 중엔 3초 캐시가 무력 → 감시견 호출도 매번 실제 잔고 API를 친다. 1회 최악 지연: 레이트 대기(모의 1건/초 공유) + 연결 5초·읽기 10초 × 최대 4회 + 재시도 대기 1+2+4초 ≈ **60초대**. 다만 같은 스레드의 `RiskMonitor.java:74`·`StopLossMonitor.java:81`·`ShadowPortfolioTicker.java:31`이 이미 1초마다 같은 호출을 하고 있어 감시견 추가분은 30초+에 1회(약 3% 미만). 낡은 동안엔 손절·청산 판정이 어차피 건너뛰어지므로 실질 영향은 "간헐 실패(성공·실패 섞임)" 구간의 지연 1회분. 연속 실패 카운터(SAFE_MODE 전환)도 그만큼 조금 빨리 찬다 | 수용 가능. 원하면 마지막 결과만 읽는 비호출형 조회로 바꾸면 0이 된다 |
| LOW | L-2 시각 경계 구멍 | `StaleBalanceWatchdog.java:56-67,83-89`, `MarketCalendarService.java:66-71` | ① 장 시작 전부터 낡았어도 09:00에 0부터 센다 → 가장 이른 알림 ≈ 09:10(+최대 30초). 개장 직후 10분은 알림 공백. ② 15:20 이후 시작된 낡음은 10분을 못 채워 그날 알림 없음. ③ 장 마감 뒤 회복하면 회복 알림이 없다(장외 `reset()` `:57-59`가 조용히 지움) — 사용자는 "실패" 알림만 받고 끝난다. 다음 날 여전히 낡으면 09:10경 다시 알린다(누락은 아님). ④ 점심 휴장은 KRX에 없고 달력도 09:00~15:30 연속이라 구멍 없음 | 기능 공백일 뿐 안전장치 우회 아님 |
| LOW | L-3 `alerted`가 전송 성공과 무관하게 먼저 선다 — 장외 삼킴은 15:30 경계에서만 생긴다 | `StaleBalanceWatchdog.java:56,63,83-88`, `TelegramNotifier.java:84-93` | 장중 판정은 감시견과 `TelegramNotifier`가 같은 Clock으로 하므로 평시엔 어긋나지 않는다. 단 `now`를 **잔고 호출 전에** 잡는다(`:56`) — 15:29:4x에 판정 통과 후 잔고 호출이 수십 초 막히면 실제 전송 시각이 15:30을 넘어 `TelegramNotifier.java:89`가 삼키고 `alerted=true`만 남는다. 다음 주기는 장외라 `reset()` → 영구 누락은 아니고 그날 마지막 알림 1통만 사라진다. 봇 토큰 미설정(`:84`)·전송 큐 HTTP 실패도 같은 이유로 재시도 없음 | 수용 가능 |
| LOW | L-4 알림 폭주 상한 | `StaleBalanceWatchdog.java:71-89` | 낡음 한 번 = 실패 1통 + 회복 1통. 다시 알리려면 10분 연속 낡음이 필요하므로 최악(10분 낡음·1회 성공 반복)도 약 10.5분에 2통, 장중 하루 최대 ~40통. 그런 깜빡임이면 `KisApiClient`의 SAFE_MODE 알림과 겹쳐 중복 메시지가 생긴다 | 폭주 아님 |
| LOW | L-5 감시견 테스트가 `check()`를 덮지 않는다 | `StaleBalanceWatchdogTest.java:24` | 협력 객체를 전부 null로 두고 `observe()`만 시험한다. 장외 리셋(`:57-59`), 스냅샷 예외 → 낡음 처리(`:64-66`), 자격증명 미설정 스킵(`:55`)은 미검증. `PositionManager`·`NotificationService`는 인터페이스라 목/람다로 만들 수 있다 | 증거 공백 |
| LOW | L-6 PARTIAL_FILLED 매수 잔량 · CANCEL_FAILED 틈 | `PendingOrderRule.java:42-62`, `FillStateUpdater.java:120` | 매수가 부분 체결(PARTIAL_FILLED)인 채 잔량이 살아 있고 그 사이 손절 등으로 보유가 0이 되면 1·2·3번 검사가 모두 통과 → 잔량 대기 중 새 매수 가능. 부분 체결분은 포지션을 만들므로 평소엔 1번이 막는다 — 드문 조합. 같은 논리로 CANCEL_FAILED(24시간 뒤 "사람 확인 필요")도 막지 않는다 | **판단 보류** — 막는 쪽(fail-closed)이 일관적이나, CANCEL_FAILED를 막으면 사람이 정리할 때까지 그 종목 매수가 영구히 멈춘다. 양쪽 근거 병기 |
| LOW | L-7 `ExecutionKnobs.resetSizing()`이 새 스위치를 되돌리지 않음 + 강제 1주 표시 없음 | `ExecutionKnobs.java` resetSizing, `StopWidthLab.java:95-98`, `OrderSizingService.java:121-123` | 지금은 `StopWidthLab`의 finally만 OFF로 되돌린다. 다른 랩이 이 스위치를 쓰고 finally를 빠뜨리면 같은 JVM의 다음 랩으로 새어 결과가 오염된다(백테스트 한정, paper 무관). 또 강제 1주 체결이 사이징 로그에 표시되지 않아 리포트에서 구분이 안 된다 | 정리 권장 |

## 질문별 답

**1) 감시견**: 기본 1스레드 지연은 L-1(추가분 작음, 최악 1회 60초대) · 장외 리셋 구멍은 L-2 ①~③(점심 경계 없음) · 알림 폭주는 L-4(상한 있음) · 동시성 문제 없음 · backtest 누출 없음 · 장외 삼킴 후 alerted 먼저 섬은 L-3(15:30 경계에서만, 다음 주기에 리셋).

**2) PendingOrderRule**: 매도 미차단 확인. 24시간 차단은 **수용 가능** — paper에서 CANCEL_REQUESTED는 조회가 성공하면 다음 폴(약 3초)에 끝난다(`FillProcessor.java:92-95`). 오래 남는 건 체결조회 API가 계속 실패할 때뿐이고, 그때 새로 사는 편이 더 위험하다(이중 보유). A동은 일봉 돌파라 한 종목 하루 놓치는 비용이 작다. 다만 차단 중 별도 알림은 24시간 뒤 CANCEL_FAILED 때까지 없다. backtest 결정성 무영향. 남는 틈 M-2·L-6. 흐름 우회·RiskEngine 수정 없음.

**3) sizingMinOneShare**: 기본 OFF 동일성 확인 · paper에서 켜질 경로 없음(yml·대시보드·app_setting 모두 불가) · 켰을 때 상한은 **실질적으로 없다**(M-1 — `PositionLimitRule`은 새 매수 크기를 보지 않고, 남는 건 현금 한도뿐).

## 배포 판정

- paper 배포: **가능** (CRITICAL·HIGH 0). M-1은 스위치가 OFF이고 켤 경로가 없어 지금 돈이 새는 경로가 아니다.
- 조건: `sizingMinOneShare`를 paper에 올리려면 M-1의 리스크 상한 + 백테스트 재검증 + 사람 승인이 먼저다. 이 값을 `ParamCatalog`·yml에 노출하는 변경이 생기면 재감사.
- 실전: 이 감사와 별개로 `docs/TRADING-RULES-AUDIT.md` CRITICAL 해소 + 게이트 G2(사람) 선행.
