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
@Component
@Profile("paper")
public class ShadowPortfolio {

    private static final Logger log = LoggerFactory.getLogger(ShadowPortfolio.class);

    private final PositionManager positionManager;
    private final PortfolioStateRepository stateRepository;
    private volatile double peakEquity = 0.0;

    public ShadowPortfolio(PositionManager positionManager,
                           PortfolioStateRepository stateRepository) {
        this.positionManager = positionManager;
        this.stateRepository = stateRepository;
    }

    @PostConstruct
    void restore() {
        stateRepository.findById(PortfolioState.KEY_PEAK_EQUITY)
                .ifPresent(state -> {
                    peakEquity = state.getStateValue();
                    log.info("[ShadowPortfolio] peakEquity 복원: {}", peakEquity);
                });
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        try {
            double current = positionManager.snapshotAccount().getTotalAssetValue();
            if (current > peakEquity) {
                peakEquity = current;
                stateRepository.save(PortfolioState.of(PortfolioState.KEY_PEAK_EQUITY, current));
                log.debug("[ShadowPortfolio] peakEquity 갱신: {}", peakEquity);
            }
        } catch (Exception e) {
            log.warn("[ShadowPortfolio] tick 오류 — peakEquity 유지 (현재값={}): {}", peakEquity, e.getMessage());
        }
    }

    public double getPeakEquity() {
        return peakEquity;
    }
}
