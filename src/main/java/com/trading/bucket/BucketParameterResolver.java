package com.trading.bucket;

import com.trading.risk.RiskLimitsProperties;
import com.trading.strategy.FilterProperties;
import org.springframework.stereotype.Component;

/**
 * "이 칸에 적용할 값은 무엇인가"의 단일 출처 — 칸별 오버라이드가 있으면 그것, 없으면 전역값.
 *
 * 사이징·손절·트레일링이 각자 전역 홀더를 직접 읽던 것을 이 한 곳으로 모았다. 칸을 모르는
 * 호출부(레거시 경로·백테스트)는 bucket=null로 부르면 되고, 그 경우 {@link StrategyBucket#orDefault}가
 * VB로 접은 뒤 오버라이드가 없으면 전역값이 그대로 나온다 — 즉 <b>기존 동작과 동일</b>하다.
 */
@Component
public class BucketParameterResolver {

    private final BucketParameters params;
    private final RiskLimitsProperties limits;
    private final FilterProperties filters;

    public BucketParameterResolver(BucketParameters params,
                                   RiskLimitsProperties limits,
                                   FilterProperties filters) {
        this.params = params;
        this.limits = limits;
        this.filters = filters;
    }

    /** 1R 비율 — A동 0.25%, B동 1.0% 처럼 칸마다 다르다 */
    public double riskFractionPerTrade(StrategyBucket bucket) {
        Double v = params.forBucket(bucket).getRiskFractionPerTrade();
        return v != null ? v : limits.getRiskFractionPerTrade();
    }

    /** ATR 손절 배수 — 다일 보유는 1.0, 당일 단타는 1.5 */
    public double atrStopMultiplier(StrategyBucket bucket) {
        Double v = params.forBucket(bucket).getAtrStopMultiplier();
        return v != null ? v : limits.getAtrStopMultiplier();
    }

    /** 트레일링 3요소를 한 번에 — 부분 오버라이드도 항목별로 폴백된다 */
    public Trailing trailing(StrategyBucket bucket) {
        BucketParameters.Overrides o = params.forBucket(bucket);
        FilterProperties.TrailingStop g = filters.getTrailingStop();
        return new Trailing(
                o.getTrailingEnabled()      != null ? o.getTrailingEnabled()      : g.isEnabled(),
                o.getTrailingArmProfitPct() != null ? o.getTrailingArmProfitPct() : g.getArmProfitPct(),
                o.getTrailingTrailPct()     != null ? o.getTrailingTrailPct()     : g.getTrailPct());
    }

    /**
     * 이 칸이 다일 보유인가 — true면 15:15 타임컷에서 제외한다.
     * 기본 false: 지정하지 않은 칸은 종전대로 당일 청산된다.
     */
    public boolean multiDayHold(StrategyBucket bucket) {
        Boolean v = params.forBucket(bucket).getMultiDayHold();
        return v != null && v;
    }

    /** 최대 보유 거래일 — 0 이하면 제한 없음(기본) */
    public int maxHoldDays(StrategyBucket bucket) {
        Integer v = params.forBucket(bucket).getMaxHoldDays();
        return v != null ? v : 0;
    }

    public record Trailing(boolean enabled, double armProfitPct, double trailPct) {}
}
