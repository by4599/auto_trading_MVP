# 감사 결과 — peakEquity 오염 교정 (2026-08-12)

판정: **CRITICAL 0건 · HIGH 2건 · MEDIUM 5건 · LOW 2건 → "완료" 아님** (HIGH 해소 전까지). 다만 **지금 앱을 재시작하는 것 자체는 안전**하다(근거는 4번 항목).

## 1. 구현자 보고 재확인 (감사자가 직접 실행/조회한 증거)

| 주장 | 검증 방법 | 결과 |
|---|---|---|
| tick() 4단 가드 | 코드 직독 `ShadowPortfolio.java:62-75` | **일치**. isFresh → `current<=0 \|\| current<=peak` → isImplausible (보고표의 2·3단이 코드에선 한 줄, 순서·의미 동일) |
| restore() 실측 대조 교정·재저장 | `ShadowPortfolio.java:38-53` | **일치**. 낮아질 때만 save |
| 운영 DB 교정 완료 | 별도 H2 세션으로 재조회 | **일치**: `PEAK_EQUITY \| 1.0E7`, `DAILY_EQUITY MX=1.0E7 MN=9,912,930 C=24` |
| 495/495 통과 | `gradle test --rerun-tasks` 신규 실행(2026-08-12 09:12 KST) + XML 합산 | **재현**: 75클래스/495테스트, 실패 0·에러 0·스킵 0. `ShadowPortfolioTest` 9/0, `RiskMonitorTest` 12/0 |
| RED 4건 실패 | 소스 변조가 필요해 재실행 안 함 | **보류**. 테스트 1·2·6·9가 가드에 논리적으로 의존함은 코드로 확인 |
| 새 JPQL 유효성 | `@DataJpaTest`(`FillStateUpdaterTest` 17/0)가 리포지토리 부트스트랩 → `@Query` 검증됨 | **통과** |

## 2. 규칙 위반 감사

| 항목 | 결과 |
|---|---|
| 흐름(Strategy→Signal→RiskEngine→OrderEngine) | 위반 없음 — 주문 경로 무변경 |
| RiskEngine 무수정 / 룰 자동 주입 | **통과**. `RiskEngine.java` 무변경, RiskRule 구현체 13개 전부 `@Component` 유지 |
| ADR-001 청산 상태머신 | **통과**. `LiquidationService`·`LiquidationPhase` diff 없음, 새 플래그 없음 |
| 프로필 격리 | `PeakEquityCalibrator` `@Profile({"paper","backtest"})` = `ShadowPortfolio`와 동일 → 빈 누락 없음. 텔레그램·bucket 무관 |
| 비밀키 | 하드코딩 없음 |

## 3. 지적 사항

| 심각도 | 항목 | 위치 | 내용 | 근거 |
|---|---|---|---|---|
| HIGH | 교정 과잉 하향 | `PeakEquityCalibrator.java:56-62` | 상한 초과 시 `ceiling`이 아니라 `verifiedMax`로 슬래시 → 정당한 전고점이 상한을 1원만 넘어도 **최대 15% 깎이고 즉시 영속화**(원값 소실). 전고점 하향은 MDD를 작게 만들어 `GlobalEquityStopRule`·`RiskMonitor` **양쪽을 동시에 느슨하게** 한다 | CLAUDE.md 리스크 룰 표, `RiskMonitor.java:107-117` |
| HIGH | 1.15 상수와 런타임 파라미터 비결합 | `PeakEquityCalibrator.java:39` vs `ParamCatalog.java:38-48` | 유도 근거인 종목당 비중·최대 종목수는 **설정 UI에서 사람이 바꿀 수 있다**(상한 0.30 / 20). 0.3×5=90% 노출이면 하루 상승 상한은 15%가 아니라 ~27% → ① tick()이 정당한 신고점을 warn 로그만 남기고 조용히 차단 ② 다음 기동 restore()가 정당한 전고점을 잘라냄(HIGH-1과 복합) | `RiskLimitsProperties.java:21-22` |
| MEDIUM | 백테스트 동작 변경 미검증 | `BacktestRunner.java:162,165` + `BacktestPositionManager.java:95` | "백테스트는 기록이 없어 판정 보류"는 부정확 — 런 중 시뮬일마다 daily_equity가 쌓이므로 isImplausible이 살아 있고, 하루 +15% 초과 구간에서 MDD 매수 게이트/강제청산 모델 판단이 달라질 수 있다. §14.4·§15의 G0 회귀 앵커 재현성 미증명 | BACKTEST-DESIGN §14.4 G0 앵커 |
| MEDIUM | 검증 근거가 오염원과 비독립 | `KisPositionManager.java:86-93,104-108` | `start_equity`는 peakEquity를 오염시킨 것과 같은 소스(잔고 totalAssetValue)의 그날 첫 값. 잘못된 잔고가 그날 첫 스냅샷이면 상한이 같이 올라 가드가 무력화되고, dailyPnl 기준선도 오염돼 -5% 강제청산 헛발동 경로가 생길 수 있다(오염 원인 미확정) | `RiskMonitor.java:96-103` |
| MEDIUM | 안전장치 기준선을 조용히 변경 | `ShadowPortfolio.java:45-48` | 자동 하향이 `log.error`만 남기고 텔레그램 알림 없음. 동종 자동보정(`ShadowPortfolioReconciler.correctFromBroker`)은 `sendCritical`을 쓴다 | 프로젝트 관례 |
| MEDIUM | DB 직접 UPDATE의 잔여 창 | 운영 DB / `RiskMonitor.java:107-117` | 값·경로 확인됨(안전). 메모리 값이 살아있는 동안 위험은 "매수 차단"만이 아니라 장중 RUNNING/SAFE_MODE면 오염 전고점으로 강제청산 반복 트리거 가능. 실측 현재: EMERGENCY_STOPPED·보유 0 → 지금 당장 손실 경로 없음. **재시작 전 RUNNING 전환 금지.** EMERGENCY_STOPPED가 이번 오염의 결과인지는 로그 미확인 — 판단 보류 |실측(`/api/risk/status`, POSITION 0행) |
| MEDIUM | 재시작이 사람 게이트 없이 RUNNING | `ShadowPortfolioReconciler.java:117-123` | 사용자 정책(2026-07-16)상 의도된 설계라 위반은 아니나, "원인 미확정 비상정지"가 재시작만으로 매매 재개된다는 사실은 기록 — 판단 보류 | OPERATIONS §3 |
| LOW | real 프로필 공백 | `GlobalEquityStopRule.java:13`, `RiskMonitor.java:29`, `ShadowPortfolio.java:15` | 실전 프로필엔 전고점 추적·MDD 게이트·청산 감시가 아예 없다(기존 상태, 이번 변경 무관). 실전 전환은 게이트 G2 + 프로필 확장 선행 | CLAUDE.md |
| LOW | 경계 테스트 부재 | `ShadowPortfolioTest.java` | 정확히 1.15배 경계 위/아래 케이스 없음 | — |

## 4. 재시작 안전성 (즉시 답)

지금 저장값은 10,000,000 = `verifiedMax`이고 상한(11,500,000) 이하라 `calibrate()`가 그대로 통과시킨다 → **재시작해도 HIGH-1의 과잉 하향은 발동하지 않는다.** 재시작은 진행 가능. 단 HIGH 2건은 코드 병합(커밋) 전에 해소 권고.

## 5. trading-implementer에게 요청한 수정 (우선순위)

1. HIGH-1: 상한 초과 시 `verifiedMax * (1 + INTRADAY_HEADROOM)`으로 **클램프**, 원값을 별도 키로 보존, `notifier.sendCritical` 알림 추가.
2. HIGH-2: `INTRADAY_HEADROOM`을 `RiskLimitsProperties`(`min(1.0, maxPositionWeight × maxPositionCount) × 0.30`)에서 **유도**하도록 결합.
3. MEDIUM-1: `@Profile("paper")`로 좁히거나, G0 앵커 24창 재현 재실행 증거 제출.

---

## 재감사 (2회차) — 2026-08-12

판정: **CRITICAL 0 · HIGH 0 · 신규 MEDIUM 1 · LOW 2 → 통과**

### 1회차 지적 해소 상태
| 번호 | 항목 | 상태 |
|---|---|---|
| HIGH-1 | 교정 과잉 하향 | 해소 — `ceiling`으로 클램프, 원값 보존 키 추가, `sendCritical` 알림 |
| HIGH-2 | 허용폭 하드코딩 | 해소 — `RiskLimitsProperties`에서 `min(1.0, weight×count)×0.30` 유도, 기본 설정에서 기존과 동일한 15% 산출 확인 |
| MEDIUM-1 | 백테스트 영향 | 해소 — `NoOpPeakEquityCalibrator(@Profile("backtest"))` 분리, 백테스트 경로에 검증 코드 미주입 |
| MEDIUM-3 | 조용한 기준선 변경 | 해소 — sendCritical 추가 |
| LOW-2 | 경계 테스트 부재 | 해소 — CeilingBoundary 3건 추가 |
| MEDIUM-2 | 검증 근거가 오염원과 비독립 | 미해소(범위 밖, BACKLOG 권고) |
| MEDIUM-4 | DB 직접 UPDATE 잔여 창 | 미해소(운영) — 재시작 전 RUNNING 금지 |

### 검증 증거
- 전체 스위트 재실행(09:27 KST): 78클래스/505테스트 전부 통과, ShadowPortfolioTest 계열 19건 전부 통과
- 감사자가 독립적으로 RED-GREEN 재현(클램프·허용폭 로직 임시 원복 → 19 tests 5 failed → 복원 후 GREEN, md5 대조로 워킹트리 무결성 확인)
- 빈 해석 정적 확인: paper=EvidenceBased 1개, backtest=NoOp 1개, 순환 없음
- 아키텍처 규칙(RiskEngine/LiquidationService/주문흐름/비밀키) 위반 없음

### 클램프 상한 ↔ 운영 DB 값 상호작용
| 경로 | 전고점 | MDD(현재자산 9,992,814 기준) | 매수 게이트 |
|---|---|---|---|
| 오염 그대로 | 13,027,929 | 23.3% | 차단(+청산 트리거) |
| 자동 클램프만 | 11,500,000 | 13.11% | 여전히 차단 |
| 1회성 실측 교정(현재 DB) | 10,000,000 | 0.07% | 열림 |

현재 DB=1.0E7(상한 이하) → 재기동해도 `calibrate()`가 건드리지 않고 게이트가 열린다(테스트로 고정). 설계 분리(자동=보수적 차단, 1회성=정답값)는 원래 의도와 일치.

### 신규 지적 (차단 아님, 기록)
- MEDIUM: 같은 오염이 재발하면 클램프 후에도 MDD 13.1%>10%라 여전히 강제청산이 자동 발동한다(현행과 동일 수준, 악화 아님). 개선 여지: 클램프 발생 시 "미검증" 표시로 MDD 청산 판정만 보류하고 매수 차단은 유지 — 사람 확인 후 해제. BACKLOG 후보.
- LOW: paper+backtest 프로필 동시 활성 시 빈 충돌로 기동 실패(fail-fast, 무해, 운영상 조합 없음)
- LOW: 컨텍스트 로드 자동 테스트 부재 — 실제 기동 확인은 paper-ops-verifier 몫

### 다음 조치
1. 앱 재시작 필요 — 메모리의 옛 13,027,929가 남아있는 한 게이트 안 열림. 재시작 전 RUNNING 전환 금지.
2. 오염 최초 원인(KisBalanceClient 총자산 합산) 규명은 미해결 — BACKLOG 등록 권고.
3. 실전 전환은 이 변경과 무관하게 사람 게이트 G2 선행.
