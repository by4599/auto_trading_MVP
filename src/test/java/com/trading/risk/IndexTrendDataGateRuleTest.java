package com.trading.risk;

import com.trading.NotificationService;
import com.trading.backtest.MutableClock;
import com.trading.bucket.StrategyBucket;
import com.trading.market.Candle;
import com.trading.market.CandleHistoryClient;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.Account;
import com.trading.signal.Signal;
import com.trading.strategy.FilterProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 지수 추세 판정 불가 시 매수 보류(fail-closed) — 라이브 전용 관문.
 *
 * <p>{@link IndexTrendRule}은 판정 불가(empty)면 통과시킨다. 백테스트 워밍업 구간의 동작을 회귀
 * 앵커가 기억하고 있어서 그 계약은 바꾸지 않았다. 라이브에서는 그 "조용한 통과"가 곧 무발동
 * 사고라서, 이 관문이 판정 불가를 명시적으로 막는다.
 */
@DisplayName("IndexTrendDataGateRule — 지수 판정 불가면 신규 매수 보류")
class IndexTrendDataGateRuleTest {

    private FilterProperties filters;

    @BeforeEach
    void setUp() {
        filters = new FilterProperties();
        filters.getIndexTrend().setEnabled(true);
        filters.getIndexTrend().setMaPeriod(120);
    }

    private static Account account() {
        return new Account(10_000_000, 0.0, 0, List.of());
    }

    private static Signal trendBuy() {
        return Signal.buy("005930", "DONCHIAN", StrategyBucket.TREND);
    }

    private static IndexRegimeSource source(Optional<Boolean> verdict) {
        return new IndexRegimeSource() {
            @Override public Optional<Boolean> isBearishRegime() { return Optional.empty(); }
            @Override public Optional<Boolean> isBelowTrend(int maPeriod) { return verdict; }
        };
    }

    @Test
    @DisplayName("판정 불가(empty) + 필터 ON → 매수 거부, 사유에 '판정 불가'를 분명히 적는다")
    void rejects_buy_when_undetermined() {
        RiskResult result = new IndexTrendDataGateRule(filters, source(Optional.empty()))
                .validate(trendBuy(), account());

        assertThat(result.isPass()).isFalse();
        assertThat(result.getReason()).contains("지수 추세 판정 불가");
        assertThat(RiskRuleNameResolver.resolve(result.getReason())).isEqualTo("IndexTrendDataGateRule");
    }

    @Test
    @DisplayName("판정이 있으면(아래/위 모두) 통과 — 막을지는 IndexTrendRule이 정한다")
    void passes_when_verdict_exists() {
        assertThat(new IndexTrendDataGateRule(filters, source(Optional.of(true)))
                .validate(trendBuy(), account()).isPass()).isTrue();
        assertThat(new IndexTrendDataGateRule(filters, source(Optional.of(false)))
                .validate(trendBuy(), account()).isPass()).isTrue();
    }

    @Test
    @DisplayName("필터가 꺼져 있으면 관여하지 않는다")
    void passes_when_filter_off() {
        filters.getIndexTrend().setEnabled(false);

        assertThat(new IndexTrendDataGateRule(filters, source(Optional.empty()))
                .validate(trendBuy(), account()).isPass()).isTrue();
    }

    @Test
    @DisplayName("매도는 절대 막지 않는다 — 손절·트레일링·최대보유 매도가 이 경로를 탄다")
    void never_blocks_sells() {
        assertThat(new IndexTrendDataGateRule(filters, source(Optional.empty()))
                .validate(Signal.sell("005930", "TrailingStop"), account()).isPass()).isTrue();
    }

    @Test
    @DisplayName("실제 데이터원 + RiskEngine: 첫 조회 전 거부 → MA120 위 통과 → MA120 아래 거부")
    void end_to_end_with_real_source() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        clock.setTo(LocalDate.of(2026, 10, 1), LocalTime.of(9, 5));
        CandleHistoryClient kis = mock(CandleHistoryClient.class);   // 인터페이스만 목
        KisIndexRegimeSource src = new KisIndexRegimeSource(kis,
                new MarketCalendarService(new MarketCalendarProperties(), clock),
                filters, mock(NotificationService.class), clock);
        RiskEngine engine = new RiskEngine(List.of(
                new IndexTrendRule(filters, src), new IndexTrendDataGateRule(filters, src)));

        // 1) 기동 직후 — 아직 한 번도 못 받았다: IndexTrendRule만 있었다면 통과했을 자리
        assertThat(engine.check(trendBuy(), account()).isPass()).isFalse();

        // 2) 지수가 MA120 위 → 통과
        when(kis.fetchIndexDailyCandles(anyString(), any(), any()))
                .thenReturn(kospi(LocalDate.of(2026, 9, 30), 7_063, 7_200));
        src.refreshIfDue();
        assertThat(engine.check(trendBuy(), account()).isPass()).isTrue();

        // 3) 다음 거래일, 지수가 MA120 아래 → IndexTrendRule이 거부
        clock.setTo(LocalDate.of(2026, 10, 2), LocalTime.of(9, 5));
        when(kis.fetchIndexDailyCandles(anyString(), any(), any()))
                .thenReturn(kospi(LocalDate.of(2026, 10, 1), 7_063, 6_894));
        src.refreshIfDue();
        RiskResult blocked = engine.check(trendBuy(), account());
        assertThat(blocked.isPass()).isFalse();
        assertThat(RiskRuleNameResolver.resolve(blocked.getReason())).isEqualTo("IndexTrendRule");
    }

    /** lastDate까지 평일 200개 — 앞은 전부 base, 마지막 봉만 lastClose */
    private static List<Candle> kospi(LocalDate lastDate, double base, double lastClose) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = lastDate; dates.size() < 200; d = d.minusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) dates.add(0, d);
        }
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            double close = i == dates.size() - 1 ? lastClose : base;
            out.add(new Candle(dates.get(i), close, close, close, close, 0));
        }
        return out;
    }
}
