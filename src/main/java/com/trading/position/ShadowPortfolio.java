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
        stateRepository.findById(PortfolioState.KEY_PEAK_EQUITY)
                .ifPresent(state -> {
                    double stored = state.getStateValue();
                    double calibrated = calibrator.calibrate(stored);
                    peakEquity = calibrated;
                    if (calibrated < stored) {
                        stateRepository.save(PortfolioState.of(PortfolioState.KEY_PEAK_EQUITY, calibrated));
                        log.error("[ShadowPortfolio] 오염된 peakEquity 교정: {} → {} (실측 근거로 정정, 영속화 완료)",
                                stored, calibrated);
                    } else {
                        log.info("[ShadowPortfolio] peakEquity 복원: {}", peakEquity);
                    }
                });
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
