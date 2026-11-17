# 31_impl — 장외 가드(H-1 b·c) + 지수 부분응답(M-1) · 지수 조회 창(M-2) · 최고점 저장 실패(M-3)

- 작성: trading-implementer · 2026-10-01 (목) 09:28 · 브랜치 `backtest/regime-filter-and-validation` (커밋 안 함)
- 입력: `_workspace/30_audit_trend-sleeve-switch.md` H-1 · M-1 · M-2 · M-3

## 삭제한 파일: 없음

이번 작업에서 **삭제한 파일은 0개**다. 새 파일만 추가했다(main 1 · test 3).
→ 배포 전 빌드 경로에서 지울 옛 `.class`는 이번 변경 몫으로는 없다(아침 `NoOpIndexRegimeSource` 건은 리더가 이미 처리).
**앱은 끄거나 재시작하지 않았다.** 테스트는 별도 빌드 경로 `C:/Users/SAMSUNG/auto_trading-build-impl31`,
테스트 로그는 스크래치(`LOGGING_FILE_NAME`)로 돌렸다 — 실행 후 `logs/paper.log`의 `Test worker` 줄 0건.

## 1. 바꾼 파일

| 파일 | 무엇 / 왜 |
|---|---|
| `risk/StopLossMonitor.java` | H-1(c) `checkStops()` 맨 앞(설정 확인 다음)에 `if (!marketCalendar.isDuringMarketHoursNow()) return;` — `RiskMonitor:78`과 같은 판정. 생성자 끝에 `MarketCalendarService` 추가. M-3 `positionRepository.save`를 `persistTrailingHigh()`로 빼 try/catch — 실패 시 WARN 남기고 메모리 값으로 판정 계속. 장중 판정 로직(익절·트레일·ATR 순서와 조건)은 그대로 |
| `position/ShadowPortfolio.java` | H-1(b) `tick()`에서 `@Scheduled`만 떼고 본문은 **한 글자도 안 바꿨다**(+설명 주석). 안 쓰게 된 import 1줄 제거 |
| `position/ShadowPortfolioTicker.java` **(신규, 38줄)** | H-1(b) paper 전용 `@Scheduled(fixedRate=1000)` — 장중일 때만 `shadowPortfolio.tick()` 호출 |
| `risk/KisIndexRegimeSource.java` | M-1 `readOrFail()` — 비었거나 `IndexTrendCalculator.read()`가 빈 값(MA 표본 부족)이면 예외 → 기존 `onFailure` 경로(lastSuccess 보존 · 30분 뒤 재시도 · 장중 텔레그램 하루 1회). M-2 상수 `REFRESH_WINDOW_START=08:30` → `REFRESH_DELAY_AFTER_OPEN=1분`, 창 시작 = `calendar.openTime(오늘)+1분`(평일 09:01). 클래스 주석 갱신 |
| 테스트 `StopLossMonitorTest` · `StopLossMonitorTrailingPersistTest` · `KisIndexRegimeSourceTest` | 생성자 인자 추가(고정 장중 시계 — `RiskMonitorTest`와 같은 방식), 조회 창 이동에 맞춘 시각(08:40→09:10, 금 08:31→09:01), M-3 테스트 1개 추가 |
| 테스트 신규 3개 | `StopLossMonitorAfterHoursTest`(5) · `ShadowPortfolioTickerTest`(3) · `KisIndexRegimeSourceRefreshGuardTest`(4) |

무변경 확인(`git diff --stat` 빈 결과): `com.trading.backtest`(main·test) · `static` · `dashboard` · `KisApiClient` ·
`KisErrorBodySnippet` · `KisPositionManager` · `order/*`(CancelRetryGate·FillProcessor·KisOrderCancelClient·OrderCancelClient 포함) ·
`KisBrokerageApiClient` · `RiskEngine` · `LiquidationService` · `LiquidationPhase`. 리스크 룰 판정 무변경. 인터페이스 시그니처 무변경.
파일 길이 최대 274줄(테스트) / main 최대 213줄.

## 2. 각 지적을 어떻게 막았나

- **H-1(c) 장외 매도 폭주** — 장외·휴장일에는 `checkStops()`가 잔고도 안 부르고 바로 끝난다 → 주문·DB 쓰기 0건.
  **잊지 않는다**: 손절선(`stopPrice`)·최고점(`trailing_high`)은 Position(DB)에 남아 있으므로 다음 개장 첫 틱(09:00:00 —
  `isDuringMarketHours`는 개장·마감 시각을 포함한다)에 장중과 똑같은 판정이 돈다. 테스트로 고정: 밤새 5개 시각 틱 → 0건 → 금 09:00 → 매도 1건(ATR·트레일 각각).
- **H-1(b) 전고점 장외 오염** — 스케줄 진입점을 새 `ShadowPortfolioTicker`로 옮기고 장 시간 판정을 붙였다.
  가드를 `tick()` 안에 넣지 않은 이유: `BacktestRunner.closeDay`가 봉마다 시계를 15:30에 두고 `tick()`을 **직접** 부른다.
  `tick()`을 그대로 두면 백테스트 경로가 캘린더 데이터(과거 연도 특수일 등)와 무관하게 바이트 단위로 보존된다. 백테스트는
  스케줄링 자체가 꺼져 있어(`SchedulingConfig @Profile("!backtest")`) 새 빈(paper 전용)이 없어도 된다.
- **M-1 지수 부분 응답** — 판정이 서지 않으면 성공이 아니다. `lastSuccess`를 덮지 않으므로 어제 판정이 살아 있고,
  `fetchedOn=오늘`이 안 찍히므로 30분 뒤 재조회한다. 실패 경로라 장중 텔레그램 1회가 나간다.
  (클라이언트는 최신→과거로 페이지를 넘기므로 부분 응답은 "최신 쪽 일부"다 — 표본이 N개 이상이면 MA 판정은 정확하므로 그건 성공으로 둔다.)
- **M-2 장전 조회** — 창 시작을 개장 1분 뒤로. 고정 09:01 대신 `openTime+1분`을 쓴 이유: 개장이 늦는 날(수능일 등, `market-calendar.yml`의
  특수일)에 고정 09:01이면 다시 "장전 조회"가 된다. 평일은 09:01로 리더 지시와 같다.
- **M-3 저장 실패** — 저장 예외를 삼키고(WARN) 메모리 값으로 트레일·ATR 판정을 계속한다. 다음 틱이 DB 값을 다시 읽어 또 올리므로 저장은 저절로 재시도된다.

## 3. 고정한 테스트

| 지시 항목 | 테스트 |
|---|---|
| 장외 + 손절선 아래 → 주문 0건 | `StopLossMonitorAfterHoursTest.after_hours_below_stop_places_no_order`(+DB 쓰기 0건), `holiday_places_no_order` |
| 장외에 손절선 아래로 마감 → 다음 날 09:00 첫 틱 매도 1건 | `closed_below_stop_sells_on_first_tick_next_open`, `closed_below_trail_sells_on_first_tick_next_open`(저장된 최고점으로) |
| 장중 → 기존과 동일하게 매도 | `in_hours_sells_as_before` + 기존 `StopLossMonitorTest` 12개 · 영속화 4개 그대로 통과 |
| 장외 + 더 높은 신선 총자산 → 전고점 불변 / 장중 → 갱신 | `ShadowPortfolioTickerTest.never_raises_peak_outside_market_hours`(실측 오염값 10,890,158 · 저장 0건 · 09:00 재개), `never_raises_peak_on_holiday`, `raises_peak_during_market_hours`(15:30 경계 포함) |
| 지수 부분 응답 → lastSuccess 보존 + 재시도 | `KisIndexRegimeSourceRefreshGuardTest.partial_response_keeps_last_verdict_and_retries`(알림 1회·30분 간격 포함), `partial_first_response_is_retried` |
| 지수 조회가 09:00 이전엔 안 나간다 | `never_fetches_before_open`(08:30·08:45·08:59:59·09:00·09:00:59 → 0회, 09:01 → 1회), `follows_late_open_days` |
| 저장 실패해도 ATR 손절이 그 틱에 판정 | `StopLossMonitorTrailingPersistTest.atr_stop_still_judged_when_high_save_fails` |

## 4. Red-Green

수정 5개를 동시에 되돌리고(서로 다른 동작이라 귀속이 겹치지 않는다) 관련 4개 클래스만 실행 → **17개 중 11개 실패, 전부 의도한 이유**:

| 되돌린 것 | 실패한 테스트 | 실패 메시지 |
|---|---|---|
| H-1(c) 가드 제거 | AfterHours 5개 중 4개(장중 테스트만 통과) | `NeverWantedButInvoked: kisOrderClient.sell` |
| H-1(b) 가드 제거 | Ticker 3개 중 2개(장중 테스트만 통과) | `expected 9900000.0 but was 1.0890158E7` / `expected 0.0 but was 1.04E7` |
| M-1 옛 동작(빈 응답만 실패) | 부분 응답 2개 | `Expecting value to be false but was true`(부분 응답을 성공 처리) |
| M-2 창 시작 08:30 | 창 2개 | `[시각 08:30] Expecting value to be false but was true` |
| M-3 try/catch 제거 | M-3 1개(기존 영속화 4개는 통과) | `Wanted but not invoked: kisOrderClient.sell("005930", 10)` |

백업본으로 복구 → `cmp`로 바이트 동일 확인 → 아래 최종 전체 실행.

## 5. 테스트 결과 (이번에 직접 실행)

```
[검증 증거 — 최종]
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-impl31 LOGGING_FILE_NAME=<scratch>/paper-test.log \
      /c/Users/SAMSUNG/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gvueggl8a5cioxuftxrik/gradle-9.2.0/bin/gradle test --console=plain
빌드 경로: C:/Users/SAMSUNG/auto_trading-build-impl31 (운영 앱이 읽는 auto_trading-build 아님)
종료 코드: 0 (BUILD SUCCESSFUL in 27s)
결과: 889개 중 889개 통과, 실패 0, 오류 0, 건너뜀 0  (기준선 876 + 신규 13)
운영 로그 오염: logs/paper.log "Test worker" 0줄
→ 판정: 통과
```

## 6. 배포 후 확인할 것

1. 기동 정상(새 빈 `ShadowPortfolioTicker` — 로그는 따로 안 찍는다).
2. **오늘 15:30 이후** `[ShadowPortfolio] peakEquity 경신` 줄 0건, `[StopLoss] … 트리거` 줄 0건, 장외 SELL FAILED 행 0건.
3. 내일 지수 판정 줄 `[IndexTrend] KOSPI 일봉 …`이 08:30대가 아니라 **09:01~09:02**에 찍히는지.
4. 내일 09:00 첫 틱: 밤새 손절선 아래였던 보유분이 있으면 09:00:0x에 `[StopLoss] StopLoss-ATR 트리거`.

## 7. 확인하지 못한 것 / 남긴 한계

- **paper 실기동 미확인**(재시작 금지). 배선은 같은 프로필의 기존 의존 관계(`RiskMonitor`가 이미 `MarketCalendarService`를 주입받음)로 판단했다.
- **백테스트 실행 안 함.** 근거는 구조다: `tick()` 본문 무변경 · 새 빈은 paper 전용 · `com.trading.backtest` diff 0. 889개 안의 백테스트 단위 테스트(`IndexTrendParityTest` 등)는 통과.
- **09:00 첫 틱의 시세**: 개장 직후 몇 초는 잔고 스냅샷(3초 캐시)이 장전 값(전일 종가)일 수 있다. 그 값이 손절선 아래면 바로 팔고,
  시가가 손절선 위로 갭 상승해 스냅샷이 그걸 보이면 팔지 않는다 — 장중 판정 로직 그대로다. 실제 KIS 응답으로는 재지 않았다.
- **매일 08:30 자동 기동이면 "재기동일"은 매일이다** → 매일 09:00~약 09:02 A동 신규 매수가 보류된다(첫 판정 전 fail-closed). 리더가 수용한 대가지만 빈도를 적어 둔다.
- **장외 잔고 호출 감소(부수 효과, 미측정)**: 장외에 1초마다 `snapshotAccount()`를 부르던 두 곳(StopLossMonitor·ShadowPortfolio)이 멈춘다. 다른 호출자는 감사하지 않았다.
- **범위 밖이라 안 고친 것(관찰만)**: ① 장중에 매도가 KIS에서 거부되면 `hasPendingSell`이 FAILED를 안 봐 1초마다 재시도한다(기존 동작 — 오늘 가드는 장외만 막는다).
  ② `OrderEngine`·`KisOrderClientImpl`에는 여전히 장 시간 가드가 없다(다른 매도 경로 — 타임컷·최대보유는 장중 스케줄이라 현재 무해).
  ③ `EvidenceBasedPeakEquityCalibrator`의 "보류함" 주석(:68-72)은 "tick()이 장 밖에도 돈다"고 적혀 있어 이제 낡았다(동작 영향 없음 — 보류함 코드는 그대로 둠).
  ④ 지수 픽스처 `kospi(...)`가 테스트 3곳에 복제돼 있다(이번에 3번째) — 공용 헬퍼 추출은 후속 정리 거리.
- 고치지 말라고 한 M-4 · M-5 · L-3 · L-5는 손대지 않았다.
