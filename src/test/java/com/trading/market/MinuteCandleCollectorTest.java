package com.trading.market;

import com.trading.universe.TradingUniverseItem;
import com.trading.universe.TradingUniverseRepository;
import com.trading.universe.TradingUniverseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MinuteCandleCollectorTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 10);

    private CandleHistoryClient candleClient;
    private CandleHistoryRepository repository;
    private TradingUniverseRepository universeRepository;
    private com.trading.backtest.BacktestDataProperties backtestProperties;
    private MinuteCandleCollector sut;

    @BeforeEach
    void setUp() {
        candleClient = mock(CandleHistoryClient.class);
        repository = mock(CandleHistoryRepository.class);
        // Java 25 인라인 목이 구체 서비스 클래스를 목킹하지 못해(DART와 동일 제약)
        // 실객체 + 리포지토리(인터페이스) 목으로 구성한다
        universeRepository = mock(TradingUniverseRepository.class);
        TradingUniverseService universeService = new TradingUniverseService(universeRepository);
        backtestProperties = new com.trading.backtest.BacktestDataProperties();
        backtestProperties.setSymbols(List.of());        // 기존 테스트는 유니버스만으로 구성
        backtestProperties.setEventThemes(Map.of());
        Clock fixed = Clock.fixed(
                ZonedDateTime.of(TODAY.atTime(15, 40), KST).toInstant(), KST);
        sut = new MinuteCandleCollector(candleClient, repository, universeService,
                backtestProperties, fixed);
    }

    private void stubUniverse(String... codes) {
        List<TradingUniverseItem> items = java.util.Arrays.stream(codes)
                .map(c -> TradingUniverseItem.of(c, "테스트" + c))
                .toList();
        when(universeRepository.findAll()).thenReturn(items);
    }

    @Test
    @DisplayName("당일 분봉을 수취해 저장하고, 당일이 아닌 봉은 걸러낸다")
    void collectToday_savesTodayOnly() {
        stubUniverse("005930");
        when(repository.countByStockCodeAndTimeframeAndCandleDate(
                eq("005930"), eq(Timeframe.MINUTE), eq(TODAY))).thenReturn(0L);
        when(candleClient.fetchTodayMinuteCandles("005930")).thenReturn(List.of(
                new MinuteCandle(TODAY, LocalTime.of(9, 1), 100, 101, 99, 100, 500),
                new MinuteCandle(TODAY.minusDays(1), LocalTime.of(15, 20), 98, 99, 97, 98, 300)
        ));

        sut.collectToday();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CandleHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getCandleDate()).isEqualTo(TODAY);
        assertThat(captor.getValue().get(0).getTimeframe()).isEqualTo(Timeframe.MINUTE);
    }

    @Test
    @DisplayName("당일 분봉이 이미 저장돼 있으면 API를 호출하지 않는다 (중복 실행 방어)")
    void collectToday_skipsWhenAlreadyStored() {
        stubUniverse("005930");
        when(repository.countByStockCodeAndTimeframeAndCandleDate(
                eq("005930"), eq(Timeframe.MINUTE), eq(TODAY))).thenReturn(390L);

        sut.collectToday();

        verify(candleClient, never()).fetchTodayMinuteCandles(any());
    }

    @Test
    @DisplayName("수집 대상 = 유니버스 ∪ 백테스트 표본 ∪ 이벤트 표본 (중복 제거)")
    void collection_targets_merge_universe_and_backtest_samples() {
        stubUniverse("005930");
        backtestProperties.setSymbols(List.of("005930", "000660"));   // 005930 중복
        backtestProperties.setEventThemes(Map.of("battery", List.of("247540")));

        assertThat(sut.collectionTargets()).containsExactly("005930", "000660", "247540");
    }

    @Test
    @DisplayName("한 종목 수집 실패가 다음 종목 수집을 막지 않는다")
    void collectToday_continuesOnFailure() {
        stubUniverse("005930", "000660");
        when(repository.countByStockCodeAndTimeframeAndCandleDate(any(), any(), any())).thenReturn(0L);
        when(candleClient.fetchTodayMinuteCandles("005930"))
                .thenThrow(new IllegalStateException("KIS 오류"));
        when(candleClient.fetchTodayMinuteCandles("000660")).thenReturn(List.of(
                new MinuteCandle(TODAY, LocalTime.of(9, 1), 200, 201, 199, 200, 700)
        ));

        sut.collectToday();

        verify(candleClient).fetchTodayMinuteCandles("000660");
        verify(repository).saveAll(anyList());
    }
}
