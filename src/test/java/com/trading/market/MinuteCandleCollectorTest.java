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
    private MarketCalendarProperties calendarProps;
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
        backtestProperties.setCandidateSymbols(List.of()); // 기본 54종목이 대상에 섞이지 않게
        Clock fixed = Clock.fixed(
                ZonedDateTime.of(TODAY.atTime(15, 40), KST).toInstant(), KST);
        // 캘린더는 구체 클래스 — Java 25 인라인 목 제약으로 실객체 + 실제 프로퍼티로 조립
        calendarProps = new MarketCalendarProperties();
        MarketCalendarService calendar = new MarketCalendarService(calendarProps, fixed);
        sut = new MinuteCandleCollector(candleClient, repository, universeService,
                backtestProperties, calendar, fixed);
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
    @DisplayName("수집 대상 = 유니버스 ∪ 백테스트 표본 ∪ 이벤트 표본 ∪ §14.1 후보 (중복 제거)")
    void collection_targets_merge_universe_and_backtest_samples() {
        stubUniverse("005930");
        backtestProperties.setSymbols(List.of("005930", "000660"));   // 005930 중복
        backtestProperties.setEventThemes(Map.of("battery", List.of("247540")));
        backtestProperties.setCandidateSymbols(List.of("000660", "035420")); // 000660 중복

        assertThat(sut.collectionTargets())
                .containsExactly("005930", "000660", "247540", "035420");
    }

    @Test
    @DisplayName("수취분에 같은 (일자,시각) 분봉이 두 번 오면 첫 값만 저장한다 — 2026-07-20 전 종목 롤백 재발 방지")
    void collectToday_deduplicatesSameMinute() {
        stubUniverse("005930");
        when(repository.countByStockCodeAndTimeframeAndCandleDate(
                eq("005930"), eq(Timeframe.MINUTE), eq(TODAY))).thenReturn(0L);
        when(candleClient.fetchTodayMinuteCandles("005930")).thenReturn(List.of(
                new MinuteCandle(TODAY, LocalTime.of(11, 34), 100, 101, 99, 100, 500),
                new MinuteCandle(TODAY, LocalTime.of(11, 35), 101, 102, 100, 101, 600),
                new MinuteCandle(TODAY, LocalTime.of(11, 35), 999, 999, 999, 999, 1), // 페이지 경계 중복
                new MinuteCandle(TODAY, LocalTime.of(11, 36), 102, 103, 101, 102, 700)
        ));

        sut.collectToday();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CandleHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(3); // 11:34, 11:35(첫 값), 11:36
        assertThat(captor.getValue().stream()
                .filter(r -> LocalTime.of(11, 35).equals(r.getCandleTime()))
                .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("캐치업: 15:40을 놓쳐도 장 마감 후 재시도로 그날 분봉을 구제한다 (소급 조회 불가)")
    void catchUp_collectsWhenScheduledRunWasMissed() {
        stubUniverse("005930");
        when(repository.countByStockCodeAndTimeframeAndCandleDate(
                eq("005930"), eq(Timeframe.MINUTE), eq(TODAY))).thenReturn(0L);
        when(candleClient.fetchTodayMinuteCandles("005930")).thenReturn(List.of(
                new MinuteCandle(TODAY, LocalTime.of(9, 1), 100, 101, 99, 100, 500)
        ));

        sut.catchUpToday();

        verify(candleClient).fetchTodayMinuteCandles("005930");
        verify(repository).saveAll(anyList());
    }

    @Test
    @DisplayName("캐치업: 이미 저장된 종목은 다시 받지 않는다 (반복 실행 안전)")
    void catchUp_skipsAlreadyStoredSymbols() {
        stubUniverse("005930");
        when(repository.countByStockCodeAndTimeframeAndCandleDate(
                eq("005930"), eq(Timeframe.MINUTE), eq(TODAY))).thenReturn(390L);

        sut.catchUpToday();

        verify(candleClient, never()).fetchTodayMinuteCandles(any());
    }

    @Test
    @DisplayName("휴장일이면 수집하지 않는다 — 평일 크론이라도 임시휴장일에 KIS를 두드리지 않게")
    void collectToday_skipsOnHoliday() {
        calendarProps.setHolidays(List.of(TODAY)); // TODAY(금)를 임시휴장으로
        stubUniverse("005930");

        sut.collectToday();

        verify(candleClient, never()).fetchTodayMinuteCandles(any());
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
