package com.trading.backtest;

import com.trading.market.Candle;
import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryClient;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.Timeframe;
import com.trading.universe.TradingUniverseItem;
import com.trading.universe.TradingUniverseRepository;
import com.trading.universe.TradingUniverseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CandleBackfillServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 10);

    private CandleHistoryClient candleClient;
    private CandleHistoryRepository repository;
    private TradingUniverseRepository universeRepository;
    private BacktestDataProperties properties;
    private CandleBackfillService sut;

    @BeforeEach
    void setUp() {
        candleClient = mock(CandleHistoryClient.class);
        repository = mock(CandleHistoryRepository.class);
        // Java 25 인라인 목이 구체 서비스 클래스를 목킹하지 못해(DART와 동일 제약)
        // 실객체 + 리포지토리(인터페이스) 목으로 구성한다
        universeRepository = mock(TradingUniverseRepository.class);
        TradingUniverseService universeService = new TradingUniverseService(universeRepository);
        properties = new BacktestDataProperties();
        Clock fixed = Clock.fixed(
                ZonedDateTime.of(TODAY.atTime(10, 0), KST).toInstant(), KST);
        sut = new CandleBackfillService(candleClient, repository, universeService, properties, fixed);
    }

    @Test
    @DisplayName("대상 종목 = 유니버스 활성 종목 ∪ 설정 symbols (중복 제거, 순서 유지)")
    void targetSymbols_mergesUniverseAndConfig() {
        when(universeRepository.findAll()).thenReturn(List.of(
                TradingUniverseItem.of("005930", "삼성전자"),
                TradingUniverseItem.of("999999", "테스트종목")));
        properties.setSymbols(List.of("005930", "000660"));

        assertThat(sut.targetSymbols()).containsExactly("005930", "999999", "000660");
    }

    @Test
    @DisplayName("저장 이력이 없으면 전체 기간이 수취 대상이다")
    void missingRanges_emptyStorage_returnsFullRange() {
        when(repository.findFirstByStockCodeAndTimeframeOrderByCandleDateAsc(any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findFirstByStockCodeAndTimeframeOrderByCandleDateDesc(any(), any()))
                .thenReturn(Optional.empty());

        LocalDate from = TODAY.minusYears(3);
        LocalDate to = TODAY.minusDays(1);
        List<CandleBackfillService.DateRange> gaps = sut.missingRanges("005930", from, to);

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).from()).isEqualTo(from);
        assertThat(gaps.get(0).to()).isEqualTo(to);
    }

    @Test
    @DisplayName("커버리지가 충분하면(양끝 7일 이내) 수취 구간이 없다")
    void missingRanges_fullyCovered_returnsEmpty() {
        LocalDate from = TODAY.minusYears(3);
        LocalDate to = TODAY.minusDays(1);
        stubStoredRange("005930", from.plusDays(3), to.minusDays(2));

        assertThat(sut.missingRanges("005930", from, to)).isEmpty();
    }

    @Test
    @DisplayName("최신 저장일 이후가 7일 넘게 비면 증분 구간만 수취한다")
    void missingRanges_staleTail_returnsIncrementalGap() {
        LocalDate from = TODAY.minusYears(3);
        LocalDate to = TODAY.minusDays(1);
        LocalDate storedTo = to.minusDays(30);
        stubStoredRange("005930", from, storedTo);

        List<CandleBackfillService.DateRange> gaps = sut.missingRanges("005930", from, to);

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).from()).isEqualTo(storedTo.plusDays(1));
        assertThat(gaps.get(0).to()).isEqualTo(to);
    }

    @Test
    @DisplayName("백필은 부족 구간만 수취해 저장하고, KOSPI는 지수 API로 수취한다")
    void backfillAll_fetchesGapsAndKospi() {
        when(universeRepository.findAll()).thenReturn(List.of());
        properties.setSymbols(List.of("005930"));
        when(repository.findFirstByStockCodeAndTimeframeOrderByCandleDateAsc(any(), eq(Timeframe.DAILY)))
                .thenReturn(Optional.empty());
        when(repository.findFirstByStockCodeAndTimeframeOrderByCandleDateDesc(any(), eq(Timeframe.DAILY)))
                .thenReturn(Optional.empty());

        Candle candle = new Candle(TODAY.minusDays(2), 100, 110, 90, 105, 1000);
        when(candleClient.fetchDailyCandles(eq("005930"), any(), any()))
                .thenReturn(List.of(candle));
        when(candleClient.fetchIndexDailyCandles(eq("0001"), any(), any()))
                .thenReturn(List.of(candle, candle));
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        int saved = sut.backfillAll();

        assertThat(saved).isEqualTo(3);
        verify(candleClient).fetchDailyCandles(eq("005930"), any(), any());
        verify(candleClient).fetchIndexDailyCandles(eq("0001"), any(), any());
    }

    private void stubStoredRange(String code, LocalDate storedFrom, LocalDate storedTo) {
        CandleHistory first = CandleHistory.ofDaily(code,
                new Candle(storedFrom, 1, 1, 1, 1, 1));
        CandleHistory last = CandleHistory.ofDaily(code,
                new Candle(storedTo, 1, 1, 1, 1, 1));
        when(repository.findFirstByStockCodeAndTimeframeOrderByCandleDateAsc(eq(code), eq(Timeframe.DAILY)))
                .thenReturn(Optional.of(first));
        when(repository.findFirstByStockCodeAndTimeframeOrderByCandleDateDesc(eq(code), eq(Timeframe.DAILY)))
                .thenReturn(Optional.of(last));
    }
}
