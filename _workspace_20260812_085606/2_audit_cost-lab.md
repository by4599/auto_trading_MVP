# 감사 보고서 2 — cost-lab 모드 (왕복 거래비용 민감도 스윕)

- 감사일: 2026-07-23
- 감사관: risk-auditor
- 대상: trading-implementer의 cost-lab 구현 (_workspace/1_impl_cost-lab.md 후속)
- 결론: **진행 가능 (조건부)** — 미해소 CRITICAL 0건 · 미해소 HIGH 0건
  (감사 중 HIGH 1건 발견 → 감사 도중 해소 확인. 아래 F-C1 참조)

---

## 0. 이전 CRITICAL·HIGH 현황

_workspace/ 에 선행 감사 파일 없음 (1_impl_cost-lab.md 는 구현 노트). 최초 감사.

---

## 1. 감사 범위 (실제로 읽은 것)

git diff --name-only / git status --porcelain 로 대조한 결과, **지시받은 파일 목록보다
변경 범위가 넓다**. 아래는 실제 미커밋 변경 전체이며, cost-lab 귀속 여부를 구분했다.

| 파일 | cost-lab 귀속 | 읽음 |
|---|---|---|
| backtest/BacktestCostProperties.java (신규) | O | 전문 |
| backtest/BacktestCosts.java | O | 전문 + diff |
| backtest/BacktestOrderClient.java | O | diff + 헤더 |
| backtest/BacktestOrchestrator.java | O (일부) | diff 전문 + 헤더 |
| backtest/BacktestReportWriter.java | O (일부) | diff 전문 + judge() |
| backtest/WalkForwardEngine.java | O | 전문 |
| test/BacktestCostPropertiesTest.java (신규) | O | 전문 |
| test/DailyBarSimulatorTest.java | O (1줄) | diff |
| backtest/BacktestRunner.java | X — exit-lab 잔여 | diff |
| risk/TrailingStopTracker.java | X — exit-lab 잔여 | diff |
| BacktestMarketDataService / BacktestMetrics / DailyBarSimulator / 전략 3종 | X — exit-lab·13절 잔여 | 부분 |
| .yml / .gitignore / application-*.yml | 변경 **없음** (확인) | — |

보조로 읽은 것: BacktestStateReset.java, RiskLimitsProperties.java, ExitLabProperties.java,
RiskLimits.java, EventBacktestPipeline.java(265~315), TradingEventListener(@Profile).

**주의:** 지시 목록에 없던 BacktestRunner·TrailingStopTracker·DailyBarSimulator 등의
변경은 exit-lab/risk-lab 세션의 미커밋 잔여물로 판단했다. 이번 감사의 판정 대상이 아니며,
**별도 감사 미실시** 상태로 커밋에 섞여 들어갈 위험이 있다 (아래 M-3).

---

## 2. 판정표

| 심각도 | 항목 | 위치 | 내용 | 근거 |
|---|---|---|---|---|
| **HIGH (해소됨) F-C1** | 비용 역산 수식 오류 | BacktestCostProperties.java:48 | setRoundTripCost() 에서 나누기 2 누락 → 의도의 **2배** 슬리피지 적용. C0 앵커가 왕복 0.41% 가 아닌 0.61% 가 되어 회귀 앵커가 조용히 무효화되고, 스윕 전 구간(C1~C4) 비용이 최대 1.39% 로 부풀려짐 | 최초 전체 테스트(00:38) 2건 FAIL — BacktestCostPropertiesTest.java:36 실측 0.0039 (기대 0.00195), :45 실측 0.0059 (기대 0.00295). **감사 중(mtime 00:39:55) 수정 확인** → 클린 재빌드 361 tests / 0 failures |
| CLEAN | 프로필 격리 | BacktestCostProperties.java:24-26 | Component + Profile(backtest) 정상. paper/real 컨텍스트에 로드되지 않음 | 참조처 전수 확인: BacktestOrderClient.java:46 (자체 Profile(backtest) :31-32), BacktestOrchestrator.java:56 (:33-34). 라이브 클래스 참조 0건 |
| CLEAN | 텔레그램 누출 재발 | TradingEventListener:28 | Profile(!backtest) 방어선 그대로 유지 — 이번 변경이 건드리지 않음 | BACKTEST-DESIGN 12절 사고 대응 유지 확인 |
| CLEAN | 백테스트 비용 모델의 라이브 침투 | EventBacktestPipeline.java:266,314 | BacktestCosts.ROUND_TRIP_COST **정적 상수 그대로** — events 모드 CANDIDATE 문턱값 불변 | BacktestCosts.java:16-17 무변경 |
| CLEAN | 기존 모드 결과 불변 (수식) | BacktestCosts.java:30-38 | buyFillPrice(raw) 가 buyFillPrice(raw, SLIPPAGE_RATE) 로 위임되고 최종식은 raw x (1+s). 변경 전 raw x (1+SLIPPAGE_RATE) 와 **동일 수식·동일 상수** | 홀더 기본값 = BacktestCosts.SLIPPAGE_RATE (BacktestCostProperties.java:29) |
| CLEAN | 기존 모드 결과 불변 (결정성) | BacktestRunner.java:82 | 런마다 stateReset.reset() 호출 — 프로필 간 상태 이월 없음. BacktestStateReset 이 TrailingStopTracker 까지 초기화 | 스윕 순서가 결과에 영향 주지 않음 확인 |
| CLEAN | 기준선 파일 오염 | BacktestReportWriter.writeCostLabReport() | baselineOnPass=false — 비용 부풀린 스트레스 결과가 docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml 을 덮어쓰지 않음 | risk-lab 만 true. 거버넌스 분모 보호 정상 |
| CLEAN | 주문 흐름 규칙 (CLAUDE.md 규칙 1·2) | BacktestOrchestrator.java:466-483 | cost-lab 은 프로퍼티 세팅 + walkForwardEngine.run() 호출뿐. Strategy 가 주문을 직접 내는 경로 신설 **없음** | Strategy - Signal - RiskEngine - OrderEngine 순서 우회 코드 0건 |
| CLEAN | 리스크 룰 규칙 (규칙 3) | — | RiskEngine 클래스 무수정, RiskRule 구현체 증감 없음 | diff 에 RiskEngine.java 없음 |
| CLEAN | 청산 상태머신 (규칙 5 / ADR-001) | — | LiquidationService 와 LiquidationPhase 무변경. 별도 플래그 신설 없음 | diff 에 risk 패키지 Liquidation 관련 파일 없음 |
| CLEAN | riskLimits 직접 변경의 정당성 | BacktestOrchestrator.java:470-471 (복원 :479-480) | risk-lab 선례(:391-392, 복원 :404-405)와 **동일 패턴**. 백테스트 전용 JVM/프로필이라 paper·real 에 도달 불가 → 정당한 스윕 용법 | RiskLimitsProperties 는 프로필 없는 Component 이나, 같은 프로세스에서 모드가 섞이지 않는 한 안전 |
| CLEAN | 비밀키·설정 | — | 하드코딩 키 0건. yml·gitignore 변경 0건. trading.bucket 은 application-paper.yml:38 에만 존재 (backtest 에서 켜지지 않음) | git diff 로 yml·gitignore 변경 무출력 확인 |
| **MEDIUM (M-1)** | 예외 경로 복원 누락 | BacktestOrchestrator.java:466-483 | runCostLab 에 try/finally 없음 → walkForwardEngine.run() 이 던지면 costProperties.resetDefaults(), resetExitProfile(), riskLimits 원복이 전부 스킵 | 완화: run() 이 :92-99 에서 잡고 System.exit → 프로세스 종료라 다음 모드 오염 불가. risk-lab·exit-lab 과 동일 선례이므로 MEDIUM |
| **MEDIUM (M-2)** | 리포트에 실제 적용 비용 미기재 | BacktestReportWriter.writeCostLabReport() 와 writeComparison() 표 | 프로필 행 라벨이 **목표** 비용(C0 기준 0.41%)뿐. 실제 역산된 편도 슬리피지·실제 왕복이 리포트 파일에 없음 (로그에는 있음 — BacktestOrchestrator.java:503-505) | F-C1 같은 역산 버그가 재발하면 **리포트만 봐서는 탐지 불가**. 실제 슬리피지 컬럼 추가 권고 |
| **MEDIUM (M-3)** | 미감사 변경 동반 커밋 위험 | BacktestRunner.java, TrailingStopTracker.java:57-76, DailyBarSimulator.java 등 | exit-lab/risk-lab 세션의 미커밋 잔여 변경이 작업 트리에 함께 있음. cost-lab 커밋에 섞이면 감사 이력이 끊긴다 | git status 대조 결과. 커밋 단위 분리 또는 별도 감사 필요 |
| **LOW (L-1)** | C0 앵커의 부동소수 동일성이 우연에 기댐 | BacktestCostProperties.java:46-55 | setRoundTripCost(0.0041) 의 결과 슬리피지는 0.0010000000000000002 로, 상수 0.001 보다 1 ULP 크다 (**비트 동일 아님**) | 단, 슬리피지는 (1+s)·(1-s) 안에서만 쓰이고 두 값 모두 1+s = 0x3ff004189374bc6a, 1-s = 0x3feff7ced916872b 로 **비트 동일**. 무작위 가격 20만 건 곱셈 불일치 0건 → C0 체결가는 RR1 과 완전 동일. 향후 상수 변경 시 이 성질이 깨질 수 있음 |
| **LOW (L-2)** | 로그에 비결정적 값 유입 | WalkForwardEngine.java:82,113-116 | System.nanoTime() 경과시간이 로그 문자열에 들어감 | 결과·리포트에는 미반영. 백테스트 결정성 무영향 |
| **LOW (L-3)** | 테스트 허용오차가 오류를 못 잡을 뻔 | BacktestCostPropertiesTest.java:27-28 | roundTripCost() 기본값 검증이 within(1e-9) — 1 ULP 오차는 통과. 다행히 :36 과 :45 가 F-C1 을 잡았다 | 회귀 앵커 성질을 지키려면 (1+s)·(1-s) 비트 동일성 단언 추가 권고 |

---

## 3. 검증 증거 (신선한 실행 결과)

    # 최초 실행 (00:38~00:39) — 당시 작업 트리 기준
    361 tests completed, 2 failed
      BacktestCostPropertiesTest.java:36  actual 0.0039000000000000003 (expected 0.00195)
      BacktestCostPropertiesTest.java:45  actual 0.005900000000000001  (expected 0.00295)

    # 파일 변경 감지: BacktestCostProperties.java mtime 2026-07-23 00:39:55 (위 빌드 이후)

    # 클린 재검증 (00:42) — 클래스 삭제 + --rerun-tasks 로 전체 재컴파일
    compileJava / compileTestJava / test  (4 actionable tasks: 4 executed)
    BUILD SUCCESSFUL in 32s
    집계: total=361  failures=0  errors=0  skipped=0
    BacktestCostPropertiesTest: tests=6 failures=0 errors=0

    # 현재 소스 확인
    sha1 e49e009fc57b970a1da7e8937fb0b8f1d5240eeb
    BacktestCostProperties.java:48  ->  double slippage = (target - fixed) / 2;

부동소수 동일성 검증 (C0 회귀 앵커):

    slip(기본)    0.001                  bits 3f50624dd2f1a9fc
    slip(C0 역산) 0.0010000000000000002  bits 3f50624dd2f1a9fd   <- 1 ULP 차이
    1+s  둘 다 3ff004189374bc6a  (동일)
    1-s  둘 다 3feff7ced916872b  (동일)
    무작위 가격 200,000건 곱셈 불일치: 0건

---

## 4. 프로세스 지적 (구현자 대상)

- **완료 보고 시점에 테스트가 깨져 있었다.** trading-implementer 가 구현 완료를 알린 뒤
  내가 실행한 첫 전체 테스트에서 361건 중 2건이 실패했고, 그 실패는 스윕 전체를 무효화하는
  수식 오류(F-C1)였다. golden-principles 10번(증거 기반 완료) 위반이다.
  **완료 보고 전 전체 테스트 실행 결과를 첨부할 것.**
- **감사 중 소스가 움직였다.** 감사 시작 후 대상 파일이 수정되었다(mtime 00:39:55).
  감사와 구현이 동시에 같은 파일을 만지면 판정의 재현성이 깨진다. 감사 요청 시점에
  작업 트리를 **동결**하거나, 수정 시 감사관에게 즉시 통보할 것.

---

## 5. 결론

| 구분 | 건수 |
|---|---|
| CRITICAL (미해소) | **0** |
| HIGH (미해소) | **0** |
| HIGH (감사 중 해소) | 1 — F-C1 비용 역산 나누기 2 누락 |
| MEDIUM | 3 |
| LOW | 3 |

**진행 가능** — 단, 아래 조건을 붙인다.

1. **커밋 직전 전체 테스트를 다시 돌린다.** 감사 중 파일이 변경된 이력이 있어, 커밋
   시점의 트리가 내가 검증한 트리(sha1 e49e009...)와 같은지 재확인이 필요하다.
2. **cost-lab 커밋에 exit-lab/risk-lab 잔여 변경을 섞지 않는다** (M-3). 섞을 경우
   해당 변경에 대한 별도 감사를 선행한다.
3. **리포트에 실제 적용 슬리피지와 실제 왕복 비용 컬럼을 추가**한다 (M-2). F-C1 은
   리포트만 보면 정상으로 보이는 결함이었고, 지금 구조로는 재발해도 탐지되지 않는다.
4. **C0 회귀 앵커를 실행 결과로 반드시 대조한다.** 리포트 판독 지침이 스스로 명시하듯,
   C0 가 14.1절 RR1(트레이드 780·PF 1.96·MDD 9.9%)을 재현하지 못하면 그 실행은 무효다.
   paper-ops-verifier 가 이 대조를 증거로 확인해야 한다.

> **실전 전환 관련 상시 조건:** cost-lab 이 전 프로필 합격하더라도 그것은 측정 결과일 뿐
> 승격 사유가 아니다. 실전(real) 전환은 **ADR-001(다일 보유) 재논의 + 게이트 G2(사람)
> 선행**이 절대 조건이며, docs/TRADING-RULES-AUDIT.md 의 미해소 항목(모의계좌 강제청산
> 리허설 미실행 등)도 함께 해소되어야 한다. paper 기본값·리스크 룰·지갑 칸은 이번 변경으로
> 바뀌지 않았음을 확인했다.
