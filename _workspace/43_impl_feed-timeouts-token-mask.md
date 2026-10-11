# 43_impl — 뉴스·DART 시간 제한 + 하트비트 전용 전송 스레드 + 텔레그램 토큰 가림 (42_audit M-1·M-2 후속)

> 2026-10-10(토) 19:2x~ KST · 리더 구현 · 감사 `44_audit_feed-timeouts-token-mask.md`

## 0. 왜

- **M-1**: 뉴스(`NewsAggregatorService`)·DART(`HttpDartApiClient`)의 `RestClient`에 요청 팩토리가 없어 JDK 기본 클라이언트(연결·읽기
  제한 없음)가 붙었다. ③(커밋 b2ee085)으로 두 작업이 I/O 풀(2스레드)로 옮겨가 손절 감시는 더 막지 않지만, 두 피드가 동시에 매달리면
  같은 풀의 하트비트 전송이 밀려 **매매는 멀쩡한데 데드맨 경보가 울린다**. 또 30분 `fixedRate`라 PC가 절전에서 깨면 밀린 횟수만큼
  몰아서 돌았다(10-09 실측 뉴스 181회/약 9.4분) — 그동안에도 하트비트가 밀린다.
- **M-2**: 텔레그램 전송 실패 로그가 `log.error(..., text, e)`로 예외를 통째 찍어, 예외 메시지 속 요청 URL `/bot<토큰>/sendMessage`의
  **봇 토큰이 로컬 로그에 남았다**(실제 1건 `logs/paper-2026-09-08.3.log.gz`). git에는 없다(`logs/` 무시).

## 1. 바뀐 것

| 파일 | 변경 |
|---|---|
| 신규 `src/main/java/com/trading/HttpTimeouts.java` | 연결·읽기 제한을 건 `SimpleClientHttpRequestFactory`를 만드는 작은 도우미 |
| `research/NewsAggregatorService.java` | 연결 3초 · 읽기 10초, 수집 주기 `fixedRate` → `fixedDelay`(30분) |
| `research/HttpDartApiClient.java` | 연결 5초 · 읽기 30초(corpCode ZIP 기준 — 읽기 제한은 바이트 사이 침묵 기준이라 흐르는 다운로드는 끊지 않는다) |
| `research/DartDisclosureService.java` | 수집 주기 `fixedRate` → `fixedDelay`(30분, 첫 실행 기동 2분 뒤 그대로) |
| `DeadmanHeartbeat.java` | HTTP를 I/O 풀 대신 **전용 전송 스레드**(heartbeat-sender, 1스레드·대기 2건·가장 오래된 박동부터 버림)로. 박동 트리거는 그대로 기본 스레드(데드맨 의미 유지). 종료 시 자기 스레드만 정리. `@Qualifier(ioTaskScheduler)` 주입 제거 |
| `TelegramNotifier.java` | 실패 로그에서 예외·원인 사슬을 한 줄로 만들고 `bot<숫자>:<비밀>`을 `bot***`로 가린 뒤 남긴다. 스택은 남기지 않는다(원인 메시지에도 같은 URL이 있다) |
| 테스트 | 신규 `HttpTimeoutsTest`(대답 없는 서버 — 데몬 스레드로 호출하고 3초만 기다린다), 신규 `research/FeedScheduleTest`(두 주기가 fixedDelay), `TelegramNotifierTest` +1(실패 로그 캡처 — 토큰 없음·`bot***` 있음), `SchedulingConfigTest` 1건 수정(하트비트 실행기가 I/O 풀이 아님), `DeadmanHeartbeatTest` 테스트 이름 1건 정정 |

## 2. 검증 증거

```
[검증 증거 — Red]  (HttpTimeouts는 제한을 걸지 않는 껍데기로 두고 실행)
명령: gradle test --tests HttpTimeoutsTest --tests FeedScheduleTest --tests SchedulingConfigTest --tests TelegramNotifierTest --console=plain
      (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-impl39)
종료 코드: 1
결과: 16개 중 11개 통과, 실패 5 — 정확히 예상한 5건(시간 제한 없음 · 뉴스/DART fixedRate 2 · 하트비트가 I/O 풀 · 실패 로그에 토큰)

[검증 증거 — Green]
명령: gradle test --console=plain (전체, 결과 폴더 비우고)
종료 코드: 0
결과: 1010개 중 1010개 통과, 실패 0 · 오류 0 · 건너뜀 0 (결과 XML 166개 — 커밋 전 ① 슬리브 변경 포함 작업 트리 기준)
```

## 3. 사고 기록 — 시험이 멈춘 일

첫 Red 실행이 580초 동안 끝나지 않아 `timeout`으로 끊겼다(종료 코드 124). 원인은 시험 자체였다: 대답 없는 서버가 연결을 **하나만**
받게 짜여 있었는데, 제한이 없는 껍데기 상태에서 JDK 클라이언트가 끊긴 GET을 **다시 시도**하자 두 번째 연결이 대기열에 걸려 영원히
기다렸고, 소켓 읽기는 JUnit `@Timeout`(같은 스레드)으로 끊기지 않는다. 고친 시험은 연결을 계속 받아 두고, 호출을 데몬 스레드에서 해서
3초만 기다린다 — 제한이 빠지면 멈추지 않고 "실패"로 끝난다. 남은 테스트 JVM은 없었다(확인).

## 4. 남은 것

- 토큰 교체(BotFather revoke)와 9월 8일 로그 파일 처리(토큰이 든 1줄)는 사용자 결정.
- 하트비트 실패 로그(`[Heartbeat] 전송 실패 — {메시지}`)에도 ping URL(healthchecks UUID)이 남을 수 있다 — URL을 설정한 뒤에만 해당, 위험 낮음. 이번엔 그대로 둠.
- 운영 반영은 재기동 후.
