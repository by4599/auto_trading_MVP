package com.trading.risk;

import org.springframework.stereotype.Component;

/**
 * RiskLimits 상수의 런타임 조정 가능 홀더 (설정 UI — TradingParamService가 갱신).
 *
 * 기본값은 RiskLimits 상수 — "기본값 원복"이 되돌아가는 단일 출처.
 * HTTP 스레드(설정 저장)와 스케줄러 스레드(RiskMonitor 등)가 동시에 읽고 쓰므로
 * 전 필드 volatile.
 */
@Component
public class RiskLimitsProperties {

    private volatile double dailyLossBlock       = RiskLimits.DAILY_LOSS_BLOCK;
    private volatile double dailyLossLiquidate   = RiskLimits.DAILY_LOSS_LIQUIDATE;
    private volatile double mddLimit             = RiskLimits.MDD_LIMIT;
    private volatile double riskFractionPerTrade = RiskLimits.RISK_FRACTION_PER_TRADE;
    private volatile double atrStopMultiplier    = RiskLimits.ATR_STOP_MULTIPLIER;
    private volatile double sizingMaxDistortion  = RiskLimits.SIZING_MAX_DISTORTION;
    private volatile double maxPositionWeight    = RiskLimits.MAX_POSITION_WEIGHT;
    private volatile int    maxPositionCount     = RiskLimits.MAX_POSITION_COUNT;

    public double getDailyLossBlock() { return dailyLossBlock; }
    public void setDailyLossBlock(double v) { this.dailyLossBlock = v; }

    public double getDailyLossLiquidate() { return dailyLossLiquidate; }
    public void setDailyLossLiquidate(double v) { this.dailyLossLiquidate = v; }

    public double getMddLimit() { return mddLimit; }
    public void setMddLimit(double v) { this.mddLimit = v; }

    public double getRiskFractionPerTrade() { return riskFractionPerTrade; }
    public void setRiskFractionPerTrade(double v) { this.riskFractionPerTrade = v; }

    public double getAtrStopMultiplier() { return atrStopMultiplier; }
    public void setAtrStopMultiplier(double v) { this.atrStopMultiplier = v; }

    public double getSizingMaxDistortion() { return sizingMaxDistortion; }
    public void setSizingMaxDistortion(double v) { this.sizingMaxDistortion = v; }

    public double getMaxPositionWeight() { return maxPositionWeight; }
    public void setMaxPositionWeight(double v) { this.maxPositionWeight = v; }

    public int getMaxPositionCount() { return maxPositionCount; }
    public void setMaxPositionCount(int v) { this.maxPositionCount = v; }
}
