package com.trading.backtest;

import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.MinuteCandle;
import com.trading.market.Timeframe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 운영 DB → 백테스트 DB 분봉 이관 — 두 DB 사이 다리가 없어 축적 분봉이 검증에
 * 닿지 못하던 공백(2026-08-04)을 메운다.
 */
@DisplayName("MinuteCandleImporter — 운영 DB 분봉 이관")
class MinuteCandleImporterTest {

    private static final LocalDate D1 = LocalDate.of(2026, 8, 3);
    private static final LocalDate D2 = LocalDate.of(2026, 8, 4);

    private CandleHistoryRepository repository;
    private final List<MinuteCandleSource.Row> sourceRows = new ArrayList<>();
    private MinuteCandleImporter sut;

    @BeforeEach
    void setUp() {
        repository = mock(CandleHistoryRepository.class);
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        MinuteCandleSource source = () -> List.copyOf(sourceRows);
        sut = new MinuteCandleImporter(source, repository);
    }

    private void given(String code, LocalDate date, LocalTime... times) {
        for (LocalTime t : times) {
            sourceRows.add(new MinuteCandleSource.Row(code,
                    new MinuteCandle(date, t, 100, 101, 99, 100, 500)));
        }
    }

    @Test
    @DisplayName("운영 DB의 분봉을 백테스트 DB로 옮긴다 (종목·일자 단위)")
    void imports_minute_rows() {
        given("005930", D1, LocalTime.of(9, 1), LocalTime.of(9, 2));
        when(repository.countByStockCodeAndTimeframeAndCandleDate(any(), any(), any())).thenReturn(0L);

        MinuteCandleImporter.ImportResult result = sut.importAll();

        assertThat(result.importedRows()).isEqualTo(2);
        assertThat(result.importedDays()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CandleHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).getTimeframe()).isEqualTo(Timeframe.MINUTE);
        assertThat(captor.getValue().get(0).getStockCode()).isEqualTo("005930");
    }

    @Test
    @DisplayName("이미 이관된 (종목,일자)는 건너뛴다 — 반복 실행 안전")
    void skips_days_already_present() {
        given("005930", D1, LocalTime.of(9, 1));
        given("005930", D2, LocalTime.of(9, 1));
        when(repository.countByStockCodeAndTimeframeAndCandleDate(
                eq("005930"), eq(Timeframe.MINUTE), eq(D1))).thenReturn(390L); // D1은 이미 있음
        when(repository.countByStockCodeAndTimeframeAndCandleDate(
                eq("005930"), eq(Timeframe.MINUTE), eq(D2))).thenReturn(0L);

        MinuteCandleImporter.ImportResult result = sut.importAll();

        assertThat(result.skippedDays()).isEqualTo(1);
        assertThat(result.importedDays()).isEqualTo(1);
        assertThat(result.importedRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("종목이 여러 개면 종목·일자 조합마다 따로 판정한다")
    void groups_by_symbol_and_date() {
        given("005930", D1, LocalTime.of(9, 1));
        given("000660", D1, LocalTime.of(9, 1), LocalTime.of(9, 2));
        when(repository.countByStockCodeAndTimeframeAndCandleDate(any(), any(), any())).thenReturn(0L);

        MinuteCandleImporter.ImportResult result = sut.importAll();

        assertThat(result.importedDays()).isEqualTo(2);
        assertThat(result.importedRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("운영 DB가 비어 있으면 아무것도 저장하지 않는다 (현재 상태 — 축적 전)")
    void empty_source_saves_nothing() {
        MinuteCandleImporter.ImportResult result = sut.importAll();

        assertThat(result.importedRows()).isZero();
        verify(repository, never()).saveAll(anyList());
    }
}
