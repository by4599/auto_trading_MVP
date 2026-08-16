package com.trading.backtest;

import com.trading.risk.RiskLimits;
import com.trading.risk.RiskLimitsProperties;
import com.trading.strategy.FilterProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 랩이 스윕하는 실행 손잡이 묶음 — 필터·손절배수/사이징·출구(타임컷·최대보유)·거래비용.
 *
 * <p>랩마다 흩어져 있던 세팅/복원 시퀀스를 한곳에 모은 것이다. 값과 순서는 이전과 같다
 * (복원 누락이 곧 다음 프로필 오염이므로 순서를 바꾸지 않는다).
 */
@Component
@Profile("backtest")
public class ExecutionKnobs {

    private final FilterProperties filters;
    private final RiskLimitsProperties riskLimits;
    private final ExitLabProperties exitLab;
    private final BacktestCostProperties costProperties;

    public ExecutionKnobs(FilterProperties filters,
                          RiskLimitsProperties riskLimits,
                          ExitLabProperties exitLab,
                          BacktestCostProperties costProperties) {
        this.filters = filters;
        this.riskLimits = riskLimits;
        this.exitLab = exitLab;
        this.costProperties = costProperties;
    }

    /** 필터 A/B에서 개별 필터를 직접 켜는 랩용 (트레일링·공시 쿨다운·거래량 확인 등) */
    public FilterProperties filters() {
        return filters;
    }

    public void allFiltersOff() {
        filters.getEntryWindow().setEnabled(false);
        filters.getVolumeConfirm().setEnabled(false);
        filters.getTrailingStop().setEnabled(false);
        filters.getTrailingStop().setArmProfitPct(0.03);
        filters.getIndexRegime().setEnabled(false);
        filters.getDisclosureCooldown().setEnabled(false);
        filters.getDisclosureCooldown().setCooldownDays(5);
    }

    public void applyExitProfile(ExitProfile p) {
        riskLimits.setAtrStopMultiplier(p.atrMult());
        exitLab.setTimecutEnabled(p.timecut());
        exitLab.setMaxHoldDays(p.maxHoldDays());
        allFiltersOff();
        if (p.trailEnabled()) {
            filters.getTrailingStop().setEnabled(true);
            filters.getTrailingStop().setArmProfitPct(p.trailArmPct());
            filters.getTrailingStop().setTrailPct(p.trailPct());
        }
    }

    public void resetExitProfile() {
        riskLimits.setAtrStopMultiplier(RiskLimits.ATR_STOP_MULTIPLIER);
        exitLab.resetDefaults();
        allFiltersOff();
    }

    /** 사이징 스윕 — 1R 비율과 동시보유 상한 */
    public void applySizing(double riskFraction, int maxPositions) {
        riskLimits.setRiskFractionPerTrade(riskFraction);
        riskLimits.setMaxPositionCount(maxPositions);
    }

    public void resetSizing() {
        riskLimits.setRiskFractionPerTrade(RiskLimits.RISK_FRACTION_PER_TRADE);
        riskLimits.setMaxPositionCount(RiskLimits.MAX_POSITION_COUNT);
    }

    /** §14 검증 후보 공통 고정 — 출구 P3 다일 트레일링 + RR1 사이징(0.5R·동시5) */
    public void applyP3ExitWithHalfRisk() {
        applyExitProfile(ExitProfile.P3);
        applySizing(0.005, 5);
    }

    /** 지수 추세 진입금지 필터 (IndexTrendRule) */
    public void applyIndexTrend(boolean enabled, int maPeriod) {
        filters.getIndexTrend().setEnabled(enabled);
        filters.getIndexTrend().setMaPeriod(maPeriod);
    }

    public boolean isIndexTrendEnabled() {
        return filters.getIndexTrend().isEnabled();
    }

    public int indexTrendMaPeriod() {
        return filters.getIndexTrend().getMaPeriod();
    }

    /** 고정 조건 복원 — 지수 추세 필터 OFF·200, 출구·사이징 상수 복원 */
    public void restoreRegimeDefaults() {
        applyIndexTrend(false, 200);
        resetExitProfile();
        resetSizing();
    }

    public void setRoundTripCost(double roundTrip) {
        costProperties.setRoundTripCost(roundTrip);
    }

    public void resetCostDefaults() {
        costProperties.resetDefaults();
    }

    /**
     * 세팅 <b>직후 홀더에서 다시 읽은</b> 실제 적용 비용 — 목표값을 그대로 옮겨 적으면
     * 역산 버그(F-C1)를 리포트가 잡지 못한다.
     */
    public AppliedCost appliedCost() {
        return new AppliedCost(costProperties.getSlippageRate(), costProperties.roundTripCost());
    }
}
