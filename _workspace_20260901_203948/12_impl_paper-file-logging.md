# 12_impl — 모의투자 앱 파일 로그 추가

**날짜**: 2026-08-29 (토)
**분류**: 릴리즈 체크리스트 "검증 | 로그/알림으로 매매 내역 사후 확인 가능" 항목의 근거 보강
**요청**: "로그 파일 남기는 것도 고쳐줘"

## 문제

앱이 로그를 **콘솔에만** 출력하고 파일로 남기지 않았다. `logging:` 블록에 레벨만 있고
`logging.file.*` 설정이 없었으며 `logback-spring.xml`도 없었다.

실제 피해가 확인됐다 — 2026-08-27 사고 조사 시:

- 앱이 12:27:54에 기동해 12:28~12:36에 주문 4건을 낸 뒤 13:08 PC 재시작으로 죽었다.
- `RunStreakRecorder`가 그날을 연속 가동일 1일로 기록했는데, 규칙상 개장(09:00) 이후
  기동한 날은 `judgeStreak()`이 0을 반환해야 한다 (`upSinceOpen == false`).
- 콘솔 창이 재시작으로 닫혀 **`appStartedAt` 값도, 15:25 크론이 언제 발화했는지도
  확인할 수 없었다.** DB·윈도우 이벤트 로그·H2 잠금 파일 타임스탬프를 전부 대조했으나
  원인 규명에 실패했다.

## 변경

`src/main/resources/application-paper.yml` 끝에 블록 1개 추가. **코드 변경 없음.**

```yaml
logging:
  file:
    name: logs/paper.log
  logback:
    rollingpolicy:
      file-name-pattern: logs/paper-%d{yyyy-MM-dd}.%i.log.gz
      max-file-size: 20MB
      max-history: 60
      total-size-cap: 500MB
```

### 설계 판단

| 판단 | 이유 |
|---|---|
| `logback-spring.xml` 대신 yml 속성 | Spring Boot 3.3.5 기본 Logback으로 충분. 새 파일·새 의존성 없음 |
| `application.yml`(공용) 대신 `application-paper.yml` | 백테스트 결정성과 테스트 실행 속도에 영향을 주지 않기 위해. 문제는 paper 앱 한정 |
| 상대 경로 `logs/` | 절대 경로 하드코딩 회피. 기존 `logs/backtest/`와 같은 위치. `.gitignore`에 `logs/` 이미 등록됨 |
| 로그 레벨 유지 (INFO) | 범위 밖 변경 회피. `RunStreakRecorder`·`ShadowPortfolioReconciler` 등 사고 조사에 필요한 줄은 전부 INFO/WARN |

## 검증 (2026-08-29 10:44~10:46 실행)

앱을 실제로 재시작해 확인했다. 토요일이라 매매 영향 없음, 월요일 개장 전 기동 조건도 유지된다
(`appStartedAt`=08-29 ≤ 08-31 09:00).

| 확인 | 결과 |
|---|---|
| 재시작 전 `logs/paper.log` | 없음 |
| 재시작 후 파일 생성 | ✅ 10:44:49 생성, 10:45 기준 115,969바이트 |
| 앱 정상 기동 | ✅ `Started TradingApplication in 10.892 seconds` |
| 기동 재동기화 기록됨 | ✅ `[Reconciler] 브로커 대조 완료 — 불일치 없음` / `기동 재동기화 완료 — 자동 가동(RUNNING)` |
| Logback 오류 | 없음 (rollingpolicy 속성 전부 수용됨) |
| 대시보드 | http 200 |
| 운전 상태 | RUNNING |
| 연속 가동일 회귀 없음 | 재시작 전후 모두 `streakDays:2, lastRecordedDate:2026-08-28` |
| 장외 증가 속도 | 30초간 0바이트 (기동 시 116KB는 1회성 버스트) |

**부수 확인**: `[Reconciler] 브로커 대조 완료 — 불일치 없음` 로그로, 그동안 HTTP 조회만으로는
단정할 수 없었던 "브로커 잔고 조회가 실제로 성공하고 있으며 보유 0건이 브로커 기준으로도
맞다"가 처음으로 증거와 함께 확인됐다.

## 실행하지 않은 것

- **Gradle 테스트 스위트 미실행.** Java 소스를 바꾸지 않았고 `application-paper.yml`은
  테스트가 로드하지 않는다. 또한 실행 중인 `bootRun`과 `TRADING_BUILD_DIR`을 공유하므로
  동시 Gradle 실행이 가동 중인 앱을 방해할 위험이 있어 피했다.
  이 변경에 대한 더 강한 증거는 **Spring 컨텍스트 전체 기동 성공**이며 위 표에 있다.
- **장중 로그 증가량 미측정** — 토요일이라 매매 루프가 돌지 않는다. 월요일에 확인 가능.
  다만 20MB 회전 + 총 500MB 상한이 걸려 있어 증가량과 무관하게 디스크는 한계가 있다.

## 남은 것

- 08-27 연속 가동일 이상(0이어야 할 날이 1로 기록됨)은 **여전히 원인 미규명**이다.
  이번 변경은 같은 일이 다시 일어났을 때 추적 가능하게 만들 뿐, 지난 건을 설명하지 못한다.
