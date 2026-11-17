# 20_audit — 전고점 재오염 감지 + 계좌 기준 성적 API 감사

> 감사 2026-09-21 22:51~23:03 KST · `risk-auditor` · **코드 무수정, 빌드 미실행**(정적 대조만 —
> 같은 시각 다른 에이전트가 `com.trading.backtest`에서 gradle을 쓰고 있었다).
> 대상: 미커밋 변경 (`position` 2파일 수정 · `dashboard`/`order` 3파일 신규 + 3파일 수정).

## 0. 판정

**CRITICAL 0 · HIGH 0 · MEDIUM 3 · LOW 5 → 매매·리스크 경로 차단 사유 없음.**

이월: `12_audit`의 HIGH(`ConsecutiveLossRule` paper 무발동)는 **미해소**이나 이번 범위 밖이고 증분 0.
`15_audit` M-2(`sendCritical`가 실패·미설정·창밖을 전부 삼켜 호출이 전달을 뜻하지 않음)는 미해소이며
**이번 변경에서 같은 패턴이 재발했다** → 아래 M-1.

## 1. 지시 항목 8건 판정

| # | 항목 | 판정 | 근거 |
|---|---|---|---|
| 1 | `tick()` 판정 3줄 바이트 동일 | ✅ | `git diff -U0`의 제거 줄이 **정확히 1줄**(옛 `log.debug`), `numstat` `12 1`. 가드 3개는 현재 파일 `ShadowPortfolio.java:113`(`isFresh`)·`:119`(`current<=0 \|\| current<=peakEquity`)·`:122`(`isImplausible`)에 그대로 |
| 2 | 백테스트 텔레그램 격리 | ✅ 구조적 차단 | `EvidenceBasedPeakEquityCalibrator:20-22` `@Profile("paper")`만 오버라이드. `NoOpPeakEquityCalibrator:14-15`(`@Profile("backtest")`)는 **오버라이드 없음** → 인터페이스 기본 무동작(`PeakEquityCalibrator:41`)이고 `NotificationService`를 **필드로도 갖지 않는다**. `BacktestRunner:165`가 `tick()`을 불러도 호출 자체가 없다 → BACKTEST-DESIGN §12(OS 환경변수가 yml 빈 토큰을 이김) 재현 불가. **설정이 아니라 빈 배선으로 막은 것이 옳다** |
| 3 | 알림 실패가 판정을 망가뜨리지 않는가 | ✅ | 순서가 안전: `:129` 메모리 반영 → `:130` DB 커밋(메서드 단위 트랜잭션, `tick()`에 `@Transactional` 없음) → `:134` INFO → `:135` 알림(마지막) → `:136` catch. 알림이 던져도 **갱신·영속화는 이미 끝났다.** `RiskMonitor`·`GlobalEquityStopRule`은 `getPeakEquity()`(volatile)만 읽는다 |
| 4 | 주문 흐름 우회 | ✅ | 추가된 줄 전체에서 `OrderEngine`·`KisOrderClient`·`RiskEngine`·`execute(`·`placeOrder`·`.save(`·`.delete`·`.flush(` **매치 0건**. 새 `RiskRule` 없음, `RiskEngine` 무수정, `LiquidationService`/`LiquidationPhase` 무변경 → ADR-001 위반 없음 |
| 5 | 신규 API 읽기 전용 | ✅ (L-2) | `AccountPerformanceService`는 조회 4종만, 모든 헬퍼가 새 `LinkedHashMap` 반환·인자 무변형. `OrderQueryController`는 파생 쿼리 2종만, **KIS 호출 0 → 레이트리밋 소모 0**. NPE 점검: `netPnlSum:148`이 `:144`의 `isClosed()` 필터 뒤라 null 언박싱 불가 |
| 6 | 프로필 격리 | ✅ (M-3 관찰) | `AccountPerformanceController:26-29` paper 전용(= `DailyPnlController:28-30` 선례). `OrderQueryController` 프로필 없음 — 이사 나온 원본 `DashboardController:29`와 동일하고 의존이 전 프로필에 있어 적절. **paper 전용 빈을 프로필 없는 `PerformanceController`에 얹지 않은 것이 정확** — 얹었으면 backtest 기동이 `NoSuchBeanDefinitionException`으로 죽는다. URL 충돌 없음 |
| 7 | 동시성 | ✅ (M-2 별건) | `lastAlertDate`·`lastAlertPeak`는 non-volatile이나 **읽기·쓰기 전부가 `synchronized notifyPeakRaised:125` 안** → happens-before 보장, check-then-act 경합 없음. 0 나눗셈 없음(`:126`이 `previous<=0` 차단) |
| 8 | 비밀키 | ✅ | 신규 3파일 + 수정 2파일에 리터럴 토큰 0건(매치는 전부 식별자 이름). 토큰은 `TelegramProperties` 경유 유지 |

## 2. 위반 항목

### M-1 — 발송 사각지대 + 그날 1회 할당 조기 소모 ⚠ 이번 작업의 목적에 직접 닿는다

`EvidenceBasedPeakEquityCalibrator:136-138`이 상태 도장(`lastAlertDate`/`lastAlertPeak`)을
**`sendCritical` 호출 전에** 찍는다. 그런데 `TelegramNotifier:52-55`의 `send()`는
① 토큰 공란 ② **거래일 09:00~15:30 밖**이면 조용히 스킵한다.

`ShadowPortfolio.tick()`은 장외에도 1초마다 돌고 `KisPositionManager.snapshotAccount():55-64`에
장중 게이트가 없다 → **08:30 자동 기동~09:00 구간의 갱신도 fresh로 성립한다.**
그 구간에 오염이 들어오면 **텔레그램이 한 통도 안 나가면서 그날의 "첫 갱신 1회" 보장이 소모된다.**

왜 치명적인가: 2026-09-21 오염(+7.94%)은 `isImplausible` 상한(실측최대 × 15%) **아래**라
클램프가 걸리지 않았다 → **이 알림이 유일한 탐지선**인데 그 탐지선에 시간 구멍이 있다.
게다가 실측된 오염/오독 2건은 **둘 다 장외**였다(09-09 세션 중 추정 · 09-11 **17:17** 17,047,935원).

### M-2 — 1초 감시 루프 안의 블로킹 HTTP

`notifyPeakRaised`가 `synchronized` 안에서 동기 HTTP POST를 한다(connect 3s + read 5s = **최대 8초**).
`spring.task.scheduling.pool.size` 설정도 `TaskScheduler` 빈도 없어 **기본 풀 1개**로 추정되며,
그러면 `RiskMonitor:74`(MDD 강제청산)·`StopLossMonitor:72`(손절)가 같은 스레드를 쓴다
→ 발송 중 최대 8초 손절·청산 감시 정지. 하필 발송이 나가는 때가 **장중**이다.
기존 패턴의 확장이다(`RiskMonitor:106/128/145`, `ShadowPortfolioReconciler:101/163`이 이미 동일).
⚠ **풀 크기 1은 설정 부재로부터의 정적 추론 — 런타임 미확인.**

### M-3 — real 프로필 공백 (증분 0 · 이월 관찰)

`real`에는 `PeakEquityCalibrator` 구현체가 **하나도 없고** `ShadowPortfolio`도 없다
→ 전고점 추적·오염 탐지·계좌 성적 조회가 실전에서 전부 사라진다. 이번 변경이 그 표면을 더 넓혔다.
**이번 변경이 만든 결함은 아니다.** 실전 전환은 `docs/TRADING-RULES-AUDIT.md` CRITICAL 해소 +
게이트 G2(사람)가 전제이며, 이 공백 해소도 **그 게이트 안에서** 다뤄야 한다.

### LOW

| # | 내용 | 판단 |
|---|---|---|
| L-1 | `ShadowPortfolio:137` catch 문구 `"tick 오류 — peakEquity 유지"`가, 알림에서 던진 경우엔 오도(이미 갱신·저장 완료) | 이번 변경이 catch 도달 경로를 새로 만들었다 → 고칠 것 |
| L-2 | `@Transactional(readOnly=true)` 없음 | 기존 `PerformanceService`도 동일 — 스타일 일관, 보류 |
| L-3 | backtest에서 `?days=`가 시뮬 시각(`MutableClock`) 기준 | 파라미터 미지정이면 `NO_DATE_LIMIT`라 기존 동작 동일 → 증분 ≈ 0 |
| L-4 | 같은 날 이미 알린 뒤의 +1% 미만 오염은 무음 | 설계 의도(리더 확정). INFO 로그는 매번 남아 사후 추적 가능 |
| L-5 | `ShadowPortfolioTest.java` 636줄 (300줄 상한 초과) | 리더 인지·`BACKLOG.md` [2026-08-14]로 추적 중 — 재지적만, 차단 안 함 |

## 3. 감사하지 못한 것 (미확인)

1. **빌드·테스트 미실행**(지시). 19_impl의 687/687·종료 0·`PeakRaiseAlert` 12건·MDD 변이 검사는
   **구현자 문서의 주장을 인용한 것이지 재현한 것이 아니다.**
2. **스프링 컨텍스트 실기동 검증 없음** — 프로필 판정은 애너테이션 정적 대조다.
3. **스케줄러 풀 크기 1은 런타임 미확인** — M-2의 전제이므로 실제 지연 폭은 미측정.
4. **텔레그램 실발송 미관측** — 창 밖 스킵이 실제로 일어나는지 보지 않았다.
5. backtest 패키지 판정은 **2026-09-21 22:51~22:57 읽은 시점** 기준(동시 편집 중이었다).
6. **아직 배포 전** — 실행 중인 앱(PID 31624)은 이번 변경 이전 코드다. 재오염 감지의 실동작은
   **다음 개장(09-22 화 09:00~09:05) 관측 후** 증거가 필요하다.
7. `SCOPE_GUARDIAN.md` 확인: **릴리즈 체크리스트 항목 추가·삭제 없음**(행 수 불변, 근거란 갱신만)
   → CLAUDE.md "체크리스트 항목 추가/삭제는 사용자 승인 필요" 규칙 위반 아님.
