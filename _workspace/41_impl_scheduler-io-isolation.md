# 41_impl — 감시 스케줄러 I/O 격리 (텔레그램 비동기 + I/O 전용 스케줄러)

> 2026-10-10(토) 11:0x~11:25 KST · `trading-implementer` · 사용자 승인 설계 구현 · **커밋 안 함**
> 대상 결함: `BACKLOG.md` [2026-09-21] "감시 스케줄러 스레드 1개" · `_workspace/20_audit_account-performance.md` M-2
> 빌드 폴더: `C:/Users/SAMSUNG/auto_trading-build-impl41` (운영 앱 폴더 `auto_trading-build`는 건드리지 않음)

## 0. 결론

**텔레그램 전송이 이제 손절·강제청산 감시를 멈추지 않는다. 뉴스·공시 수집은 별도 스레드 2개로 옮겼고,
매매 감시는 지금처럼 스레드 1개에서 순서대로 돈다.** 전체 테스트 932개 통과(실패 0, 종료 코드 0).

쉬운 설명(사용자 전달용): 경비원 한 명이 CCTV(손절·청산 감시)를 보면서 편지(텔레그램)도 직접 우체국에
부치러 다녀왔다 — 다녀오는 최대 8초 동안 CCTV가 비었다. 이제 편지는 우체통에 넣기만 하면 전담 집배원이
순서대로 가져간다. 신문 수거(뉴스·공시)는 다른 직원 둘에게 넘겼다. CCTV는 여전히 그 경비원 한 명만 본다
(여럿이 동시에 보면 서로 엉킬 수 있어서다).

| 항목 | 전 | 후 |
|---|---|---|
| 텔레그램 전송 중 감시 스레드 | 최대 8초 정지(connect 3 + read 5) | 즉시 반환 — `telegram-sender` 스레드가 전송 |
| 강제청산 첫 매도까지 알림 대기 | 최대 16초(`RiskMonitor:106`이 `:109` 청산 트리거 전에 8초 + `LiquidationService:59`가 미체결 취소·매도 전에 8초) | 대기 없음 |
| 뉴스·공시 HTTP | 기본 스레드에서 (타임아웃 미설정 — §4-2) | `io-sched-1/2` 스레드 |
| 하트비트·미러 HTTP | 기본 스레드에서 최대 8초 | HTTP만 I/O 스레드, 판단·조립은 기본 스레드 |
| 매매 감시 직렬성 | 스레드 1개 | 스레드 1개(명시적으로 고정, 테스트로 잠금) |

## 1. 바뀐 파일

| 파일 | 변경 | 이유 |
|---|---|---|
| `src/main/java/com/trading/BackgroundAlertSender.java` | **신규(이동·일반화)** — `position` 패키지 전용 클래스를 `com.trading` 공개 클래스로. 전달 대상을 `Consumer<String>`으로, `send()`가 수락 여부(boolean) 반환, 일꾼 반납 금지(순서 보장), `close()`가 최대 3초 마저 보내고 끊기 | 텔레그램 전체가 재사용하도록 |
| `src/main/java/com/trading/position/BackgroundAlertSender.java` | **삭제** | 위로 이동 |
| `src/main/java/com/trading/TelegramNotifier.java` | 판정(토큰·장중 창)은 호출 스레드, HTTP는 `BackgroundAlertSender`(상한 100, `telegram-sender`). 테스트용 생성자(가짜 전송기) + `@PreDestroy` 정리 | 핵심 수정 |
| `src/main/java/com/trading/SchedulingConfig.java` | `taskScheduler`(풀 1, `scheduling-`) + `ioTaskScheduler`(풀 2, `io-sched-`) 빈. 이름 상수 2개 | 함정 차단 + I/O 풀 |
| `src/main/java/com/trading/research/NewsAggregatorService.java` | `aggregate`·`cleanOld`에 `scheduler = IO_SCHEDULER` + 주석 | I/O 풀로 이동 |
| `src/main/java/com/trading/research/DartDisclosureService.java` | `scheduledAggregate`에 `scheduler = IO_SCHEDULER` + 주석 | I/O 풀로 이동 |
| `src/main/java/com/trading/DeadmanHeartbeat.java` | 박동 내용은 기본 스레드에서 만들고 HTTP만 `@Qualifier(IO_SCHEDULER) Executor`로 위임 | §2.5 |
| `src/main/java/com/trading/mirror/SupabaseMirrorScheduler.java` | 스냅샷 조립은 기본 스레드, 업로드만 I/O 실행기로 위임 | §2.4 |
| `src/main/java/com/trading/position/EvidenceBasedPeakEquityCalibrator.java` | import 1줄 + `notifier` → `notifier::sendCritical` (`:100`) | 이동한 클래스 사용 |
| `src/test/java/com/trading/BackgroundAlertSenderTest.java` | **신규 7건** | 즉시 반환·순서·상한·종료·실패 삼킴 |
| `src/test/java/com/trading/TelegramNotifierTest.java` | **신규 6건** | 막힘·순서·장중 창 시점·토큰·실패·상한 |
| `src/test/java/com/trading/SchedulingConfigTest.java` | **신규 6건** (실제 스프링 컨테이너) | 스케줄러 배선 |
| `src/test/java/com/trading/ScheduledPlacementTest.java` | **신규 2건** (운영 `@Scheduled` 전수 스캔) | 배치 감사 |
| `src/test/java/com/trading/DeadmanHeartbeatTest.java` | 생성자 인자 추가(바로 실행 실행기) + **신규 2건** | 위임 검증 |
| `src/test/java/com/trading/mirror/SupabaseMirrorSchedulerTest.java` | 생성자 인자 추가(바로 실행 실행기) + **신규 2건** | 위임 검증 |

**건드리지 않은 것**: `RiskEngine`·`LiquidationService`·전략 흐름·인터페이스 시그니처 전부 무수정.
`application-paper.yml`·`RiskRuleNameResolver`·`static/js/diag-history.js`·`CLAUDE.md`·`BACKLOG.md` 무수정
(작업 시작 전부터 있던 다른 작업의 수정분 그대로). 같은 시각 다른 에이전트가 추가한
`StaleAccountBuyGuardWiringTest` 등도 손대지 않음.

## 2. 설계 결정과 근거

### 2.1 텔레그램 전송을 호출자에게서 떼어 냄 (설계 1)

- 위치를 `TelegramNotifier` 안으로 정했다 — `NotificationService.sendCritical`·`TelegramNotifier.send`의
  **모든 호출부가 코드 수정 없이** 비동기가 된다. 호출부 전수(정적 검색):
  `RiskMonitor:106,128,145` · `ShadowPortfolioReconciler:101,163` · `TradingEventListener:44,50`(체결 이벤트 —
  `FillPoller` 스레드에서 AFTER_COMMIT으로 불림) · `KisApiClient:368,386` · `LiquidationService:59,78,116,120,126,187,189` ·
  `KisIndexRegimeSource:203` · `DailyPnlRecorder:221` · `LiquidationDrillScheduler:83,93,101` · `TimeCutScheduler:254` ·
  `TradingController:121,190` · `EvidenceBasedPeakEquityCalibrator:134`(기동 시 교정) · `:212`(전고점 경보, 자체 대기열 경유).
- **장중 창 규칙은 그대로, 판정 시점은 "받은 시각"** — `TelegramNotifier:80-89`. 토큰 공란·장외면 대기열에도
  넣지 않고 로그만(기존 문구 그대로). 15:29:59에 받은 건 15:30을 넘겨 전달돼도 나간다
  (`TelegramNotifierTest.market_hours_gate_is_checked_when_the_message_arrives`로 고정).
- 전달 실패 로그는 기존과 같은 ERROR+스택(`TelegramNotifier:93-100`), 이제 `[telegram-sender]` 스레드에 찍힌다.
- 대기열 상한 100(`:38`) — 타임컷·청산 몰림(수~십수 건)보다 넉넉하고, 텔레그램이 죽어 한 건 8초일 때도
  메모리를 지키는 선. 넘치면 **본문째 WARN** 후 버림(`BackgroundAlertSender:83-87`).
- 종료 정리: `@PreDestroy`(`:103-105`) → 새 알림 거부, 받은 것은 최대 3초 마저 보냄, 그 뒤 진행 중 건은
  끊고(interrupt) 남은 건수 WARN(`BackgroundAlertSender:101-110`). 일꾼은 데몬이라 JVM 종료를 막지 않는다.
- **순서 보장 근거**: 일꾼 1개 고정 + 선입선출 대기열(`BackgroundAlertSender:65-71`). 옛 클래스는
  `allowCoreThreadTimeOut(true)`(한가하면 일꾼 반납)였는데, `ThreadPoolExecutor` 구조상 일꾼이 사라지는
  순간 동시에 들어온 두 건이 "새 일꾼의 첫 작업"과 "대기열"로 갈려 **뒤바뀔 수 있다** — 그래서 반납을 껐다
  (대가: 유휴 데몬 스레드 1개 상주). 이 경합은 결정적으로 재현할 수 없어 테스트가 아니라 구조로 막았다.

### 2.2 스케줄러 2개 + 치명적 함정 차단 (설계 2·3)

- `SchedulingConfig:43-51` — `taskScheduler`(풀 1, `scheduling-`), `ioTaskScheduler`(풀 2, `io-sched-`).
  두 빈 모두 `@Profile("!backtest")` 클래스 안(`:33-34`) → 백테스트에는 스케줄러도 `@Scheduled` 처리기도 없다.
  `@EnableScheduling` 위치 불변.
- 기본 선택 규칙은 Spring 6.1.14 바이트코드로 확인: `ScheduledAnnotationBeanPostProcessor.finishRegistration`이
  `TaskSchedulerRouter`를 쓰고, 그 `determineDefaultScheduler`가 "유일한 `TaskScheduler` 빈 → 여럿이면 이름
  `taskScheduler`"로 고른다. 이름 붙은 작업은 `BeanFactoryAnnotationUtils.qualifiedBeanOfType`(빈 이름/한정자).
- 부트 자동 스케줄러 후퇴 조건도 바이트코드로 확인: Boot 3.3.5 `TaskSchedulingConfigurations$TaskSchedulerConfiguration`
  = `@ConditionalOnBean(internalScheduledAnnotationProcessor)` + `@ConditionalOnMissingBean({TaskScheduler, ScheduledExecutorService})`.
- **함정 재현(Red-Green)** — `taskScheduler` 빈만 잠시 지우고 `SchedulingConfigTest` 실행 →
  5건 중 2건 실패: TaskScheduler 빈이 `{ioTaskScheduler}` 하나뿐, 이름 없는 감시 흉내 작업이
  **`["io-sched-2", "io-sched-1"]` 두 스레드에서 실행**됨. 복구 후 통과. 즉 지시서의 함정은 실재하고 테스트가 잡는다.
- 풀 크기 1은 코드에 고정 — `spring.task.scheduling.*` 속성은 이제 이 풀에 적용되지 않는다(누가 yml로 풀을
  키워 직렬성을 깨는 길을 막는 의도). 주석에 명시.

### 2.3 옮긴 작업 / 남긴 작업

규칙: **KIS를 부르거나 매매 상태(포지션·잔고 캐시·운전 모드 쓰기)를 건드리면 기본 스레드.**

| 작업 | 배치 | 이유 |
|---|---|---|
| `NewsAggregatorService.aggregate`(30분)·`cleanOld`(02:00) | **I/O 풀** | RSS HTTP + 뉴스 테이블만. 워치리스트는 DB 읽기. 메모리 공유 상태 없음 |
| `DartDisclosureService.scheduledAggregate`(30분) | **I/O 풀** | DART HTTP + 공시 테이블만. 유니버스(`TradingUniverseService.getActiveCodes` = DB 읽기)·워치리스트 읽기만. `corpMap`은 volatile, 이 메서드만 씀 |
| `DeadmanHeartbeat.ping`(5분) | 트리거 **기본** / HTTP **I/O** | §2.5 (지시와 다른 결정) |
| `SupabaseMirrorScheduler.push`(5분) | 조립 **기본** / 업로드 **I/O** | §2.4 |
| `TradingScheduler`·`RiskMonitor`·`StopLossMonitor`·`FillPoller`·`ShadowPortfolioTicker`·`ShadowPortfolioReconciler`·`TimeCutScheduler`(3)·`MaxHoldScheduler`·`LiquidationDrillScheduler`·`DailyPnlRecorder`(2)·`KisIndexRegimeSource`·`EvidenceBasedPeakEquityCalibrator.flushPendingAlert`·`MinuteCandleCollector`(2)·`RunStreakRecorder`·`MinuteCandleRetention`·`RiskBlockRetention` | **기본(풀 1)** | KIS 호출 또는 매매 상태 접근, 보존 정리 포함 — 지시 4번대로 직렬 유지 |

`ScheduledPlacementTest`가 운영 클래스 전체(프로필 무시)를 스캔해 "I/O로 지정된 것 = 정확히 위 3개,
나머지는 전부 이름 없음"을 고정한다. 손절 감시를 실수로 I/O에 올리면 여기서 걸린다.

I/O 풀이 2스레드라 **서로 다른 I/O 작업끼리는 동시에 돌 수 있다**(같은 메서드의 중복 실행은 스프링이 막음).
뉴스 수집과 02:00 정리가 겹칠 수 있는데, 둘 다 리포지토리 단위 트랜잭션이고 메모리 공유 상태가 없어 DB 수준
경합만 남는다(오래된 행 삭제 vs 새 행 삽입 — 충돌 없음). 정적 분석 결론이다.

### 2.4 미러 — KIS를 부르므로 "조립은 기본, 업로드만 I/O"

- `MirrorSnapshotAssembler.assemble():59`가 `PositionManager.snapshotAccount()`를 부르고,
  `KisPositionManager.snapshotAccount():62-80`은 캐시가 3초(`:39`)보다 낡으면 **KIS 잔고 API를 부르고(`:73`)
  잔고 캐시를 갱신(`:74`)**한다. 장중엔 `RiskMonitor`가 1초마다 채워 대개 캐시 적중이지만 보장은 아니다.
  → 조립을 I/O로 옮기면 KIS 동시 호출 + 캐시 동시 갱신 위험. 그래서 조립은 기본 스레드에 남기고
  느린 Supabase HTTP(최대 3+5초)만 넘겼다(`SupabaseMirrorScheduler:63-64`). 스냅샷은 불변 객체를 실행기로
  넘기므로 스레드 간 공개가 안전하다.

### 2.5 하트비트 — ⚠ 지시와 다른 결정 (리더 확인 요청)

지시서는 하트비트를 "옮길 작업" 예로 들었지만, **트리거는 기본 스레드에 남기고 HTTP만 I/O로 넘겼다**
(`DeadmanHeartbeat:72-97`). 이유:
- 데드맨 스위치의 존재 이유는 "죽은 시스템은 자기가 죽었다고 알릴 수 없다"(OPERATIONS §2)이고,
  경고 시 대응이 "MTS로 보유 확인 → **수동 손절선 예약**"(OPERATIONS:162)이다.
- 트리거까지 I/O로 옮기면 **기본 스레드(손절·청산 감시)가 멈춰도 박동은 계속 나간다** → 외부 감시가
  침묵을 못 본다 → 수동 손절이 가장 필요한 순간을 놓친다. 지금 구조는 박동이 "감시 스레드가 살아 있다"의
  증거이기도 했다.
- 트리거 비용은 5분마다 DB 조회 1회(`positionRepository.findAll`)라 무시할 만하고, 막히던 원인(HTTP 8초)은
  제거됐다. 지시의 목적(느린 I/O를 매매 스레드에서 제거)은 그대로 달성.
- 전부 옮기길 원하면: `ping()`에 `scheduler = SchedulingConfig.IO_SCHEDULER`를 붙이고 위임 대신 직접 전송하면 된다
  (`ScheduledPlacementTest`의 기대 목록도 함께 수정).

### 2.6 `BackgroundAlertSender` 일반화와 전고점 보정기의 이중 대기열

- 지시대로 기존 클래스를 재사용·일반화했다. 전고점 보정기는 자기 대기열(`peak-alert-sender`)을 그대로 둔다 —
  그 클래스의 테스트(`ShadowPortfolioTest.send_does_not_run_on_the_caller_thread`)가 "알림 구현체와 무관하게
  감시 스레드를 막지 않는다"를 계약으로 고정하고 있고, 지우면 계약이 `TelegramNotifier` 구현에 기대게 된다.
  대가는 전고점 경보만 두 번 갈아타는 것(무해).

### 2.7 부수효과 (확인함)

- `ThreadPoolTaskScheduler`는 `Executor`이기도 해서 Boot의 `applicationTaskExecutor`가 물러난다
  (Boot 3.3.5 `TaskExecutorConfigurations$TaskExecutorConfiguration` = `@ConditionalOnMissingBean(Executor.class)`,
  바이트코드 확인). 이 앱은 `@Async`·`@EnableAsync`·MVC 비동기 반환형(Callable/DeferredResult/SseEmitter/
  StreamingResponseBody/Mono/Flux)·WebSocket·`Executor` 주입이 **전부 0건**(정적 검색) → 영향 없음.
  `SchedulingConfig` 주석에 "나중에 @Async를 쓰려면 실행기를 따로 정의" 경고를 남겼다.
- `Executor` 빈이 2개가 됐으므로 이름표 없는 `Executor` 주입은 기동 실패한다 — 하트비트·미러는 `@Qualifier(IO_SCHEDULER)`.
  `SchedulingConfigTest.paper_heartbeat_and_mirror_receive_the_io_scheduler`가 paper 프로필 실제 생성자 주입을 검증
  (이름표를 지우면 `NoUniqueBeanDefinitionException ... found 2: taskScheduler,ioTaskScheduler`로 실패 — 두 클래스 각각 확인).

## 3. 검증 증거 (이번 세션에서 직접 실행)

```
[검증 증거 — Red]  (새 시그니처 + 옛 동작 껍데기: 동기 텔레그램, 옛 대기열, 스케줄러 빈 없음, 위임 무시)
명령: gradle test --console=plain --tests BackgroundAlertSenderTest --tests TelegramNotifierTest
      --tests SchedulingConfigTest --tests ScheduledPlacementTest --tests DeadmanHeartbeatTest
      --tests SupabaseMirrorSchedulerTest --tests ShadowPortfolioTest   (TRADING_BUILD_DIR=...-impl41)
종료 코드: 1
결과: 75개 중 60개 통과, 실패 15 — 전부 의도한 행동 실패(컴파일 오류 0):
  BackgroundAlertSender 5 (상한 초과 시 false · close가 마저 보냄 · close가 막힌 전달을 끊음 · 닫힌 뒤 false · 실패 후 다음 건)
  TelegramNotifier 2 (막힌 전송 중 즉시 반환 — 2초 선점 타임아웃 · 대기열 상한)
  SchedulingConfig 4 (빈 2개 · 기본 1스레드 직렬 · I/O 지정 · I/O 막힘 격리 — ioTaskScheduler 없음)
  ScheduledPlacement 2 (뉴스·공시가 I/O 아님) · DeadmanHeartbeat 1 · SupabaseMirrorScheduler 1 (위임 안 함)
  ShadowPortfolioTest(이동한 대기열 사용) 전부 통과

[검증 증거 — Green]  (같은 7개 클래스)
종료 코드: 0
결과: 75개 중 75개 통과, 실패 0 · 오류 0

[검증 증거 — 함정 Red-Green]  taskScheduler 빈만 제거 → SchedulingConfigTest
종료 코드: 1 · 5개 중 2개 실패 (빈이 {ioTaskScheduler}뿐 · 감시 흉내 작업이 ["io-sched-2","io-sched-1"]에서 실행)
→ 복구 후 통과

[검증 증거 — 이름표 Red-Green]  DeadmanHeartbeat / SupabaseMirrorScheduler의 @Qualifier 각각 제거 → SchedulingConfigTest
종료 코드: 1 · 각 1건 실패 (NoUniqueBeanDefinitionException: Executor found 2: taskScheduler,ioTaskScheduler)
→ 복구 후 6개 중 6개 통과

[검증 증거 — 전체]
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-impl41 LOGGING_FILE_NAME=.../test.log
      gradle test --console=plain   (실행 전 test-results/test 비움)
종료 코드: 0 (BUILD SUCCESSFUL in 28s)
결과: test-results XML 154개 합산 932개 중 932개 통과, 실패 0 · 오류 0 · 건너뜀 0
  = 기준 905 + 이번 신규 25(7+6+6+2+2+2) + 다른 에이전트가 세션 중 추가한 StaleAccountBuyGuardWiringTest 2
```

테스트 출력에서 확인한 실제 로그: `[telegram-sender] ERROR ... Telegram 알림 전송 실패: boom`(전송이 전용 스레드에서 남),
`[telegram-sender] 알림을 버렸다(대기열 가득 참): 대기 101`~`105`(상한 100 + 진행 중 1),
`[test-sender] 종료 대기 200ms 안에 다 보내지 못했다 — 진행 중 1건을 끊고 대기 1건을 버린다`.

## 4. 남은 위험 · 미확인

1. **운영 미반영·런타임 미관측.** 실행 중인 앱은 옛 코드다 — 재기동해야 적용된다. 재기동 후 확인할 것:
   감시 로그는 계속 `[scheduling-1]`, 뉴스·공시 로그는 `[io-sched-1|2]`, 텔레그램 실패 로그는 `[telegram-sender]`.
   실제 지연 감소폭(8초 → 0)은 측정하지 않았다 — 20_audit M-2의 "풀 1" 전제는 이번 배선 테스트로 코드상 확정됐지만
   운영 JVM에서 본 것은 아니다.
2. **뉴스·공시 HTTP에 타임아웃이 없다 (기존 결함, 이번 범위 밖 — 별건 권고).** `NewsAggregatorService:62-64`,
   `HttpDartApiClient:45`가 요청 팩토리를 지정하지 않는다. 런타임 클래스패스에 Apache/Jetty 클라이언트가 없어
   (`gradle dependencies` 확인) Spring 6.1.14 `RestClient`는 JDK 클라이언트를 고르고, 연결·읽기 타임아웃이 설정되지
   않는다(바이트코드 확인 — 정적 분석). **전에는 피드 하나가 응답을 안 주면 손절 감시 스레드가 무기한 멈출 수 있었다**
   — 이번 이동으로 그 위험은 I/O 스레드로 격리됐다. 남은 것: 두 피드가 동시에 걸리면 I/O 2스레드가 다 묶여
   하트비트 HTTP·미러 업로드가 밀린다 → 외부 데드맨이 "침묵" 경고(매매는 정상인데 경보). 근본 처방은 두 클라이언트에
   connect/read 타임아웃(예: 3+10초)을 주는 것 — 3줄 수정이지만 지시 범위 밖이라 하지 않았다.
3. **"호출 ≠ 전달"은 그대로이고 경로가 하나 늘었다.** `sendCritical`이 돌아와도 전달 보장은 없다(15_audit M-2 미해소).
   새로 생긴 경우: 대기열 가득 참/종료 중 → 본문째 WARN 후 버림. `DailyPnlRecorder.sendAndMark`의 `notifiedAt`은
   이제 "대기열에 넣었다"를 뜻한다 — 그 주석이 이미 "전달이 아니라 호출"이라고 적어 둬 의미는 어긋나지 않는다.
   또 텔레그램이 느리면 알림이 사건보다 늦게 도착할 수 있다(전엔 같은 지연이 감시를 멈추게 했다).
4. **순서는 대기열 단위.** 전고점 경보는 `peak-alert-sender` → `telegram-sender` 두 번 갈아타므로, 다른 알림과의
   상대 순서는 "`TelegramNotifier`에 도착한 순서"다(사건 시각 순서와 수 ms 다를 수 있음).
5. **종료 순서는 추론만 했다.** 스프링이 의존 역순으로 파괴하므로 `TelegramNotifier`는 의존자들(보정기 등) 뒤에
   닫힌다고 봤다. 그 뒤 들어온 알림은 "이미 닫힘" WARN으로 남는다. 실제 앱 종료로 관측하지 않았다.
6. 문서 미갱신(범위 밖): `BACKLOG.md` [2026-09-21] 항목의 해소 표기, `DailyPnlRecorder:91-92` 주석의
   "스케줄 스레드 1개를 @Scheduled 14개가 나눠 쓰는"(개수가 낡음 — 기본 스레드 공유 자체는 여전히 사실).
   리더가 정리 여부를 판단할 것.
7. `ShadowPortfolioTest` 등에서 보정기 인스턴스마다 유휴 데몬 스레드가 남는다(일꾼 반납을 껐기 때문). 테스트 JVM 한정,
   전체 실행 시간 변화 없음(28~29초).
