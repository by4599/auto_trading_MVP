# 6_verify — 타임컷 재시도 스윕 증거 검증

검증자: 리더(오케스트레이터) 직접 실행 · 2026-09-07 KST
대상: `_workspace/4_impl_timecut-retry-sweep.md` 구현 + `5_audit_` 감사 결과 대조

## [검증 증거] — 이번에 직접 실행

```
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build gradle test --rerun-tasks --console=plain
종료 코드: 0
결과: 93클래스 616테스트 통과 — skipped=0 failures=0 errors=0
      (선행 작업 607 + 신규 TimeCutRetrySweepTest 9)
→ 판정: 통과
```

구현자 Red-Green(변형 A: 스윕 무력화 → 4건 실패 / B: 브로커 확인 제거 → 1건 실패 /
C: 조회 예외를 '정리 완료'로 처리 → 1건 실패)은 `4_impl_` 노트 참고.
감사관도 대상 3개 클래스를 독립 재실행(9/13/8, 전부 0 실패).

## 구현 확인 (리더 직접 코드 대조)

`TimeCutScheduler.java:186-259` — 설계 판단대로 들어왔다.

| 지시 | 실제 코드 | 판정 |
|---|---|---|
| 인라인 재시도 금지, 스윕 스케줄로 | `:177-185` cron `0 20,24 15` + `0 28 15` (MON-FRI/KST) | ✅ |
| 신선한 0에서만 재매도 스킵 | `:230-244` `brokerConfirmedFlat` — 0이면 true, **예외면 로그 후 false**(재매도로) | ✅ |
| 읽기 전용 사용 | `getActualHoldingQuantity`만 호출, `sendMarketOrder` 없음 | ✅ |
| RiskEngine 경유 유지 | `:159-174` 기존 `sellPosition` 재사용 | ✅ |
| 대상 집합 일치 | `:153-157` `timeCutTargets` 헬퍼를 타임컷·스윕이 공유 | ✅ |
| 최종 스윕만 알림 | `:207-209` → `:245-259` `alertIfStillHeld` | ✅ |
| 종목별 예외 격리 | `:198-205` | ✅ |

알림 문구(`:255-258`)는 쉬운 우리말이고 "이대로면 밤새 들고 갑니다 / 손절선은 살아 있습니다"로
사실만 말한다. 파일 크기: `TimeCutScheduler.java` 267줄, `TimeCutRetrySweepTest.java` 274줄 —
둘 다 300줄 상한 이내.

## 감사 결과 (`5_audit_`) — CRITICAL 0 · HIGH 1 · MEDIUM 5 · LOW 5

### HIGH 1건은 **이번 diff가 만든 것이 아니다** — 사용자 판단 대기

`GlobalEquityStopRule.java:29-43`에 **`signal.isBuy()` 가드가 없다.**
리더가 직접 검산: 룰 14개 중 `isBuy()` 호출이 0건인 것은 이 파일 **하나뿐**이다
(`grep -c "isBuy()" src/main/java/com/trading/risk/*Rule.java`).

같은 파일의 주석은 세 번이나 매수 전용이라고 말한다:
- `:10` "이 룰은 진입 게이트로서 매수 "거부"만 담당한다"
- `:15` "신규 진입은 계속 막는 쪽이 보수적이다"
- `:39` 거부 사유 문자열 자체가 "신규 매수 금지"

즉 **코드가 자기 문서와 어긋난다.** 결과: 전고점 대비 MDD가 한도를 넘으면
타임컷·이번 스윕·손절·최대보유의 **매도가 전부 거부된다**. 자동청산이 보류되는 분기
(`RiskMonitor` 전고점 미검증 / 낡은 스냅샷)에서는 출구가 전부 닫힌다.

**이번 작업과의 관계**: 재시도 스윕도 `sellPosition` → `riskEngine.check`를 타므로
하락장에서 이 룰에 막힌다. 즉 **HIGH를 두면 이번 수정의 목적이 반쯤 무력화된다.**

**그런데 단순 1줄 수정이 아니다 — 백테스트 파급이 있다.**
`GlobalEquityStopRule`은 `@Profile({"paper","backtest"})`이고, 백테스트의 출구
(`DailyBarExitSimulator.exitAt:169`)도 RiskEngine을 탄다. 반면 `BacktestRunner.closeDay:160-186`의
강제청산 모델은 RiskEngine을 우회한다. 따라서 현재 앵커
(`docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml`)는 **"MDD 한도 초과 구간에서 손절·트레일 출구가
막힌 채" 산출된 값**일 가능성이 있다. 가드를 넣으면 그 구간의 출구가 열려 결과가 달라질 수 있다.
§14.3이 MDD 33.5%를 보고한 창이 있으므로 한도 초과 구간은 실제로 존재한다.

→ **고치려면 risk-lab 재실행 + 앵커 대조가 선행돼야 한다.** 리더는 이 변경을 하지 않았고
사용자 판단을 기다린다. (감사관 근거: `GlobalEquityStopRuleTest.java`에 SELL 케이스 0건)

### MEDIUM 5건 — 이번 범위에서 해소하지 않음, 근거 병기

1. **중복 매도 접수 경로 실재** — 타임아웃 주문은 `KisOrderClientImpl.java:103-106`에서
   FAILED로 기록돼 `hasPendingSell`이 못 잡는다. 브로커에 살아 있는 미체결 매도는
   보유수량에도 안 보인다. 최대 3회(15:20·24·28) 재접수 가능.
   ※ 리더 견해: KIS는 **매도가능수량**으로 초과 매도를 거부하므로 실피해는 "거부 1건"에
   그칠 가능성이 높다. 다만 **코드·테스트로 증명된 방어가 아니다** — 감사관 지적이 맞다.
2. **최종 알림이 DB만 본다**(`:245-259`) — `:233`에서 읽은 브로커 실수량을 버려서,
   모의 체결조회 공백(알려진 결함 5번) 때문에 이미 팔린 종목에도 알림이 갈 수 있다.
   과알림 방향이라 안전하지만 정확하지 않다.
3. **최종 알림이 15:30을 넘기면 소멸** — `TelegramNotifier`가 장중에만 보낸다.
   09-01 실측 종목당 17~18초에 잔고 조회가 더해지면 15:28 스윕이 15:30을 넘길 수 있다.
   **가장 나쁜 날에만 침묵한다**는 점에서 뼈아프다.
4. **단일 스케줄 스레드 점유** — 스윕이 도는 동안 `RiskMonitor`·`StopLossMonitor`(각 1초)가 밀린다.
5. **청산 양보가 mode만 본다** — 관례인 `isAnyLiquidationInProgress()`를 쓰지 않아
   phase→mode 전환 사이 창이 있다. 감사관도 판단 보류로 병기.

### LOW 5건
재매도 수량이 DB 기준 / `hasPendingSell`가 CANCEL_REQUESTED 미포함 / 스케줄러 풀 크기 1 암묵 의존 /
회귀 테스트 전제가 실사고와 다름(테스트는 브로커 5주 가정, 09-01 실제는 0주) /
스윕이 "늦은 타임컷"으로도 동작.

## 검증하지 **않은** 것 (통과로 적지 않음)

1. **스프링 실기동 주입 미확인** — 새 의존 2개(`BrokerageApiClient`·`NotificationService`)의
   빈 주입은 정적 확인만. paper 프로필 후보가 각각 `KisBrokerageApiClient`·`TelegramNotifier`
   하나뿐이라는 근거.
2. **실장중 스윕 동작 미관측** — 15:20/24/28에 실제로 도는 것을 본 적 없다.
3. **KIS 초과매도 거부 동작 미확인** — MEDIUM-1의 실피해 크기가 이 가정에 걸려 있다.
4. **미배포** — 실행 중인 앱(PID 9776, 2026-08-31 08:30:19 기동)은 옛 코드다.
   09-02 저녁~09-07 새벽 PC 절전(clock leap 4d7h39m)으로 연속 가동 기록은 이미 끊겼으므로,
   **지금 재시작해도 잃을 기록이 없다.**
