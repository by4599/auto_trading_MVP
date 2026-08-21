# [ADR-001] 멀티 슬리브 자금 배분 및 이원화 리스크 엔진 아키텍처

> **개정 2026-08-07 (사용자 승인) — Sleeve A 개시.** §2.1 자금 배분과 §2.2 낙폭 방어선이
> 아래와 같이 대체됐다. 개정 배경·계산 근거는 `_workspace/ADR-001-revision-draft.md`,
> 검증 근거는 `docs/BACKTEST-DESIGN.md` §14~15.

## 1. 콘텍스트 (Context)

단일 파이프라인 구조는 추세 필터와 모멘텀 스크리너를 AND로 결합할 때 신호 고갈 및 실행
타이밍 모순을 유발한다. 또한 장중 변동성 제어(일간 리스크)와 누적 자산 보호(MDD)는
생명주기가 서로 다름에도, 단일 청산 스위치만 공유하면 전략 간 철학 충돌과 동시성 우회
위험이 생긴다. 이를 자금 관리·실행 레이어 수준에서 격리하고, 한국 주식시장 결제 제도
(T+2)의 제약을 반영한다.

이 문서는 이전 리뷰 사이클에서 발견된 아래 결함들을 모두 반영한 최종본이다:
Sleeve 정체성 혼란, T+2 파킹 리스크, 청산/평시 매도 경로 혼선, Trim과 전량청산의
종단 상태 충돌, **두 청산 경로 간 상호 배제 부재**, Trim 실패 시 에스컬레이션 부재,
Reconciler의 Trim 상태 미인식.

---

## 2. 결정 사항 (Decision Outcomes)

### 2.1. 슬리브 분리 및 유휴 자금 운영 제한

| 항목 | 개정 전 | **개정 후 (2026-08-07)** |
|---|---|---|
| Sleeve A (다일 추세추종) | 구현 보류 · 자금 0% | **개시. 자금 40%** (칸 `TREND`) |
| Sleeve B (당일 모멘텀 단타) | 최대 30% | **최대 30%** (변동 없음) |
| 유휴 자금 | 70~100% 현금 | **30% 현금** |

현금 30%는 여전히 **현금으로만** 대기한다 — KOFR 등 파킹형 ETF 포함 대체 포지션 금지는
그대로다(T+2 결제 지연이 진입 타이밍과 충돌한다는 원 근거는 유효).

**개정 사유**: 보류의 전제가 뒤집혔다. 당일 청산 3방식(VB·MA돌파·스캘핑)은 백테스트
전량 불합격인 반면, 다일 보유 + 트레일링 조합은 약세장 6.5년(24창)에서 조건부 통과했다
(BACKTEST-DESIGN §15.5~15.7). 즉 **검증된 후보가 막아둔 쪽에만 있다.**

**Sleeve A 진입 자격 (신설)**: BACKTEST-DESIGN §4를 통과하고 **약세장 스트레스(6.5년)까지**
통과한 조합만 올린다. 현재 유일한 해당 후보는 돈치안 돌파 + P3 출구 + 지수 MA120 필터 + 0.25R.
**단 잠정**이며, 칸은 `trend-enabled=false`로 잠겨 있다.

> **개정 2026-08-10 — 승격 조건에서 "실측 슬리피지"를 뺀다.** 모의투자 체결조회가 실제
> 체결에도 빈 목록을 주는 것이 확정돼(BACKTEST-DESIGN §16.6) 매도 체결가를 얻을 수 없고,
> 따라서 왕복 슬리피지를 모의에서 측정할 방법이 없다. 이 관문을 남겨두면 영원히 열리지
> 않으므로 **실전 계좌 전환 이후 항목으로 이관**한다. 그 대신 A동 실매매 승격은
> **게이트 G2(사람 승인)** 가 단독 관문이며, 승인 시 "비용 가정 0.41%는 미검증"이라는
> 사실을 명시적으로 감수하는 것으로 한다.

### 2.2. 이원화된 자산 방어선과 `peakEquity` 생명주기

- **당일 손익 가드 (`DailyLossRule`)**: 매일 08:30 리셋, -3%(신규매수 차단) / -5%(강제청산 트리거) 2단계.
- **누적 자산 가드 (`Global Equity Stop`)**: 전고점 대비 MDD > **8%** (개정 2026-08-07,
  기존 10%에서 하향). 리셋 없음, 영구 추적.
- **슬리브별 낙폭 상한 (신설, 2026-08-07)**: A동 자체 −12% / B동 자체 −20% 도달 시
  **그 슬리브만** 신규매수 중지 + 보유 정리하고 다른 슬리브는 계속 운영한다.
  이유: 각 슬리브는 자기 칸 자산으로 사이징하므로 계좌 기여 낙폭이 크게 다르다 —
  A동(검증) 1.7~5.2%p vs B동(불합격, 자체 82.3% 실측) **24.7%p**. 계좌 단일 경보만 두면
  **미검증 B동 때문에 검증된 A동까지 청산**된다. A동 상한 12%는 검증에서 관측된 최악(13.1%)
  바로 아래로, 검증 범위를 벗어나면 스스로 멈추게 하려는 값이다.
- **`peakEquity` 필드**: `ShadowPortfolio` 내 영구 보존. 매 1초 틱마다 실시간 자산 평가 후, 기존 값보다 **높을 때만** 갱신(단조 증가). 08:30 자동 리셋 대상 아님.

> **구조적 주석 (MDD 가드의 실질적 작동 범위)**
> ⚠ **개정으로 이 주석의 전제가 바뀌었다.** 투자 비중이 30% → 70%로 오르면 계좌 −8%에
> 도달하는 데 투자분 −11.4%면 충분하다(개정 전 −10%는 투자분 −33% 필요). 즉 계좌 가드가
> 실질적으로 살아난다. 아래는 개정 전 상황의 기록이다.
>
> Sleeve A 미구현 기간 동안 자산의 70%+ 가 현금이므로, 계좌 전체 MDD 10%가 발동하려면
> Sleeve B(자금 30%) 기준 -33% 이상 손실이 필요하다. 따라서 평시에는 `DailyLossRule`
> (-5%)이 실질적 방어선이며, `Global Equity Stop`은 시스템 오류·비정상 시장 충격에
> 대응하는 **최후의 보루**로 기능한다. 이는 설계 결함이 아니라 의도된 이중 방어이다.

### 2.3. 청산 경로: 공유 상태 머신 기반 이원화

**핵심 원칙**: 전량 청산과 부분 축소(Trim)는 서로 독립된 진입점을 갖되, **동일한 상태
머신을 공유하여 동시 실행을 물리적으로 차단**한다. "각자 안전하다"는 "함께 실행해도
안전하다"를 보장하지 않는다는 점을 이전 라운드에서 확인했으므로, 아래와 같이 상위
락을 명시한다.

```java
public enum LiquidationPhase {
    IDLE,               // 평시
    TRIMMING,           // 부분 축소 진행 중 (RUNNING 유지, 신규 매수는 정상)
    FULL_LIQUIDATING    // 전량 강제 청산 진행 중 (신규 주문 전면 차단)
}

@Component
public class LiquidationService {

    @Autowired private BrokerageApiClient brokerageClient;
    @Autowired private TradingStatusManager statusManager;
    @Autowired private NotificationService notifier;
    @Autowired private OpportunityCostLogger opportunityCostLogger;

    // 두 진입점이 공유하는 단일 상태 머신 (개별 AtomicBoolean 두 개를 쓰지 않음)
    private final AtomicReference<LiquidationPhase> phase =
        new AtomicReference<>(LiquidationPhase.IDLE);

    private final ExecutorService liquidationExecutor = Executors.newSingleThreadExecutor(
        r -> new Thread(r, "Emergency-Liquidation-Thread")
    );
    private final ExecutorService trimExecutor = Executors.newSingleThreadExecutor(
        r -> new Thread(r, "Position-Trim-Thread")
    );

    // ── 전량 강제 청산 (Global Equity Stop 전용) ──────────────────────
    public void triggerForceLiquidation() {
        // IDLE이든 TRIMMING이든 상관없이 FULL_LIQUIDATING으로 강제 승격.
        // 이미 FULL_LIQUIDATING이면 중복 진입 차단.
        LiquidationPhase previous = phase.getAndSet(LiquidationPhase.FULL_LIQUIDATING);
        if (previous == LiquidationPhase.FULL_LIQUIDATING) {
            log.info("ℹ️ [중복 청산 패스] 이미 전량 청산이 진행 중입니다.");
            return;
        }
        if (previous == LiquidationPhase.TRIMMING) {
            log.warn("⚠️ [승격] Trim 진행 중 전량 청산 요청 발생. Trim을 중단하고 전량 청산으로 전환합니다.");
            // Trim 스레드는 다음 루프에서 phase != TRIMMING을 감지하고 자체 종료함 (아래 3항 참조)
        }
        liquidationExecutor.submit(this::executeForceLiquidation);
    }

    private void executeForceLiquidation() {
        List<String> success = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            statusManager.changeMode(TradingMode.FORCE_LIQUIDATING);
            notifier.sendCritical("🚨 [강제청산 개시] 실제 잔고 기반 전량 청산을 시작합니다.");

            brokerageClient.cancelAllPendingOrders();          // 취소 먼저
            ActualAccountInfo actual = brokerageClient.getActualAccountAsset(); // 그다음 스냅샷

            for (ActualPosition pos : actual.getHoldings()) {
                if (pos.getQuantity() <= 0) continue;
                try {
                    executeWithRetry(pos.getTicker(), pos.getQuantity());
                    success.add(pos.getTicker());
                } catch (IndeterminateLiquidationException e) {
                    failed.add(pos.getTicker() + " [🔴 수동확인필수: " + e.getMessage() + "]");
                } catch (Exception e) {
                    failed.add(pos.getTicker() + " [⚫ 거부확인됨: " + e.getMessage() + "]");
                }
            }
            sendLiquidationReport(success, failed);
        } catch (Exception e) {
            log.error("🔥 청산 인프라 장애", e);
            notifier.sendCritical("🆘 청산 인프라 마비! 수동 개입 필요: " + e.getMessage());
        } finally {
            // 성공하든 실패하든 무조건 종단 상태로 수렴 (Trim 여부와 무관하게 최상위 우선순위)
            statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);
            phase.set(LiquidationPhase.FULL_LIQUIDATING); // 재가동 전까지 잠금 유지
        }
    }

    // ── 부분 축소 (Capacity Scaling 전용) ─────────────────────────────
    public void triggerPartialTrim(List<TrimTarget> targets) {
        // FULL_LIQUIDATING이 이미 진행/완료된 상태라면 Trim은 무의미하므로 즉시 거부
        if (!phase.compareAndSet(LiquidationPhase.IDLE, LiquidationPhase.TRIMMING)) {
            log.info("ℹ️ [Trim 패스] 현재 phase={} 상태이므로 Trim을 건너뜁니다.", phase.get());
            return;
        }
        trimExecutor.submit(() -> executePartialTrim(targets, 0));
    }

    private void executePartialTrim(List<TrimTarget> targets, int consecutiveFailures) {
        List<String> success = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            for (TrimTarget t : targets) {
                // 매 종목 처리 전, 상위(전량청산)로 승격됐는지 확인 후 즉시 자체 종료
                if (phase.get() == LiquidationPhase.FULL_LIQUIDATING) {
                    log.warn("⚠️ [Trim 중단] 전량 청산으로 승격되어 Trim 절차를 양보합니다.");
                    return; // FULL_LIQUIDATING 쪽 finally가 최종 상태를 책임짐
                }
                try {
                    executeWithRetry(t.getTicker(), t.getQuantity());
                    success.add(t.getTicker());
                } catch (Exception e) {
                    failed.add(t.getTicker());
                }
            }

            if (!failed.isEmpty()) {
                int totalFailures = consecutiveFailures + 1;
                notifier.sendCritical(String.format(
                    "⚠️ [Trim 부분 실패 %d회차] 성공: %s / 실패: %s", totalFailures, success, failed));

                // 에스컬레이션: N회 연속 실패 시 방치하지 않고 전량 청산으로 승격
                if (totalFailures >= 3) {
                    notifier.sendCritical("🆘 [Trim 3회 연속 실패] 위험 노출이 해소되지 않아 전량 청산으로 자동 승격합니다.");
                    phase.set(LiquidationPhase.IDLE); // 상태 초기화 후
                    triggerForceLiquidation();          // 정식 절차로 승격
                    return;
                }
            } else {
                notifier.sendCritical("✅ [Trim 완료] 초과 노출 정리 완료: " + success);
            }
        } finally {
            // 실패했지만 3회 미만이거나, 성공했다면 RUNNING 유지 — EMERGENCY_STOPPED로 전환하지 않음
            phase.compareAndSet(LiquidationPhase.TRIMMING, LiquidationPhase.IDLE);
        }
    }

    // 다른 컴포넌트(Reconciler 등)가 조회할 수 있는 공개 상태 확인 메서드
    public boolean isAnyLiquidationInProgress() {
        return phase.get() != LiquidationPhase.IDLE;
    }

    private void executeWithRetry(String ticker, int quantity) {
        int remaining = quantity;
        int attempts = 0;
        boolean balanceCheckFailed = false;
        while (remaining > 0 && attempts < 3) {
            attempts++;
            try {
                brokerageClient.sendMarketOrder(ticker, "SELL", remaining);
            } catch (Exception e) {
                log.error("❌ [{}] 주문 전송 실패 ({}회차)", ticker, attempts, e);
            }
            sleep(500);
            try {
                remaining = brokerageClient.getActualHoldingQuantity(ticker); // 실제 잔고 기준
                balanceCheckFailed = false;
            } catch (Exception e) {
                balanceCheckFailed = true;
                if (attempts >= 3) {
                    throw new IndeterminateLiquidationException("잔고 조회 연속 실패로 확인 불가");
                }
            }
        }
        if (remaining > 0 && !balanceCheckFailed) {
            throw new ExplicitRejectLiquidationException("거부/미체결로 잔여 " + remaining + "주");
        }
    }

    private void sendLiquidationReport(List<String> success, List<String> failed) {
        if (failed.isEmpty()) {
            notifier.sendCritical("✅ [강제청산 완수] 대상: " + success);
        } else {
            notifier.sendCritical(String.format(
                "⚠️ [강제청산 부분 실패]\n• 성공: %s\n• 실패: %s", success, failed));
        }
    }
}
```

**Trim 대상 선정 책임**: `TrimTarget` 목록은 `CapacityScalingEngine`이 생성한다. 선정 규칙은
"랭킹 하위 종목부터, 초과분만큼" 이며, 이 로직 자체는 본 ADR의 범위가 아니라 별도
설계 문서(추후 ADR-002)에서 정의한다. `LiquidationService`는 대상 선정에 관여하지 않는다.

**평시 전략 매도 경로**: Sleeve B의 15:15 타임컷, 추후 Sleeve A의 Trailing Stop 등
전략 고유의 익절/손절은 `LiquidationService`를 전면 우회하며 기존 `OrderEngine.execute()`
경로를 그대로 사용한다.

### 2.4. Reconciler의 청산 상태 인식

```java
@Component
public class ShadowPortfolioReconciler {
    @Autowired private LiquidationService liquidationService;
    // ...

    @Scheduled(fixedRate = 600000)
    public void reconcile() {
        TradingMode mode = statusManager.getCurrentMode();
        // EMERGENCY_STOPPED/FORCE_LIQUIDATING뿐 아니라 TRIMMING 중에도 API 경합을 피함
        if (mode == TradingMode.EMERGENCY_STOPPED
            || liquidationService.isAnyLiquidationInProgress()) {
            return;
        }
        // ... 보정 및 위험 재평가 로직
    }
}
```

### 2.5. 타임라인 정합성 및 실행 폴백

- **스냅샷 고정**: Sleeve B 스크리닝은 **09:30 정각 스냅샷**으로 고정. 전략명은
  '장 초반 모멘텀 스크리닝'으로 정의.
- **체결 방식**: Time Jitter 금지(백테스트-실전 타임라인 어긋남). **지정가 분할
  주문(Price Jitter)** 으로 슬리피지 관리.
- **미체결 폴백**: 09:33까지 미체결 잔량은 취소 후 **시장가로 강제 전환**하여
  당일 진입 원칙을 지킨다 (모멘텀 탈주로 인한 기회 상실 방지).

### 2.6. 기회비용 감사 로그

`Capacity Scaling` 또는 `MINIMUM_QUALITY_THRESHOLD` 미달로 드롭된 모든 신호는
`OpportunityCostLogger`에 종목코드·스코어·드롭 사유·당시 Capacity 상태와 함께
무조건 기록한다. 침묵 처리하지 않는다.

---

## 3. 남겨진 미결정 사항 (Explicitly Deferred)

이 ADR이 다루지 않는, 다음 라운드에서 별도로 결정해야 할 항목:

- `TrimTarget` 선정 알고리즘 (`CapacityScalingEngine`의 랭킹·수량 계산 로직) → ADR-002
- `MINIMUM_QUALITY_THRESHOLD` 값의 최초 산정 및 Walk-Forward 재검증 주기
- Price Jitter(지정가 그리드)의 가격 간격·주문 개수 파라미터
- Sleeve A 구현 시점의 자금 재배분 규칙 (현재는 미구현이므로 논외)

이 항목들에 대한 제안이 코드나 대화에 등장하면, **이 ADR의 결정 사항과 충돌하는지
먼저 명시**한 뒤 논의를 진행한다.

---

## 4. 상태 (Status)

**Accepted**

- 리스크 코어 동시성 검증: 완료 (멱등성 가드, 상태 머신 공유, 종목별 예외 격리)
- 청산 경로 이원화 및 상호 배제: 완료
- 자금 관리 레이어: 완료 (T+2 제약 반영)
- 타임라인/실행 정합성: 완료 (Price Jitter + 미체결 폴백)
- 남은 작업: 통합 테스트 스위트 구현 (진짜 멀티스레드 경합 재현), Sleeve B 스코어링
  로직 실장, ADR-002(Trim 선정 알고리즘)
