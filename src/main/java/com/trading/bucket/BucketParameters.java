package com.trading.bucket;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

/**
 * 자금 칸별 리스크·출구 파라미터 오버라이드 (2026-08-06).
 *
 * <p>지금까지 사이징(1R 비율)·ATR 손절 배수·트레일링은 <b>전역 상수 하나</b>였다. A동(다일
 * 추세추종)과 B동(당일 단타)을 동시에 굴리기로 하면서(ADR-001 개정 결정 1·2) 이 값들이
 * 칸마다 정반대가 됐다 — A동은 0.25R·ATR1.0·트레일 arm1%/trail3%, B동은 1.0R·ATR1.5·
 * arm3%/trail1%. 전역 하나로는 둘을 동시에 만족시킬 수 없다.
 *
 * <p><b>비워두면 아무것도 바뀌지 않는다.</b> 값을 지정하지 않은 칸·항목은
 * {@link com.trading.risk.RiskLimitsProperties}·{@link com.trading.strategy.FilterProperties}의
 * 전역값으로 폴백한다. 설정을 넣기 전까지 기존 동작(백테스트 포함)과 완전히 동일하다.
 *
 * <p>설정 예 (application-paper.yml):
 * <pre>
 * trading:
 *   bucket-params:
 *     overrides:
 *       VB:
 *         risk-fraction-per-trade: 0.0025
 *         atr-stop-multiplier: 1.0
 *         trailing-arm-profit-pct: 0.01
 *         trailing-trail-pct: 0.03
 * </pre>
 */
@ConfigurationProperties(prefix = "trading.bucket-params")
public class BucketParameters {

    private Map<StrategyBucket, Overrides> overrides = new EnumMap<>(StrategyBucket.class);

    public Map<StrategyBucket, Overrides> getOverrides() { return overrides; }
    public void setOverrides(Map<StrategyBucket, Overrides> overrides) {
        this.overrides = overrides != null ? overrides : new EnumMap<>(StrategyBucket.class);
    }

    /** 해당 칸의 오버라이드. 없으면 빈 값(전 항목 전역 폴백). */
    public Overrides forBucket(StrategyBucket bucket) {
        Overrides o = overrides.get(StrategyBucket.orDefault(bucket));
        return o != null ? o : Overrides.EMPTY;
    }

    /**
     * 항목별 null = "이 칸은 이 항목을 따로 정하지 않음" → 전역값 사용.
     * 래퍼 타입(Double/Boolean)을 쓰는 이유가 그것이다 — 0이나 false와 구분해야 한다.
     */
    public static class Overrides {

        static final Overrides EMPTY = new Overrides();

        private Double riskFractionPerTrade;
        private Double atrStopMultiplier;
        private Boolean trailingEnabled;
        private Double trailingArmProfitPct;
        private Double trailingTrailPct;
        private Boolean multiDayHold;
        private Integer maxHoldDays;

        public Double getRiskFractionPerTrade() { return riskFractionPerTrade; }
        public void setRiskFractionPerTrade(Double v) { this.riskFractionPerTrade = v; }

        public Double getAtrStopMultiplier() { return atrStopMultiplier; }
        public void setAtrStopMultiplier(Double v) { this.atrStopMultiplier = v; }

        public Boolean getTrailingEnabled() { return trailingEnabled; }
        public void setTrailingEnabled(Boolean v) { this.trailingEnabled = v; }

        public Double getTrailingArmProfitPct() { return trailingArmProfitPct; }
        public void setTrailingArmProfitPct(Double v) { this.trailingArmProfitPct = v; }

        public Double getTrailingTrailPct() { return trailingTrailPct; }
        public void setTrailingTrailPct(Double v) { this.trailingTrailPct = v; }

        /** true면 15:15 타임컷에서 제외된다 (다일 보유 칸) */
        public Boolean getMultiDayHold() { return multiDayHold; }
        public void setMultiDayHold(Boolean v) { this.multiDayHold = v; }

        /** 최대 보유 거래일 — 초과하면 종가 청산. null/0 이하면 제한 없음 */
        public Integer getMaxHoldDays() { return maxHoldDays; }
        public void setMaxHoldDays(Integer v) { this.maxHoldDays = v; }
    }
}
