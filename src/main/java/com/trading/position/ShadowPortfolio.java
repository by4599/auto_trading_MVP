package com.trading.position;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// ADR 2.2: peakEquity는 단조 증가만. 08:30 DailyLossRule 리셋과 완전 분리.
// "영구 보존" 요건을 재시작 너머까지 보장하기 위해 portfolio_state에 영속화한다.
// RiskMonitor(MDD 청산 트리거)와 GlobalEquityStopRule(매수 거부)이 이 값을 참조한다.
// backtest 프로파일에서도 로딩된다 — MDD 매수 게이트 패리티 (BacktestRunner가 봉마다 tick() 호출).
@Component
@Profile({"paper", "backtest"})
public class ShadowPortfolio {

    private static final Logger log = LoggerFactory.getLogger(ShadowPortfolio.class);

    private final PositionManager positionManager;
    private final PortfolioStateRepository stateRepository;
    private final PeakEquityCalibrator calibrator;
    private volatile double peakEquity = 0.0;
    /** 전고점이 클램프 교정된 뒤 사람 확인을 못 받은 상태 — MDD 자동 강제청산만 보류시킨다 */
    private volatile boolean peakUnverified = false;

    public ShadowPortfolio(PositionManager positionManager,
                           PortfolioStateRepository stateRepository,
                           PeakEquityCalibrator calibrator) {
        this.positionManager = positionManager;
        this.stateRepository = stateRepository;
        this.calibrator = calibrator;
    }

    /**
     * 저장된 전고점을 복원하되, 실측 근거(daily_equity)상 불가능한 값이면 교정해서 다시 저장한다.
     * peakEquity는 단조 증가라 스스로 낮아지지 못하므로 오염이 영구화된다 —
     * 기동 시 한 번 실측과 대조하는 것이 유일한 복구 지점이다 (2026-08-12 사고).
     */
    @PostConstruct
    void restore() {
        restoreUnverifiedMark();
        stateRepository.findById(PortfolioState.KEY_PEAK_EQUITY)
                .ifPresent(state -> {
                    double stored = state.getStateValue();
                    double calibrated = calibrator.calibrate(stored);
                    peakEquity = calibrated;
                    if (calibrated < stored) {
                        markPeakUnverified();
                        stateRepository.save(PortfolioState.of(PortfolioState.KEY_PEAK_EQUITY, calibrated));
                        log.error("[ShadowPortfolio] 오염된 peakEquity 교정: {} → {} (실측 근거로 정정, 영속화 완료)",
                                stored, calibrated);
                    } else {
                        log.info("[ShadowPortfolio] peakEquity 복원: {}", peakEquity);
                    }
                });
    }

    /**
     * 미검증 표시는 재시작을 넘어 유지된다 — 클램프는 기동 때 한 번만 일어나므로,
     * 메모리에만 두면 다음 재시작이 사람 확인 없이 자동청산을 되살린다.
     */
    private void restoreUnverifiedMark() {
        peakUnverified = stateRepository.findById(PortfolioState.KEY_PEAK_EQUITY_UNVERIFIED)
                .map(s -> s.getStateValue() > 0)
                .orElse(false);
        if (peakUnverified) {
            log.error("[ShadowPortfolio] 전고점이 '미검증'으로 표시돼 있다 — "
                    + "MDD 자동 강제청산은 사람 확인(/api/trading/peak-equity-ack)까지 보류된다");
        }
    }

    private void markPeakUnverified() {
        peakUnverified = true;
        stateRepository.save(PortfolioState.of(PortfolioState.KEY_PEAK_EQUITY_UNVERIFIED, 1));
    }

    /**
     * 전고점이 클램프 교정된 미검증 값인가.
     *
     * <p>클램프는 "저장된 전고점이 실측 근거로 불가능하다"는 뜻이라, 상한까지 낮춘 값도
     * 여전히 실제 전고점보다 높을 수 있다(실측: 상한 11,500,000 vs 실자산 10,000,000 → MDD 13.04%).
     * 그 값으로 계산한 MDD는 <b>과대평가</b>이므로 {@code RiskMonitor}는 자동 강제청산을 보류하고
     * 사람 확인을 기다린다. 반면 매수 차단({@code GlobalEquityStopRule})은 그대로 둔다 —
     * 보류가 위험을 늘리지 않도록 신규 진입은 계속 막는 쪽이 보수적이다.
     */
    public boolean isPeakUnverified() {
        return peakUnverified;
    }

    /**
     * 사람이 전고점을 확인했다는 신호 (대시보드/API) — 이 신호가 있어야 MDD 자동 강제청산이 복귀한다.
     * 전고점 값 자체는 건드리지 않는다: 값 정정은 별개의 조작이고, 여기서는 "확인했다"만 기록한다.
     *
     * @return 실제로 보류를 해제했으면 true, 원래 보류가 없었으면 false
     */
    public boolean acknowledgePeakEquity() {
        if (!peakUnverified) return false;
        stateRepository.save(PortfolioState.of(PortfolioState.KEY_PEAK_EQUITY_UNVERIFIED, 0));
        peakUnverified = false;
        log.warn("[ShadowPortfolio] 전고점 미검증 표시 해제(사람 확인) — MDD 자동 강제청산 재개. 현재 전고점={}",
                peakEquity);
        return true;
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        try {
            Account account = positionManager.snapshotAccount();

            // 낡은(폴백) 스냅샷으로는 전고점을 갱신하지 않는다 — RiskMonitor·StopLossMonitor와 같은
            // 데이터 품질 게이트. 전고점은 리셋이 없어 한 번 잘못 올리면 영구히 남는다.
            if (!account.isFresh()) {
                log.debug("[ShadowPortfolio] 계좌 스냅샷이 낡음(잔고 API 폴백) — peakEquity 갱신 건너뜀");
                return;
            }

            double current = account.getTotalAssetValue();
            if (current <= 0 || current <= peakEquity) return;

            // 신선한 값이어도 실측 근거상 불가능하게 크면 갱신하지 않는다 (잔고 오독 1회 → 영구 오염 차단)
            if (calibrator.isImplausible(current)) {
                log.warn("[ShadowPortfolio] 총자산 {}이 실측 근거상 비정상 — peakEquity 갱신 건너뜀 (현재값={})",
                        current, peakEquity);
                return;
            }

            peakEquity = current;
            stateRepository.save(PortfolioState.of(PortfolioState.KEY_PEAK_EQUITY, current));
            log.debug("[ShadowPortfolio] peakEquity 갱신: {}", peakEquity);
        } catch (Exception e) {
            log.warn("[ShadowPortfolio] tick 오류 — peakEquity 유지 (현재값={}): {}", peakEquity, e.getMessage());
        }
    }

    public double getPeakEquity() {
        return peakEquity;
    }

    /** 백테스트 런 간 상태 초기화 전용 — 이전 런의 peakEquity가 다음 런으로 누출되는 것을 막는다. */
    public void reset() {
        peakEquity = 0.0;
    }
}
