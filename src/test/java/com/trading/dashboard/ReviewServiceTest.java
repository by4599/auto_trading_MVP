package com.trading.dashboard;

import com.trading.market.Candle;
import com.trading.market.KisProperties;
import com.trading.market.MarketDataService;
import com.trading.research.DisclosureItem;
import com.trading.research.DisclosureRepository;
import com.trading.research.NewsRepository;
import com.trading.strategy.FilterProperties;
import com.trading.strategy.StrategyParameters;
import com.trading.universe.TradingUniverseItem;
import com.trading.universe.TradingUniverseRepository;
import com.trading.universe.TradingUniverseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 인터페이스(리포지토리·MarketDataService)만 목킹, 구체 클래스는 실객체 —
 * Java 25 Mockito 제약. QuoteCacheService는 KIS 미설정 상태로 두어
 * 현재가 조회 경로가 실행되지 않게 한다 (검토 로직만 검증).
 */
class ReviewServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);
    private static final Clock FIXED = Clock.fixed(
            ZonedDateTime.of(TODAY.atTime(10, 0), KST).toInstant(), KST);

    private TradingUniverseRepository universeRepository;
    private MarketDataService marketDataService;
    private DisclosureRepository disclosureRepository;
    private NewsRepository newsRepository;
    private FilterProperties filters;
    private ReviewService sut;

    @BeforeEach
    void setUp() {
        universeRepository = mock(TradingUniverseRepository.class);
        marketDataService = mock(MarketDataService.class);
        disclosureRepository = mock(DisclosureRepository.class);
        newsRepository = mock(NewsRepository.class);
        when(newsRepository.findByStockCodeOrderByPublishedAtDesc(anyString())).thenReturn(List.of());
        when(disclosureRepository.findByStockCodeAndDisclosedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());

        filters = new FilterProperties();
        sut = new ReviewService(
                new TradingUniverseService(universeRepository),
                marketDataService,
                new QuoteCacheService(null, new KisProperties()), // 미설정 — 현재가 경로 미실행
                new StrategyParameters(),
                filters,
                disclosureRepository,
                newsRepository,
                FIXED);
    }

    private void universeWith(String... codes) {
        when(universeRepository.findAll()).thenReturn(
                List.of(codes).stream().map(c -> TradingUniverseItem.of(c, "이름" + c)).toList());
    }

    private static List<Candle> candles(LocalDate lastDate) {
        return List.of(
                new Candle(lastDate.minusDays(1), 69_500, 71_000, 69_000, 70_500, 1_000),
                new Candle(lastDate,              70_000, 70_800, 69_900, 70_600, 500));
    }

    @Test
    void target_price_uses_yesterday_range_and_today_open() {
        universeWith("005930");
        when(marketDataService.getRecentCandles("005930")).thenReturn(candles(TODAY));

        List<Map<String, Object>> out = sut.candidates();

        assertThat(out).hasSize(1);
        Map<String, Object> e = out.get(0);
        assertThat(e.get("targetPrice")).isEqualTo(71_000L); // 70000 + (71000-69000)*0.5
        assertThat(e.get("yesterdayHigh")).isEqualTo(71_000L);
        assertThat(e.get("todayOpen")).isEqualTo(70_000L);
        assertThat(e.get("k")).isEqualTo(0.5);
    }

    @Test
    void day_base_is_cached_per_day_single_candle_fetch() {
        universeWith("005930");
        when(marketDataService.getRecentCandles("005930")).thenReturn(candles(TODAY));

        sut.candidates();
        sut.candidates();

        verify(marketDataService, times(1)).getRecentCandles("005930");
    }

    @Test
    void before_market_open_target_is_null_and_retried_next_call() {
        universeWith("005930");
        when(marketDataService.getRecentCandles("005930"))
                .thenReturn(candles(TODAY.minusDays(1)));  // 마지막 봉이 어제 → 개장 전

        List<Map<String, Object>> out = sut.candidates();

        assertThat(out.get(0).get("targetPrice")).isNull();

        // 캐시에 저장되지 않았으므로 다음 호출에서 다시 시도한다
        sut.candidates();
        verify(marketDataService, times(2)).getRecentCandles("005930");
    }

    @Test
    void zero_open_price_before_market_yields_null_target() {
        // KIS는 개장 전 당일 봉의 시가를 0으로 반환한다 — 목표가를 계산하면 안 된다
        universeWith("005930");
        when(marketDataService.getRecentCandles("005930")).thenReturn(List.of(
                new Candle(TODAY.minusDays(1), 69_500, 71_000, 69_000, 70_500, 1_000),
                new Candle(TODAY, 0, 0, 0, 70_500, 0)));

        List<Map<String, Object>> out = sut.candidates();

        assertThat(out.get(0).get("targetPrice")).isNull();
        assertThat(out.get(0).get("brokeOut")).isNull();
    }

    @Test
    void disclosure_cooldown_active_state_is_reported() {
        universeWith("005930");
        when(marketDataService.getRecentCandles("005930")).thenReturn(candles(TODAY));
        filters.getDisclosureCooldown().setEnabled(true); // cooldownDays 기본 5

        DisclosureItem event = DisclosureItem.of("005930", "삼성전자", "R1",
                "유상증자결정", TODAY.minusDays(2), "NEGATIVE", "CAPITAL_INCREASE");
        DisclosureItem routine = DisclosureItem.of("005930", "삼성전자", "R2",
                "지분보고", TODAY.minusDays(1), "NEUTRAL", "INSIDER_OWNERSHIP");
        when(disclosureRepository.findByStockCodeAndDisclosedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(event, routine));

        List<Map<String, Object>> out = sut.candidates();

        @SuppressWarnings("unchecked")
        Map<String, Object> cooldown = (Map<String, Object>)
                ((Map<String, Object>) out.get(0).get("filters")).get("disclosureCooldown");
        assertThat(cooldown.get("active")).isEqualTo(true);
        assertThat(cooldown.get("eventType")).isEqualTo("CAPITAL_INCREASE"); // 정례 유형은 무시
        assertThat(cooldown.get("until")).isEqualTo(TODAY.plusDays(3).toString()); // 공시일+5일
    }

    @Test
    void candle_fetch_failure_yields_null_target_but_entry_still_listed() {
        universeWith("005930", "000660");
        when(marketDataService.getRecentCandles("005930")).thenThrow(new RuntimeException("KIS 오류"));
        when(marketDataService.getRecentCandles("000660")).thenReturn(candles(TODAY));

        List<Map<String, Object>> out = sut.candidates();

        assertThat(out).hasSize(2);
        assertThat(out.get(0).get("targetPrice")).isNull();
        assertThat(out.get(1).get("targetPrice")).isEqualTo(71_000L);
    }
}
