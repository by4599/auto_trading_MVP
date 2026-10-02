package com.trading;

import com.trading.bucket.BucketParameterResolver;
import com.trading.bucket.BucketParameters;
import com.trading.bucket.BucketProperties;
import com.trading.bucket.StrategyBucket;
import com.trading.market.Candle;
import com.trading.market.MarketDataService;
import com.trading.risk.RiskLimitsProperties;
import com.trading.signal.Signal;
import com.trading.strategy.DonchianBreakoutStrategy;
import com.trading.strategy.DonchianProperties;
import com.trading.strategy.FilterProperties;
import com.trading.strategy.MaBreakoutProperties;
import com.trading.strategy.MovingAverageBreakoutStrategy;
import com.trading.strategy.MovingAverageCalculator;
import com.trading.strategy.RsiProperties;
import com.trading.strategy.ScalpingProperties;
import com.trading.strategy.ScalpingStrategy;
import com.trading.strategy.StrategyParameters;
import com.trading.strategy.VolatilityBreakoutStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-10-01 A동 전환 — 배포될 {@code application-paper.yml}이 검증된 설정(BACKTEST-DESIGN §15.7 D0)과
 * 같은지 고정한다: 돈치안(20/120) · 0.25R · ATR 1.0 · 트레일 arm1%/trail3% · 타임컷 제외 ·
 * 최대 20거래일 · 지수 MA120 ON, 그리고 당일 청산 3방식(B동) OFF.
 */
@DisplayName("paper 설정 — A동(돈치안) ON · B동 3방식 OFF · 지수 MA120")
class PaperTrendSleeveConfigTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T00:30:00Z"), ZoneOffset.UTC);

    /** 과거→최신 단조 상승 125봉 — 이평 정배열·돈치안 돌파·MA120 위 조건을 모두 만족하는 이력 */
    private static List<Candle> risingHistory() {
        List<Candle> list = new ArrayList<>();
        LocalDate base = LocalDate.of(2026, 3, 1);
        for (int i = 0; i < 125; i++) {
            double px = 100 + i;
            list.add(new Candle(base.plusDays(i), px, px, px, px, 1000));
        }
        return list;
    }

    private static List<Candle> liveTick(double price) {
        return List.of(new Candle(LocalDate.of(2026, 10, 2), 220, price + 1, 219, price, 1000));
    }

    @Test
    @DisplayName("B동 3방식은 꺼져 있다 — '켜져 있었다면 샀을' 입력에서도 신호가 없다")
    void b_sleeve_strategies_emit_no_signal() {
        FilterProperties filters = PaperProfileYaml.bind("trading.filters", FilterProperties.class);
        StrategyParameters vbParams = PaperProfileYaml.bind("trading.strategy", StrategyParameters.class);
        MaBreakoutProperties maProps = PaperProfileYaml.bind("trading.ma-breakout", MaBreakoutProperties.class);
        ScalpingProperties scalpProps = PaperProfileYaml.bind("trading.scalping", ScalpingProperties.class);

        assertThat(vbParams.isEnabled()).isFalse();
        assertThat(maProps.isEnabled()).isFalse();
        assertThat(scalpProps.isEnabled()).isFalse();
        assertThat(PaperProfileYaml.bind("trading.rsi", RsiProperties.class).isEnabled()).isFalse();

        // VB: 목표가 = 시가 100 + (110−90)×0.5 = 110, 현재가 120 → 켜져 있으면 매수
        List<Candle> vbBreakout = List.of(new Candle(LocalDate.of(2026, 10, 1), 100, 110, 90, 100, 1000),
                new Candle(LocalDate.of(2026, 10, 2), 100, 121, 99, 120, 1000));
        StrategyParameters vbOn = new StrategyParameters();
        assertThat(new VolatilityBreakoutStrategy(vbOn, filters).evaluate("005930", vbBreakout)).hasSize(1);
        assertThat(new VolatilityBreakoutStrategy(vbParams, filters).evaluate("005930", vbBreakout)).isEmpty();

        // 이평 정배열 돌파: 상승 이력 + 현재가 > MA20 → 켜져 있으면 매수
        MarketDataService mds = mock(MarketDataService.class);
        when(mds.getDailyCandles(anyString(), anyInt())).thenReturn(risingHistory());
        MaBreakoutProperties maOn = new MaBreakoutProperties();
        assertThat(new MovingAverageBreakoutStrategy(mds, new MovingAverageCalculator(), CLOCK, maOn)
                .evaluate("005930", liveTick(230))).hasSize(1);
        assertThat(new MovingAverageBreakoutStrategy(mds, new MovingAverageCalculator(), CLOCK, maProps)
                .evaluate("005930", liveTick(230))).isEmpty();

        assertThat(new ScalpingStrategy(scalpProps).evaluate("005930", vbBreakout)).isEmpty();
    }

    @Test
    @DisplayName("A동 돈치안은 켜져 있고 검증값(고가 20일·추세 MA120) — 돌파하면 TREND 칸 매수 신호")
    void donchian_is_on_with_validated_parameters() {
        DonchianProperties props = PaperProfileYaml.bind("trading.donchian", DonchianProperties.class);
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getLookback()).isEqualTo(20);
        assertThat(props.getTrendMaPeriod()).isEqualTo(120);

        MarketDataService mds = mock(MarketDataService.class);
        when(mds.getDailyCandles(anyString(), anyInt())).thenReturn(risingHistory());
        List<Signal> signals = new DonchianBreakoutStrategy(mds, new MovingAverageCalculator(), CLOCK, props)
                .evaluate("005930", liveTick(230));

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).isBuy()).isTrue();
        assertThat(signals.get(0).getBucket()).isEqualTo(StrategyBucket.TREND);
    }

    @Test
    @DisplayName("지수 추세 필터 ON · MA120 (기본값 200이 아니다)")
    void index_trend_filter_on_with_ma120() {
        FilterProperties filters = PaperProfileYaml.bind("trading.filters", FilterProperties.class);

        assertThat(filters.getIndexTrend().isEnabled()).isTrue();
        assertThat(filters.getIndexTrend().getMaPeriod()).isEqualTo(120);
    }

    @Test
    @DisplayName("TREND 칸 사이징·출구 = D0/P3 — B동 칸은 전역값 그대로")
    void trend_bucket_parameters_match_backtest_d0() {
        BucketParameterResolver resolver = new BucketParameterResolver(
                PaperProfileYaml.bind("trading.bucket-params", BucketParameters.class),
                new RiskLimitsProperties(),
                PaperProfileYaml.bind("trading.filters", FilterProperties.class));

        assertThat(resolver.riskFractionPerTrade(StrategyBucket.TREND)).isEqualTo(0.0025);
        assertThat(resolver.atrStopMultiplier(StrategyBucket.TREND)).isEqualTo(1.0);
        assertThat(resolver.trailing(StrategyBucket.TREND))
                .isEqualTo(new BucketParameterResolver.Trailing(true, 0.01, 0.03));
        assertThat(resolver.multiDayHold(StrategyBucket.TREND)).isTrue();
        assertThat(resolver.maxHoldDays(StrategyBucket.TREND)).isEqualTo(20);

        assertThat(resolver.riskFractionPerTrade(StrategyBucket.VB)).isEqualTo(0.01);
        assertThat(resolver.multiDayHold(StrategyBucket.VB)).isFalse();
        assertThat(resolver.maxHoldDays(StrategyBucket.VB)).isZero();
    }

    @Test
    @DisplayName("칸 스위치 — TREND ON(400만원 = ADR 40% × 원금 1,000만원), EVENT·MIX OFF")
    void bucket_switch_trend_on_b_sleeve_off() {
        BucketProperties props = PaperProfileYaml.bucketProperties();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.isBucketActive(StrategyBucket.TREND)).isTrue();
        assertThat(props.isBucketActive(StrategyBucket.EVENT)).isFalse();
        assertThat(props.isBucketActive(StrategyBucket.MIX)).isFalse();
        assertThat(props.allocationOf(StrategyBucket.TREND)).isEqualTo(0.40 * 10_000_000);
    }
}
