# 42_audit — 감시 스케줄러 I/O 격리 감사

> 2026-10-10(토) · risk-auditor(사용량 한도로 한 번 멈췄다가 재개) · 코드 수정 0 · gradle·H2·실행 중 앱 접근 0.
> Spring 6.1.14 jar 바이트코드(javap)와 운영 로그로 실제 동작을 확인했다. 리더가 보고 요지를 이 경로에 보존했다.

## 0. 판정 — CRITICAL 0 · HIGH 0 · MEDIUM 2 · LOW 6 → 커밋 가능

운영 반영 조건(커밋 조건 아님): 재기동은 사람이 지켜볼 수 있는 주말에 하고 "재기동 후 확인" 1~3을 바로 본다. 첫 기동을 월요일
08:30 자동 기동에 맡기지 않는다 — 전체 앱 기동을 증명하는 테스트가 없다(`@SpringBootTest` 0건).

쉬운 요약: 경비원(손절·청산 감시)에게서 편지 심부름(텔레그램)과 신문 수거(뉴스·공시)를 떼어 낸 공사는 제대로 됐다. 다만 신문 배달원
두 명이 "대답 없는 집" 앞에서 무한정 기다릴 수 있다(시간 제한 없음). 둘 다 묶이면 "살아 있다" 신호가 늦어져 가짜 비상벨이 울릴 수 있다.

## 1. 발견 항목

| 심각도 | 항목 | 위치 | 내용 |
|---|---|---|---|
| MEDIUM | M-1 I/O 풀 공유 + 뉴스·DART 무기한 대기 → 하트비트 지연, 데드맨 거짓 경보 | `NewsAggregatorService.java:62-64`, `HttpDartApiClient.java:45`, `SchedulingConfig.java:48-51`, `DeadmanHeartbeat.java:80`, `SupabaseMirrorScheduler.java:64` | 두 피드가 동시에 매달리면 2스레드가 묶여 하트비트·미러 HTTP가 무기한 밀린다 → 10분 뒤 외부 경보(매매는 정상). 절전에서 깨면 30분 `fixedRate` 작업이 밀린 횟수만큼 몰아 돌아 그동안 하트비트가 밀린다. 근거: `DefaultRestClientBuilder.initRequestFactory` 순서 HttpComponents→Jetty→**JDK**→Simple, 의존성에 Apache/Jetty 없음 → JDK 클라이언트, 연결 타임아웃 없음·`readTimeout` null. 거짓 경보면 OPERATIONS §2의 "MTS 수동 손절 예약"과 앱 손절이 겹친다 |
| MEDIUM | M-2 텔레그램 봇 토큰이 로컬 로그에 기록됨(원래 있던 결함) | `TelegramNotifier.java:98`(`log.error(..., text, e)`), 실제 1줄 `logs/paper-2026-09-08.3.log.gz` | 연결·응답 대기 실패 시 예외 메시지에 URL 경로 `/bot<토큰>/sendMessage`가 그대로 찍힌다(`DefaultRestClient`가 "I/O error on … request for "<URL의 ? 앞까지>""를 만든다 → 경로 속 토큰은 남는다). git에는 없다(`logs/`는 .gitignore). 권고: 예외 메시지에서 토큰을 가리고 스택 출력을 뺀다. 토큰 교체(BotFather revoke)는 사람 판단 |
| LOW | L-1 종료 시 유실 흔적 | `BackgroundAlertSender.java:108-110`, `LiquidationService.java:185-191` | 3초 넘겨 버린 알림은 건수만 남는다. 청산 보고는 텔레그램에만 있다. interrupt가 `HttpURLConnection` 읽기를 끊지 못한다(데몬이라 종료는 유한). 전고점 교정기 `close()`가 이제 최대 3초 막힌다 |
| LOW | L-2 `notifiedAt`이 대기열 거부도 "보냄"으로 | `DailyPnlRecorder.java:221-223`, `TelegramNotifier.java:89` | `send()`의 성공 여부를 버린다 → 대기열 가득 참·종료 중 유실된 날도 도장이 찍혀 이월되지 않는다(가능성 낮음). 15_audit M-2(호출≠전달) 미해소, 유실 경로 2개 추가 |
| LOW | L-3 두 대기열 사이 순서·15:30 경계 | `EvidenceBasedPeakEquityCalibrator.java:205-214` | 전고점 경보는 대기열을 두 번 갈아타 수 ms 늦게 줄 설 수 있다. 15:30 경계에서 버려지면 보류함이 이미 비어 재전송 안 됨(원래 성질) |
| LOW | L-4 적체 해소 때 몰아치기·역전 | `DeadmanHeartbeat.java:80`, `SupabaseMirrorScheduler.java:64` | 낡은 박동이 한꺼번에 나간다. 미러 업로드 역전 가능(표시 전용). 텔레그램이 죽어 있으면 알림이 최대 약 13분 늦을 수 있다(100건×8초) |
| LOW | L-5 문서 낡음 | `DailyPnlRecorder.java:91-92`("14개" — 실제 기본 22 + I/O 3), BACKLOG [2026-09-21], `ScheduledPlacementTest.java:27-28` | "KIS 호출은 기본 스레드에서만"은 앱 전체 불변식이 아니다(원래부터 청산 스레드·지수 갱신 스레드·Tomcat이 KIS를 부른다). 이번 변경이 새 KIS 호출 스레드를 만들지는 않았다 |
| LOW | L-6 범위 위생 | 미추적 `auto-start-paper.bat` | 무관한 작업물 — `git add -A` 금지 |

## 2. 점검 항목별 판정
1. **스케줄러 배선 — 정상.** 이름 없는 `@Scheduled` 22개 전부 `taskScheduler`(1스레드). Spring 6.1.14 `TaskSchedulerRouter.determineDefaultScheduler`:
   유일(또는 @Primary) TaskScheduler 빈 → 여럿이면 이름 "taskScheduler" → `ScheduledExecutorService`. `ioTaskScheduler`만 남으면 그것이 기본이 된다 —
   함정은 실재하고 테스트(`SchedulingConfigTest:49-64,66-77`, `ScheduledPlacementTest:79-82`)가 잡는다(구현자 Red 시연). 다른 곳에 @Primary·
   SchedulingConfigurer·TaskScheduler 빈 0건, yml `spring.task.scheduling.*` 없음. 백테스트 격리 그대로(`@EnableScheduling`·`@Profile("!backtest")` 불변,
   `SchedulingConfigTest:104-114` 고정).
2. **KIS·매매 상태가 기본 스레드 밖으로 — 안 나감.** 뉴스(RSS + 뉴스·워치리스트 리포지토리), DART(opendart + 공시 리포지토리 +
   `TradingUniverseService.getActiveCodes()` 순수 DB 읽기). 공유 분류기는 `static final` 불변 목록. 미러: 조립(KIS 호출 가능)은 기본 스레드, 넘기는
   스냅샷은 불변 record·불변 리스트, `Executor.execute`가 happens-before 보장. 하트비트: 내용은 기본 스레드, HTTP만 넘김 — 타이머를 기본 스레드에
   남긴 판단에 동의(감시 스레드가 얼면 박동도 멈춘다).
3. **텔레그램 비동기 — 순서 의존 없음.** 호출부 26곳 전수, 구현체 1개, 결과를 기다리는 코드 없음. 청산 "예고→개시→완수"는 같은 FIFO라 순서 보존.
   사라진 대기는 모드 전환과 미체결 취소 사이 — 신규 매수는 `TradingScheduler:95`·`OrderEngine:79-88`에서 모드를 두 번 보므로 끼어드는 경합 없음.
   달라진 점: "강제청산 개시" 알림이 첫 매도보다 늦게 도착할 수 있다(정보성). 장중 판정은 호출자 스레드·받은 시각(예전과 같은 시점).
4. **뉴스·DART 타임아웃 — M-1.** 이 둘만 타임아웃이 없다(KIS·텔레그램·하트비트·미러는 `SimpleClientHttpRequestFactory` + 타임아웃). 이번 변경 전엔
   같은 매달림이 손절 감시 스레드를 무기한 멈췄다 → 분명한 개선이고 남은 위험은 "가짜 경보". 권고:
   ① 뉴스 연결 3,000ms / 읽기 10,000ms, DART 연결 5,000ms / 읽기 30,000ms(기존 패턴) ② 하트비트 HTTP를 피드 작업과 다른 전용 실행기로
   (타임아웃이 있어도 DART 전면 장애 때 한 주기가 (1+약 20종목)×35초 ≈ 12분까지 스레드를 잡을 수 있다) ③ (선택) 뉴스·DART를 `fixedRate`→`fixedDelay`.
5. **흐름·프로필·비밀키·크기.** Strategy·RiskEngine·LiquidationService·인터페이스 수정 없음. 메시지 본문 WARN은 민감정보 노출 아님(원래 텔레그램으로 나간다).
   실제 노출은 토큰 → M-2. 변경 파일 최대 269줄, 함수 50줄 미만.
6. **패키지 이동·일꾼 반납 금지.** 옛 패키지 참조 0건. 유휴 일꾼 반납을 끈 결정 타당(반납·재생성 경계에서 순서가 바뀔 수 있는 `ThreadPoolExecutor` 경로를 닫는다).

배경 결함: 20_audit M-2 · BACKLOG [2026-09-21] **해소**(남은 동기 HTTP는 타임아웃이 있는 KIS뿐). 15_audit M-2(호출≠전달) 미해소(L-2).

독립 검증: 결과 XML 합산 932/932 통과(신규·변경 스위트 포함, 11:29~11:30 KST 실행분). 운영 앱(PID 22036)은 아직 옛 코드.

## 3. 재기동 후 확인
1. 기동: `APPLICATION FAILED TO START`·`NoUniqueBeanDefinitionException`·`No qualifying bean` 없음, 대시보드 응답(Executor 빈이 2개가 되고 부트 `applicationTaskExecutor`가 빠진다)
2. 스레드: `RiskMonitor`·`StopLossMonitor`·`TradingScheduler`·`FillPoller`·`TimeCutScheduler` 로그는 `[scheduling-1]`만, `scheduling-2`는 한 번도 없어야 함, 뉴스·DART는 `[io-sched-1|2]`
3. 월요일 장중 텔레그램 수신 정상, 실패면 `[telegram-sender]` ERROR, "알림을 버렸다" WARN 0건
4. 정시성: 15:15/15:20·24/15:25/15:28/15:29 크론이 수 초 안에 발화(예전엔 +321초 밀린 날이 있었다 — `DailyPnlRecorder.java:94`)
5. 하트비트(설정돼 있다면) 5분 간격 핑, 미러 `updated_at` 5분 갱신
6. 다음 종료 때 `[telegram-sender] 종료 대기 … 버린다` WARN 없음

## 4. 감사하지 못한 것
전체 paper 앱 컨텍스트 기동 · 운영 JVM의 실제 스레드 배치와 지연 감소폭 · 테스트 직접 실행(지시) · 종료 순서·`@PreDestroy` 대기(추론) · `HEARTBEAT_URL` 설정 여부.

## 5. 범위 밖 관찰
- 10-05 03:02:45 → 10-09 21:04:50 약 90시간 로그 공백(PC 절전 — 사용자 확인: 배터리 부족).
- 깨어난 직후 `fixedRate` 몰아치기: 뉴스 181회(21:04:50~21:14:14, 약 9.4분). 옛 구조에선 그동안 손절·청산 감시가 밀렸고, 새 구조에선 하트비트가 밀린다.
  기본 스레드의 `ShadowPortfolioTicker`(1초)·Reconciler(10분)·하트비트(5분)도 같은 성질 — 별건 검토.
- 뉴스 4개 피드가 매 주기 전부 실패(404 두 곳, edaily TLS1.0 거부, heraldkorea I/O 오류). DART는 10-09 몰아치기 중 180회 모두 "상장사 코드 매핑 적재: 0건".
  표시 전용 기능의 결함, 매매 무관.

## 6. 리더 후속 조치
- 커밋: ③ 변경을 경로 지정으로 커밋(`auto-start-paper.bat` 제외).
- M-1·M-2: ① 구현이 같은 작업 트리에서 진행 중이라(시험 충돌 방지) ① 완료 직후 리더가 처리 — 뉴스·DART 타임아웃, 하트비트 전용 실행기,
  뉴스·DART `fixedDelay` 전환, 텔레그램 실패 로그의 토큰 가림. 토큰 교체 여부와 9월 8일 로그 파일 처리는 사용자에게 묻는다.
- L-5: 문서(BACKLOG·주석)는 마지막에 일괄 갱신.
