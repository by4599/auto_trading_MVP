# 14_impl — 마감 손익 알림: 보냈는지 기록 + 다음 거래일 아침 이월

날짜: 2026-09-15 · 브랜치 `backtest/regime-filter-and-validation` · 기준 HEAD `a956375`
범위: 지시받은 ①②③만. **매매 경로 무변경**(risk/order/strategy/signal/bucket/backtest 증분 0파일).
근거 감사: `13_audit_daily-pnl-notify.md` §3 (MEDIUM 1건)

---

## 0. 한 줄 결론

알림이 조용히 사라지던 길을 막았다 — **보낸 것만 원장에 도장을 찍고, 못 보낸 날은
다음 거래일 아침 09:05에 다시 보낸다.** 전체 테스트 **645개 전원 통과(종료 코드 0)**,
기준선 637 대비 +8(신규 테스트), 회귀 0.

---

## 1. 바꾼 파일 (4개, 전부 측정·알림 영역)

| 파일 | 왜 |
|---|---|
| `position/DailyEquity.java` | ① nullable 컬럼 `notified_at` + `markNotified()`/`isNotified()`/게터 |
| `position/DailyEquityRepository.java` | ③ 파생 쿼리 `findByEndEquityNotNullAndNotifiedAtNullOrderByTradeDateAsc()` |
| `scheduler/DailyPnlRecorder.java` | ②③ 전송 창 직접 확인 + 전송 성공 기록 + 09:05 이월 스케줄 |
| `test/scheduler/DailyPnlRecorderTest.java` | 신규 8건 + 기존 2건에 계약 추가 |

건드리지 않은 것: `TelegramNotifier`·`NotificationService`(시그니처 고정, CLAUDE.md 규칙 4) ·
`RiskEngine`·리스크 룰 14개 · `OrderEngine` · 전략 · 백테스트 엔진 · `DailyPnlController`.

```
$ git diff --name-only -- src/main/java/com/trading/{risk,order,strategy,signal,bucket,backtest}
(출력 없음 = 0파일)
```

---

## 2. 설계 판단

### ① 왜 컬럼 하나(`notifiedAt`)인가
직전 커밋(`6d99b27`)이 `end_equity`/`end_deposit`을 nullable로 얹은 전례를 그대로 따랐다.
`ddl-auto: update`라 재기동 시 자동 추가되고, **기존 행·백테스트 행은 null**이다.
별도 플래그(boolean)가 아니라 **시각**을 쓴 이유: "보냈다"보다 "언제 보냈다"가 사후 대조에
쓸모 있고, 값 하나로 이월 대상 판정(`null이면 미전송`)이 끝난다.

`markNotified()`는 이미 찍힌 값을 덮어쓰지 않는다 — `recordClose()`와 같은 규칙(먼저 찍힌 게 정본).

### ② 창 판정을 호출 측에서 한 번 더 하는 이유 (의도된 중복)
`TelegramNotifier:52`도 같은 창을 보지만 **창 밖이면 `log.info`로 조용히 건너뛰고
호출 측에 아무것도 돌려주지 않는다.** 보냈는지 모르면 (a) 이월할 수 없고 (b) 경고도 못 남긴다.
게다가 `recordCloseFor`의 첫 게이트와 전송 사이에는 잔고 조회(레이트리밋 ≥1초 + HTTP 최대 10초)가
끼어 있어 그 사이에 15:30을 넘길 수 있다. 코드 주석(`sendAndMark` javadoc)에 이 이유를 남겼다.

결과 동작:
| 상황 | 전송 | `notifiedAt` | 로그 |
|---|---|---|---|
| 창 안 | 보낸다 | 찍는다 + save | info |
| 창 밖 | **안 보낸다** | null 유지 | **warn**("…넘겨 알림 보류 — 다음 거래일 아침에 보낸다") |
| 예외 발생 | 삼킨다 | null 유지 | warn("…다음 거래일 아침에 다시 시도한다") |

원장 `save()`는 알림보다 **먼저** 유지했다(13_audit §2의 격리 보장). 그래서 성공 경로는
save가 2회(원장 1 + 도장 1)다 — 기존 테스트의 `verify(save)`를 `times(2)`로 명시 수정했다.

### ③ 이월은 별도 클래스로 빼지 않았다
`TimeCutScheduler`가 타임컷 본체와 재시도 스윕을 한 클래스에 두는 패턴 그대로,
`DailyPnlRecorder` 안에 `@Scheduled` 메서드를 하나 더 뒀다(파일 245줄 — 300줄 상한 내).

- **09:05 KST 평일** — 전송 창이 막 열린 직후. 마감 직전에는 타임컷·스윕·1초 감시가 같은
  스레드에 몰려 크론이 밀린다(실측 15:25 크론 +321초).
- **잔고를 다시 부르지 않는다.** 보내는 건 알림뿐이고, 원장은 그날 장중에 찍힌 값이 정본이다.
  아침에 다시 읽으면 다른 날 자산이 섞인다. (장외 KIS 호출을 늘리지 않는다는 기존 정책과도 일치)
- 대상: `closed == true && notifiedAt == null && tradeDate < today`.
  마지막 조건은 "오늘 것은 그날 15:29 경로가 맡는다" — 메시지의 "지난 거래일" 표기를 참으로 만든다.
- 상한 5건, **날짜 오름차순**. 잘라낸 날짜는 `log.warn`에 날짜를 나열한다.
- 가드: 자격증명 → 휴장일 → `isDuringMarketHoursNow()`. 창 밖이면 다음 기회로 미룬다.

메시지 머리말만 갈라 쓴다(본문은 공유):
- 당일 `📒 [2026-09-14 마감] 오늘 …`
- 이월 `📒 [지난 거래일(2026-09-11) 마감분] 그날 …`

---

## 3. 실행한 명령과 출력 (전부 이번 세션 실행)

빌드 경로는 라이브 앱(PID 16216) 점유를 피해 **별도 경로**를 썼다:
`TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-impl-build`.
Gradle은 캐시 배포본 직접 호출(`gradle-9.2.0/bin/gradle`).

### 기준선 (코드 변경 전)
```
명령: gradle clean test --console=plain
종료 코드: 0 · BUILD SUCCESSFUL in 27s
결과: classes=95 tests=637 failures=0 errors=0 skipped=0
```

### RED (테스트만 작성, 구현은 스텁)
```
명령: gradle clean test --tests "com.trading.scheduler.DailyPnlRecorderTest"
종료 코드: 1
결과: 18개 중 12개 통과, 실패 6
  - 창을 넘기면 보내지 않고 notifiedAt도 비운다 (핵심 회귀)
        : NeverWantedButInvoked: notificationService.sendCritical(<any>)
  - 창 안에서 보내면 보낸 시각을 원장에 찍는다
        : expected: 2026-09-14T15:29 but was: null
  - 다음 거래일 아침(09:05) — 못 보낸 마감분을 보내고 어느 날 것인지 밝힌다
        : Wanted but not invoked: sendCritical(<Capturing argument>)
  - 이미 보낸 마감분은 다시 보내지 않는다        : Wanted but not invoked
  - 여러 건 밀리면 최근 5건만 오래된 날짜부터     : Wanted but not invoked
  - 시작 기록이 있는 거래일 → 마감 기록           : TooFewActualInvocations: save() Wanted 2 times
```

### GREEN (구현 후)
```
명령: gradle clean test --tests "com.trading.scheduler.DailyPnlRecorderTest"
종료 코드: 0 · BUILD SUCCESSFUL in 15s
결과: tests=18 failures=0 errors=0 skipped=0
```

### Red-Green 변이 ① — 창 확인 제거 (핵심 회귀)
```
변이: sendAndMark()의 if (!marketCalendar.isDuringMarketHoursNow()) 블록 삭제
명령: gradle clean test --tests "com.trading.scheduler.DailyPnlRecorderTest"
종료 코드: 1
결과: 18개 중 17개 통과, 실패 1
  - 창을 넘기면 보내지 않고 notifiedAt도 비운다 — 잔고 조회가 15:30을 넘긴 날 (핵심 회귀) FAILED
→ 이 테스트는 정확히 이 수정만 검사한다 (다른 17건은 영향 없음)
```

### Red-Green 변이 ② — 도장 제거
```
변이: ledger.markNotified(LocalDateTime.now(clock)) 삭제
종료 코드: 1
결과: 18개 중 15개 통과, 실패 3
  - 다음 거래일 아침(09:05) … / 이미 보낸 마감분은 다시 보내지 않는다 /
    창 안에서 보내면 보낸 시각을 원장에 찍는다
→ "보냈다는 기록"이 이월 억제의 실제 근거임을 증명
```

### 복구 확인
```
$ diff <스크래치 원본> src/main/java/com/trading/scheduler/DailyPnlRecorder.java
(차이 없음 — RESTORED)
```

### 최종 (컴파일 + 전체 테스트)
```
명령: gradle clean build --console=plain
종료 코드: 0 · BUILD SUCCESSFUL in 29s
결과: classes=95 tests=645 failures=0 errors=0 skipped=0
→ 기준선 637 → 645 (신규 8). 회귀 0
```
집계: `C:/Users/SAMSUNG/auto_trading-impl-build/test-results/test/*.xml` 속성 합산.

### 안전 확인
```
라이브 앱 PID 16216 : java.exe 생존 (557,896 K)
C:/Users/SAMSUNG/auto_trading-build : mtime 2026-09-15 08:48 — 이번 세션에 건드리지 않음
trading-db : 접근 없음
```

### 규격
```
DailyPnlRecorder.java 245줄 (<300) · 최장 메서드 recordCloseFor 44줄 (<50)
DailyEquity.java 115줄 · 테스트 398줄 (코드베이스 5번째 — FillStateUpdaterTest 488줄 등 선례 내)
```

---

## 4. 신규 테스트 8건 (요구 항목 대조)

| 요구 | 테스트 |
|---|---|
| 창 안 → 전송 + notifiedAt | `marks_notified_at_when_sent_inside_window` |
| **창 밖 → 미전송 + null + 경고 로그** | `does_not_send_when_window_closed_and_leaves_notified_at_null` |
| 아침 이월 → 보내고 도장 | `morning_catchup_sends_pending_and_marks_notified` |
| 이미 보낸 건 재발송 안 함 | `morning_catchup_does_not_resend` (두 번 실행 → 1회) |
| 휴장일이면 이월도 안 돎 | `morning_catchup_skips_on_holiday` |
| 창 밖이면 이월도 안 돎 | `morning_catchup_skips_before_open` (08:50) |
| 상한 + 정렬 | `morning_catchup_sends_recent_five_oldest_first` (7건 → 5건, 09-07부터 09-11) |
| 오늘 것은 이월 대상 아님 | `morning_catchup_ignores_today` |

테스트 기법:
- **`SteppingClock`**(테스트 내부 `Clock` 서브클래스) — 잔고 조회가 5초를 먹어 15:29:58 → 15:30:03이
  되는 실측 사고를 그대로 재현. Java 25 Mockito 제약 때문에 목이 아니라 실객체.
- 리포지토리 목은 `thenAnswer`로 파생 쿼리 의미(마감됨 + 미알림 + 날짜 오름차순)를 그대로 흉내 냈다 —
  그래서 "도장 찍은 뒤엔 다음 조회에서 빠진다"가 실제로 검증된다.
- 경고 로그는 logback `ListAppender`로 캡처했다(이 저장소 첫 사용). "조용한 소실"이 이 결함의
  본질이므로 **경고를 남긴다는 것 자체가 계약**이라고 봤다.

---

## 5. 남긴 한계 (다음 사람이 알아야 할 것)

1. **재기동 전까지 적용되지 않는다.** 라이브 앱(PID 16216)은 옛 코드다. `notified_at` 컬럼도
   `ddl-auto: update`라 **재기동 시** 생긴다. 지시대로 앱을 끄지 않았다.
2. **스프링 배선은 컴파일까지만 검증됐다.** 웹 컨텍스트를 띄우는 `@SpringBootTest`가 이 저장소에
   없다. 단 새 파생 쿼리 이름은 기존 `@DataJpaTest` 4개가 리포지토리를 만들며 파싱하므로
   **이름 검증은 이미 통과**했다(645개에 포함). 실제 09:05 발화는 배포 후 첫 아침에 확인할 것.
3. **5건 상한은 "버린다"가 아니라 "미룬다"이다.** 잘라낸 날은 `notifiedAt`이 비어 있어 다음 아침에
   이어서 나간다(하루 5건씩 배수). 도장을 대신 찍어 영구 폐기하는 선택은 하지 않았다 —
   `notifiedAt`은 "보낸 시각"이므로 안 보낸 날에 찍으면 원장이 거짓말을 한다.
   부작용: 7건 밀리면 최근 5건이 먼저 가고 오래된 2건이 그 다음 날 간다(도착 순서가 날짜순이 아님).
4. **전송 "성공"은 창 안에서 예외가 없었다는 뜻일 뿐이다.** `TelegramNotifier.send()`가 HTTP 실패를
   내부에서 삼키므로(`:63-66`) 텔레그램 서버가 거부해도 도장이 찍힌다. 인터페이스를 바꾸지 말라는
   제약 때문에 여기까지가 한계다. 도달률 측정은 여전히 미해결(13_audit §11-3).
5. **09:05도 절대 안전하지는 않다.** 스케줄 스레드는 여전히 1개다. 다만 그 시각에 경합하는 크론은
   마감 직전보다 훨씬 적고, 창이 15:30까지 6시간 넘게 열려 있어 밀려도 창을 넘길 가능성이 낮다.
   근본 해결(스케줄 풀 확장)은 범위 밖이라 손대지 않았다.
6. **실전(`real`) 프로필에서는 여전히 이 기능이 없다.** `@Profile("paper")` 유지 — 13_audit §9의
   판단 보류 항목이며 사용자 확인 사항이다.

## 6. 발견했지만 손대지 않은 것 (보고만)

- `DailyPnlController`는 `notifiedAt`을 노출하지 않는다. 대시보드에서 "알림 갔는지"를 보려면
  한 줄 추가가 필요하지만 요청 범위 밖이라 두었다.
- **이번 테스트 실행이 운영 로그 `logs/paper.log`에 섞였다** — 09:24:12에 `[Test worker]` 줄이
  append됐다(라이브 앱 PID 16216이 같은 파일에 쓰는 중). SCOPE_GUARDIAN.md가 이미 "판정 근거인
  로그 자체가 오염돼 있다 … 분리 작업은 별도 과제"로 기록해 둔 기존 문제이고, 이번에 새로 만든
  것이 아니다. 범위 밖이라 손대지 않았다. 로그로 무언가를 판정할 때 `[Test worker]` 줄은 버릴 것.
- 13_audit L-1(`sendCritical` 이름/의미 불일치)은 그대로다 — 인터페이스에 `sendInfo`가 없어
  대안이 없고, 시그니처 변경 금지 제약에 걸린다.
