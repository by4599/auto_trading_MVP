package com.trading.bucket;

import com.trading.risk.RiskLimitsProperties;
import com.trading.strategy.FilterProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 칸별 파라미터 분리 — A동(다일 추세추종)과 B동(당일 단타)이 정반대 값을 요구해
 * 전역 상수 하나로는 둘을 동시에 만족시킬 수 없다(ADR-001 개정 결정 1·2).
 *
 * 가장 중요한 불변식: <b>오버라이드가 없으면 기존 전역값과 완전히 동일</b>해야 한다 —
 * 설정을 넣기 전까지 모의투자·백테스트 동작이 바뀌면 안 되기 때문이다.
 */
@DisplayName("BucketParameterResolver — 칸별 오버라이드와 전역 폴백")
class BucketParameterResolverTest {

    private RiskLimitsProperties limits;
    private FilterProperties filters;
    private BucketParameters params;
    private BucketParameterResolver sut;

    @BeforeEach
    void setUp() {
        limits = new RiskLimitsProperties();
        filters = new FilterProperties();
        params = new BucketParameters();
        sut = new BucketParameterResolver(params, limits, filters);
    }

    private BucketParameters.Overrides override(StrategyBucket bucket) {
        BucketParameters.Overrides o = new BucketParameters.Overrides();
        params.getOverrides().put(bucket, o);
        return o;
    }

    @Test
    @DisplayName("오버라이드가 없으면 전 항목이 전역값과 같다 — 도입 전과 동작 동일")
    void falls_back_to_global_when_no_override() {
        limits.setRiskFractionPerTrade(0.01);
        limits.setAtrStopMultiplier(1.5);
        filters.getTrailingStop().setEnabled(true);
        filters.getTrailingStop().setArmProfitPct(0.03);
        filters.getTrailingStop().setTrailPct(0.01);

        for (StrategyBucket b : StrategyBucket.values()) {
            assertThat(sut.riskFractionPerTrade(b)).isEqualTo(0.01);
            assertThat(sut.atrStopMultiplier(b)).isEqualTo(1.5);
            assertThat(sut.trailing(b))
                    .isEqualTo(new BucketParameterResolver.Trailing(true, 0.03, 0.01));
        }
    }

    @Test
    @DisplayName("A동만 0.25R·ATR1.0으로 바꿔도 B동은 전역값을 유지한다")
    void per_bucket_override_does_not_leak_to_other_buckets() {
        limits.setRiskFractionPerTrade(0.01);
        limits.setAtrStopMultiplier(1.5);
        BucketParameters.Overrides a = override(StrategyBucket.VB);
        a.setRiskFractionPerTrade(0.0025);
        a.setAtrStopMultiplier(1.0);

        assertThat(sut.riskFractionPerTrade(StrategyBucket.VB)).isEqualTo(0.0025);
        assertThat(sut.atrStopMultiplier(StrategyBucket.VB)).isEqualTo(1.0);

        assertThat(sut.riskFractionPerTrade(StrategyBucket.EVENT)).isEqualTo(0.01);
        assertThat(sut.atrStopMultiplier(StrategyBucket.MIX)).isEqualTo(1.5);
    }

    @Test
    @DisplayName("트레일링은 항목별로 폴백된다 — 일부만 지정해도 나머지는 전역값")
    void trailing_falls_back_field_by_field() {
        filters.getTrailingStop().setEnabled(true);
        filters.getTrailingStop().setArmProfitPct(0.03);
        filters.getTrailingStop().setTrailPct(0.01);
        BucketParameters.Overrides a = override(StrategyBucket.VB);
        a.setTrailingArmProfitPct(0.01);   // A동만 arm 1%
        a.setTrailingTrailPct(0.03);       // A동만 trail 3%

        assertThat(sut.trailing(StrategyBucket.VB))
                .isEqualTo(new BucketParameterResolver.Trailing(true, 0.01, 0.03));
        assertThat(sut.trailing(StrategyBucket.EVENT))
                .isEqualTo(new BucketParameterResolver.Trailing(true, 0.03, 0.01));
    }

    @Test
    @DisplayName("bucket=null은 VB로 접힌다 — 칸 도입 이전 레거시·백테스트 경로")
    void null_bucket_folds_to_vb() {
        override(StrategyBucket.VB).setAtrStopMultiplier(1.0);

        assertThat(sut.atrStopMultiplier(null)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("전역값을 런타임에 바꾸면 오버라이드 없는 칸에 즉시 반영된다 (설정 UI 경로)")
    void global_change_reflects_in_non_overridden_buckets() {
        override(StrategyBucket.VB).setAtrStopMultiplier(1.0);

        limits.setAtrStopMultiplier(2.0);

        assertThat(sut.atrStopMultiplier(StrategyBucket.VB)).isEqualTo(1.0);   // 고정
        assertThat(sut.atrStopMultiplier(StrategyBucket.EVENT)).isEqualTo(2.0); // 따라감
    }
}
