# 29_impl — 모의투자 A동(돈치안) 전환: 지수 필터 실데이터원 · 트레일링 최고점 영속화 · paper 설정

- 작성: trading-implementer · 2026-10-01 (목) · 브랜치 `backtest/regime-filter-and-validation` (커밋 안 함)
- 설계 정본: ADR-001(2026-08-07 개정 — A동 개시·자금 40%) · BACKTEST-DESIGN §14.4 · §15.6 · §15.7 D0
- **실행 중인 앱(PID 24856, 8080)은 끄거나 재시작하지 않았다.** 테스트는 별도 빌드 경로
  (`TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-impl29`)로 돌렸다 — 실행 중인 앱은
  `C:/Users/SAMSUNG/auto_trading-build/classes`에서 클래스를 읽고 있어, 같은 경로로 빌드하면 장중에
  그 파일을 덮어쓴다. 테스트 로그도 `LOGGING_FILE_NAME` 환경변수로 스크래치 폴더로 돌렸다
  (`@DataJpaTest`가 paper 프로필을 켜면서 운영 로그 `logs/paper.log`에 `[Test worker]` 줄을 섞는 기존 문제
  — 오늘 13:00·15:45 관측 작업 보호). 확인: 실행 후 `logs/paper.log`의 `Test worker` 줄 **0건**.

## 0. 결론

리더가 짚은 3곳(①②③)을 고쳤고, **4번째 막힌 곳을 추가로 발견해 고쳤다**: paper의 일봉 조회가
KIS 1회 호출(약 100행 상한)로 끝나서 **125봉이 필요한 돈치안은 paper에서 영원히 신호를 못 냈다**.
(같은 경로를 쓰는 이평돌파 EVENT 칸이 07-04~ 90일간 **매매 0건** — VB 28건·스캘핑 72건과 대조.)
이것을 안 고치면 A동을 켜도 한 번도 사지 않는다.

## 1. 바꾼/만든 파일

| 파일 | 줄 | 무엇 / 왜 |
|---|---|---|
| `risk/KisIndexRegimeSource.java` (신규) | 206 | ① paper 지수 데이터원. KOSPI 일봉 하루 1회·전용 스레드·실패 시 마지막 판정 유지 |
| `risk/IndexTrendCalculator.java` (신규) | 56 | ① 판정 순수 함수 — 백테스트 `belowTrend`와 같은 규칙(오늘 봉 제외, 같으면 '아래' 아님) |
| `risk/IndexTrendDataGateRule.java` (신규) | 45 | ① fail-closed 관문 — 필터 ON인데 판정 불가면 매수 거부. `@Profile("!backtest")`, `RiskRule`+`@Component`만 |
| `risk/NoOpIndexRegimeSource.java` (삭제) | -21 | ① 항상 empty를 주던 paper 데이터원. 새 데이터원과 빈이 겹치면 기동 실패라 삭제 |
| `risk/RiskRuleNameResolver.java` | +1 | ① 새 거부 사유 → `IndexTrendDataGateRule` 이름표("왜 안 샀나" 집계) |
| `position/Position.java` | +24 | ② `trailing_high` 컬럼(nullable) + `raiseTrailingHigh()` + 라운드트립 종료·새 진입 시 초기화 |
| `risk/StopLossMonitor.java` | +15 | ② 최고점을 Position에 저장(오를 때만)하고 트래커를 그 값으로 덮어씀 |
| `risk/TrailingStopTracker.java` | +13 | ② `syncHigh()`(덮어쓰기) 추가 — 백테스트는 안 씀. 낡은 "재시작 시 소실" 주석 갱신 |
| `market/KisMarketDataService.java` | +45 | ④ `getDailyCandles` 페이지네이션 — 첫 페이지로 부족할 때만 이어 받기(ATR 15봉은 예전처럼 1회) |
| `resources/application-paper.yml` | +33 | ③ A동 ON · B동 3방식 OFF · 지수 MA120 · TREND 칸 파라미터 |
| `bucket/BucketProperties.java`, `bucket/StrategyBucket.java` | 주석 | ③ "ADR 개정 승인 전까지 잠금" → 개정·사용자 결정 사실로 갱신(기본값 false는 유지) |
| 테스트 12개 파일 (신규 11 + 수정 1) | — | §8 |

**무변경 확인**(git diff 0): `com.trading.backtest` 전체 · `static/` · `dashboard` · `KisApiClient` ·
`KisErrorBodySnippet` · `KisPositionManager` · `order/*`(CancelRetryGate·FillProcessor·KisOrderCancelClient·
OrderCancelClient 포함) · `KisBrokerageApiClient` · `RiskEngine` · `LiquidationService`/`LiquidationPhase` · `IndexTrendRule`.
인터페이스 시그니처(`MarketDataService`·`IndexRegimeSource`·`PositionManager` 등) 무변경.

## 2. ① 지수 데이터원 설계

- **출처**: `CandleHistoryClient.fetchIndexDailyCandles("0001", 오늘-400일, 어제)` — 백테스트 백필이 KOSPI를
  받는 바로 그 경로(TR FHKUP03500100). 모의 도메인에서 동작 실측: 09-21 백필 로그
  `[CandleHistory] 일봉 수취: code=0001 2026-09-05~2026-09-20 → 10건 (지수)`. `KisApiClient` 경유라
  레이트리밋(1건/초)·토큰 처리를 그대로 탄다. 새 KIS 연동 코드는 없다.
- **판정**: 전일 종가 < 전일까지의 MA120 → 하락 추세(매수 금지). 오늘 봉은 요청 자체를 안 하고(`to=어제`),
  섞여 와도 버린다. 백테스트 판정과의 일치를 `IndexTrendParityTest`가 무작위 경로 2,800여 지점에서 고정.
- **캐시·호출 규율**: 그날 성공하면 다시 안 부른다(틱마다 부르지 않음). 거래일 08:30~마감에만, 실패 후 재시도
  30분 간격 — 장외·장전에 이 조회만으로 연속 실패가 3회 쌓여 SAFE_MODE로 가는 일을 막는 간격(장중엔 다른
  성공 호출이 카운터를 리셋한다). 호출당 실패 집계는 최대 1회(첫 실패 페이지에서 중단). 필터가 꺼져 있으면 아예 안 부른다.
- **스레드**: 1분 스케줄은 "받을 때인가"만 보고 실제 조회는 전용 데몬 스레드로 넘긴다 — 스케줄러 풀이 1개라
  그 스레드에서 KIS를 기다리면 손절 감시가 멈춘다(`BackgroundAlertSender`와 같은 이유).
- **실패 시**: 마지막 성공 판정 유지(무기한 — 리더 지시). WARN 로그 + 텔레그램 **장중 하루 1회**
  (장전 실패는 텔레그램이 어차피 안 보내므로 하루 몫을 쓰지 않는다).
- **판정 불가(한 번도 성공 못 함)일 때 = fail-closed**: `IndexTrendRule`은 그대로 두고(empty면 통과 — 백테스트
  MA 워밍업 동작을 회귀 앵커가 기억하므로), 라이브 전용 `IndexTrendDataGateRule`이 매수를 막는다.
  사유 문구 "지수 추세 판정 불가 — … 신규 매수 보류 (fail-closed)" → 대시보드 차단 이력에 룰 이름으로 남는다.
  근거: 다일 보유는 거래가 드물어 하루 쉬어도 손해가 작고, 검증된 필터 없이 사는 것은 검증 범위 밖 매매다.

### 장중 배포 직후 첫 판정 타이밍 (배포 시각 결정용)

- **첫 판정 전 매수 차단은 정상(의도된) 동작이다.**
- 첫 조회는 컨텍스트 기동 **20초 뒤**(스케줄 initialDelay) 시작 → KIS 3회 호출(400일 ≈ 270거래일 ≈ 3페이지,
  페이지 사이 0.6초 + 1건/초 레이트리밋 대기) ≈ 3~10초. **대략 `Started TradingApplication` 후 25~35초**
  (프로세스 시작 후 약 40~50초)에 `[IndexTrend] KOSPI 일봉 N건 수취 — … → 판정` 줄이 찍힌다.
  기동 직후는 어차피 SAFE_MODE→재동기화→RUNNING 순서라 그 사이엔 매매 루프도 안 돈다.
  (이 수치는 코드 설정값에서 계산한 추정이다 — 실측 아님.)
- 첫 조회가 실패하면 **30분 뒤 재시도**, 그동안 A동 신규 매수는 막힌다(텔레그램 1회).
- ⚠ **판정이 나도 오늘은 아마 계속 안 산다**: 리더 실측(09-18) KOSPI 6894.23 < MA120 7063.17(−2.39%).
  그 뒤 시장이 움직였을 수 있으니 배포 로그의 판정 줄을 볼 것. "하락 추세: 신규 매수 금지"면 A동 매수 0건이 정상이다.

## 3. ② 트레일링 최고점 영속화 설계

- `Position.trailing_high`(nullable, `ddl-auto: update`가 다음 기동 때 컬럼 추가). 수명은 `entryDate`와 같다 —
  전량 매도(수량 0)·새 진입에서 비운다. 포지션 행은 전량 청산 때 삭제되므로 새 진입은 자연히 빈 값에서 시작.
- `StopLossMonitor`가 매 틱 `max(저장값, 현재가)`를 **오를 때만** 저장하고, 트래커는 `syncHigh()`로 그 값을
  덮어쓴 뒤 판정만 한다. **DB 값이 정본** — 트래커 메모리(`max` 누적)를 정본으로 두지 않는다.
- **최고점을 모를 때(브로커 보정 행·배포 전 보유분)의 기본값 = 첫 관측가에서 시작.** 과거 고점을 추정해
  트레일을 당겨 파는 것보다 늦게 파는 쪽이 안전하다(`MaxHoldScheduler` 진입일 미상 원칙과 동일). ATR 손절선이 1차 방어선.
- **부수 해소(기존 잠재 결함)**: 옛 인메모리 방식은 타임컷·체결 대사로 끝난 포지션의 고점을 지우지 않아
  (`clear`는 StopLossMonitor 자기 매도에서만 불림) **같은 종목 재진입 직후 옛 고점으로 즉시 트레일 매도**할 수 있었다.
  테스트 `stale_memory_high_does_not_leak_into_new_round_trip`이 옛 방식에서 실제로 매도함을 확인(§8 Red-Green d).
- 쓰기 경합: StopLossMonitor·FillPoller·Reconciler는 같은 단일 스케줄 스레드라 직렬. HTTP 경로(/resume 등)와
  드물게 겹치면 `@Version` 충돌로 그 틱만 건너뛰고(종목별 try/catch) 다음 틱에 복구.

## 4. ③ 최종 paper 설정 (`application-paper.yml` — 전문은 파일)

| 키 | 값 | 근거(백테스트 대응) |
|---|---|---|
| `trading.donchian.enabled / lookback / trend-ma-period` | true / 20 / 120 | §15.7 D0 |
| `trading.filters.index-trend.enabled / ma-period` | true / **120** | §14.4 G1·§15.7 (코드 기본값 200 주의) |
| `trading.bucket-params.overrides.TREND.risk-fraction-per-trade` | 0.0025 | SZ2 0.25R |
| `…TREND.atr-stop-multiplier` | 1.0 | P3 |
| `…TREND.trailing-enabled / arm / trail` | true / 0.01 / 0.03 | P3 |
| `…TREND.multi-day-hold / max-hold-days` | true / 20 | P3 (타임컷 OFF · 최대 20거래일) |
| 동시 5종목 | 전역 `risk.maxPositionCount`=5 (기본값, 설정 UI 현재값 5 확인) | SZ2 동시5 |
| `trading.bucket.trend-enabled / trend-allocation` | true / **4,000,000** | ADR 40% × 원금 1,000만원 (금액이다, 비율 아님) |
| `trading.bucket.event-enabled / mix-enabled` | false / false | B동 칸 잠금(이중 차단) |
| `trading.strategy.enabled` | false | VB 끔 (VB 칸은 칸 설정으로 못 잠근다 — `isBucketActive(VB)`는 항상 true) |
| `trading.ma-breakout.enabled` | false | 이평돌파(EVENT) 끔 |
| `trading.scalping.enabled` | false | 스캘핑(MIX) 끔 |

설정 UI 저장값(DB `app_setting`)이 yml을 덮어쓰는지 확인: 실제 앱 기동(09-30 21:56:30) 로그에
`[Param] 저장된 투자 파라미터` 줄이 없다 → 저장값 0건, yml이 그대로 적용된다. TREND 값들은 UI 카탈로그 대상도 아니다.
현재 계좌: 총자산 9,751,155원(09-30 원장) → 400만원은 현재 자산의 약 41.0%.

## 5. 백테스트와 달라지는 점 (성적 비교 시 분모)

1. **건당 위험이 백테스트의 약 40%**: 1R = 400만원 × 0.25% = **1만원**. 백테스트는 계좌 전체(1,000만원, 복리 — 6.5년간
   2,147만원까지)의 0.25% = 2.5만원에서 출발. 칸 자산 = 배분금 + TREND `trade_result` 실현손익인데, 모의 매도 체결가 결함(결함 5)으로
   매도 손익이 거의 기록되지 않으므로 **칸 자산은 사실상 400만원 고정(복리 없음)**.
2. **단주 스킵이 더 잦다**: `OrderSizingService`는 수량=floor(1R/ATR)가 1 미만이거나 내림 왜곡이 20%를 넘으면 건너뛴다.
   1R이 작아 ATR 약 2,000원 이상 종목부터 스킵 위험이 커진다(1R/ATR이 5 미만 구간). 고가·고변동 종목의 진입이 백테스트보다 줄어든다. **정량화 안 함.**
3. **칸 현금 상한**: TREND 투입 원가 합계 ≤ 400만원(백테스트는 계좌 현금 한도뿐).
4. **유니버스**: paper `trading_universe`(사람이 편입, 최대 20) vs 백테스트 54종목.
5. **진입 시점**: 백테스트는 일봉 근사(고가로 발화 판정, 이분탐색 진입가). paper는 라운드로빈 틱(종목당 N초)의 현재가 → 시장가.
6. **트레일 고점**: 백테스트는 일봉 고가. paper는 잔고 스냅샷 현재가(약 3초 캐시) 표본 → 실제 장중 고가보다 낮게 잡혀 **늦게 파는** 쪽.
7. **손절·트레일 판정 빈도**: 백테스트 하루 1회(시가·저가, 갭 관통은 시가 체결). paper는 1초마다 → 장중 이탈 시 더 일찍 나간다.
8. **비용**: 백테스트 왕복 0.41% 가정. paper 실제 비용은 매도 체결가 결함으로 측정 불가.
9. **공시 쿨다운**: paper는 **켜져 있다**(5일, B동 시절 A/B 채택분). 검증된 D0는 `allFiltersOff()`로 **꺼져 있었다** →
   A동 진입이 백테스트보다 추가로 걸러진다. **결정 필요 — 지시 목록에 없어 바꾸지 않았다.** 맞추려면 한 줄:
   `trading.filters.disclosure-cooldown.enabled: false`.
10. **계좌 단위 안전장치가 A동에도 걸린다**: `RiskMonitor` 일손실 −5%·MDD 10% 강제청산(계좌 전체). 칸별 상한은 없음(§6).
11. **지수 판정 불가 처리**: 백테스트는 표본 부족 시 통과(fail-open, 워밍업 구간뿐), paper는 차단(fail-closed).
12. **배포 시점 B동 잔여 보유분**: 칸이 TREND가 아니므로 15:15 타임컷으로 정리된다. 단 **스캘핑 OFF와 함께 MIX 목표익절(+0.8%)도 꺼진다** —
    MIX 잔여분은 ATR 손절·전역 트레일링·타임컷으로만 나간다. 잔여분은 그날 15:15까지 동시 5종목 슬롯을 차지한다.
13. 백테스트 고유 한계(생존편향·일봉 근사·KOSPI 단일·크래시형 MDD 미방어)는 그대로.

## 6. ADR-001 개정 vs 실제 설정·코드 차이 (고치지 않음 — 리더·사용자 결정)

| ADR 개정(2026-08-07) | 실제 | 상태 |
|---|---|---|
| 누적 자산 가드 MDD **8%** | `risk.mddLimit` **0.10** (RiskLimits.MDD_LIMIT, UI 현재값 0.1). `GlobalEquityStopRule`·`RiskMonitor` 둘 다 이 값 | ❌ 미반영 |
| 슬리브별 낙폭 상한 A동 −12% / B동 −20%(그 칸만 정지·정리) | 코드 없음(칸별 전고점 추적·칸별 정지 없음). 계좌 단일 경보만 | ❌ 미구현 |
| A동 40% | `trend-allocation` 4,000,000 (이번에 반영) | ✅ |
| B동 최대 30% | vb/event/mix-allocation 각 10,000,000(=각 100%). 신호는 전부 꺼서 현재 무해 | ⚠ 재가동 시 위반 |
| 현금 30% | B동 OFF라 실제 현금 약 60% | ⚠ (B동 OFF의 결과) |
| "칸은 `trend-enabled=false`로 잠겨 있다"(§2.1 본문) | 이번에 true — **ADR 문장이 낡음**(문서 갱신은 사용자 승인 사항이라 안 고침) | ⚠ 문서 |
| 일손실 −3%/−5%, 08:30 리셋 | 동일 | ✅ |
| 평시 익절·손절(A동 트레일링 포함)은 OrderEngine 경로 | StopLossMonitor → RiskEngine → OrderEngine | ✅ |
| 개정안 §3 필요 코드: 타임컷 예외·최대보유·칸별 파라미터·돈치안 칸 분리 | 구현됨 | ✅ |
| 개정안 §3-6 지수 추세 필터(A동) | 이번에 paper 실구현. 단 **전역 필터**라 B동을 다시 켜면 B동에도 걸린다 | ⚠ |
| §2.5 09:30 스냅샷·Price Jitter·09:33 폴백(B동) | 미구현(알려진 결함 2) | ❌ (B동 OFF) |

## 7. ④ 추가 발견 — 돈치안 일봉 100행 상한 (필수 수정)

- 증거: `KisMarketDataService.getDailyCandles`는 1회 호출, KIS 일봉 차트는 호출당 약 100행(`KisCandleHistoryClient` 주석·백필 페이지 수와 일치).
  돈치안·이평돌파는 125봉을 요청하고 120봉 미만이면 신호를 안 낸다. 실측: 07-04~ 90일 칸별 매매 VB 28 · MIX 72 · **EVENT 0**
  (`/api/performance/by-bucket?days=90`, DB 조회 전용), 9월 로그 `칸=EVENT` 사이징 0줄.
- 수정: 첫 페이지로 N개가 안 차면 가장 오래된 날 전날을 끝 날짜로 이어 받는다(최대 5페이지, 진전 없으면 중단, 경계 중복 방어).
  ATR(15봉) 등 작은 요청은 예전처럼 1회. 이어 받을 때만 INFO `[MarketData] {종목} 일봉 N봉 수취 (요청 125봉, 페이지 2회)`.
- 부담: 종목당 하루 첫 평가 때 +1회(이평돌파가 꺼지면서 그 1회는 빠진다).

## 8. 테스트 결과 (이번에 직접 실행)

```
[검증 증거 — 기준선(변경 전)]
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-impl29 LOGGING_FILE_NAME=<scratch>/paper-test.log \
      .../gradle-9.2.0/bin/gradle test --console=plain
종료 코드: 0
결과: 830개 중 830개 통과, 실패 0

[검증 증거 — 최종(변경 후)]
명령: 위와 동일 (전체)
종료 코드: 0
결과: 876개 중 876개 통과, 실패 0, 오류 0 (BUILD SUCCESSFUL in 29s)
운영 로그 오염: logs/paper.log 의 "Test worker" 줄 0건
→ 판정: 통과
```

신규 테스트 46개: `KisIndexRegimeSourceTest`(11) · `IndexTrendDataGateRuleTest`(5) · `IndexTrendCalculatorTest`(7) ·
`IndexTrendParityTest`(2) · `IndexTrendWiringTest`(2 — 실제 스프링 컨테이너: paper는 새 데이터원 1개+관문, backtest는 관문 미등록) ·
`StopLossMonitorTrailingPersistTest`(4) · `PositionTrailingHighTest`(3) · `KisMarketDataServiceTest`(4) ·
`PaperTrendSleeveConfigTest`(5 — **실제 application-paper.yml 바인딩**) · `TrendSleeveExitTest`(2) · `RiskRuleNameResolverTest`(+1행).

지시된 고정 항목 대응:
- 지수 MA120 아래 → 차단 / 위 → 통과: `IndexTrendDataGateRuleTest.end_to_end_with_real_source`, `KisIndexRegimeSourceTest` 판정 2건
- 실패 시 마지막 성공 판정 유지 · 한 번도 성공 못 했으면 조용히 통과 안 함: `keeps_last_successful_verdict_on_failure`, `never_succeeded_is_undetermined`, `rejects_buy_when_undetermined`
- KIS 하루 1회: `calls_kis_once_per_trading_day`(판정 1,000회 + 재조회 시도에도 1회, 다음 거래일 2회)
- 트레일링 최고점 재시작 복원: `restores_persisted_high_after_restart`
- TREND 타임컷 제외 · 20거래일 MaxHold 청산: `TrendSleeveExitTest`(설정 파일 바인딩값으로)
- B동 3방식 신호 없음: `b_sleeve_strategies_emit_no_signal`("켜져 있었다면 샀을" 입력으로 대조)

Red-Green (수정을 되돌려 실패 확인 → 복구):

| | 되돌린 것 | 결과 |
|---|---|---|
| a | 관문을 fail-open으로 | 관문 테스트 5개 중 2개 실패 |
| b | 실패 시 마지막 판정 폐기 | 데이터원 11개 중 2개 실패 |
| c | 하루 1회·재시도 간격 제거 | 데이터원 11개 중 2개 실패 |
| d | 트레일 고점을 옛 메모리 방식으로 | 영속화 4개 중 4개 실패(재진입 즉시 매도 재현 포함) |
| e | 일봉 페이지네이션 없음(구현 전) | 4개 중 2개 실패 |
| f | yml 변경 전 | 설정·출구 테스트 7개 중 7개 실패 |

전부 원본 복구 후 위 최종 876/876.

## 9. 배포 후 첫날 관측할 것

1. 기동 로그 `[RiskEngine] Loaded N risk rules: …`에 `IndexTrendDataGateRule` 포함(이전보다 1개 많음). Hibernate 오류 없이 기동.
2. 기동 후 약 1분 안: `[CandleHistory] 일봉 수취: code=0001 … (지수)` → `[IndexTrend] KOSPI 일봉 N건 수취 — {전일} 종가 X · MA120 Y (Z%) → …`.
   실패면 `[IndexTrend] KOSPI 일봉 갱신 실패` WARN + 텔레그램 → 30분 뒤 재시도.
3. 종목별 첫 평가 때 `[MarketData] {종목} 일봉 125봉 수취 (요청 125봉, 페이지 2회)` — **120봉 미만이면 돈치안은 여전히 못 산다**(가장 중요한 확인).
4. B동 흔적 없음: `칸=VB/EVENT/MIX` 사이징 로그·신규 B동 주문 0건.
5. `/api/risk/blocks`: 하락 추세면 A동 신호가 `IndexTrendRule`로 막힌 기록. `IndexTrendDataGateRule` 기록은 기동 직후 첫 판정 전 몇십 초에만 있어야 정상.
6. A동 포지션이 생기면 `position.trailing_high`가 첫 감시 틱 뒤 채워지고, 재시작 후에도 남는지.
7. 15:15 타임컷 로그 `[타임컷] 다일 보유 칸 N종목은 제외 — 이월한다`, 15:17 `[최대보유] … 진입일 미상 — 오늘부터` 또는 도장.

## 10. 남은 것 / 확인하지 못한 것

- **paper 전체 컨텍스트 실기동 미확인**(재시작 금지). 대신 실제 스프링 컨테이너로 배선·프로필 분리를 테스트(`IndexTrendWiringTest`).
- **paper 앱에서의 실제 KOSPI 조회 미확인** — 같은 TR이 모의 도메인에서 09-21 성공한 백필 로그가 근거. 첫 판정 줄로 확인할 것.
- **100행 상한은 실측 호출로 재지 않았다**(장중 계좌 유량을 쓰는 외부 호출을 피함). 코드·주석·EVENT 0건이 근거. 배포 후 §9-3으로 확인.
- `trailing_high` 컬럼 추가는 `ddl-auto: update` 표준 동작에 기댄다(임베디드 H2 `@DataJpaTest`들은 새 매핑으로 통과). 운영 파일 DB에서는 미실행.
  `schema.sql`은 이미 `bucket`·`entry_date` 등도 빠진 낡은 문서라(기동 시 실행 안 됨) 손대지 않았다.
- 단주 스킵 빈도(1R 1만원) 미정량화. 공시 쿨다운 ON 여부 **결정 필요**(§5-9).
- 지수 판정 "마지막 성공 유지"에 **기한이 없다**(지시대로). 지수 TR만 계속 실패하면 옛 판정이 무기한 쓰인다 — 매일 장중 텔레그램 1회로만 드러난다. 기한을 둘지 결정 필요.
- 갭다운 지수 필터(`IndexRegimeRule`)는 paper에서 여전히 판정 불가(=통과). 기본 OFF·검증 설정 밖이지만 설정 UI에 켜는 스위치가 있어, 켜도 아무 효과가 없다(기존 문제).
- 브로커 보정으로 새로 생긴 행은 칸이 null(=VB)이라 A동 보유분이 그 경로로 생기면 15:15에 팔린다(기존 동작).
