package com.trading.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * 운영 DB 분봉 보존 기간 정리 — 일봉을 지우지 않는 것이 가장 중요한 불변식.
 */
@DisplayName("MinuteCandleRetention — 운영 DB 분봉 정리")
class MinuteCandleRetentionTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 4);

    private final CandleHistoryRepository repository = mock(CandleHistoryRepository.class);
    private final Clock clock = Clock.fixed(
            ZonedDateTime.of(TODAY.atTime(23, 10), KST).toInstant(), KST);

    private MinuteCandleRetention sut(int retentionDays) {
        return new MinuteCandleRetention(repository, clock, retentionDays);
    }

    @Test
    @DisplayName("보존 기간 이전 분봉만 삭제한다 — 기준일은 오늘 − 보존일수")
    void deletes_only_minutes_older_than_cutoff() {
        when(repository.countByTimeframeAndCandleDateBefore(eq(Timeframe.MINUTE), any()))
                .thenReturn(1000L);
        when(repository.deleteByTimeframeAndCandleDateBefore(eq(Timeframe.MINUTE), any()))
                .thenReturn(1000L);

        sut(60).purgeOldMinutes();

        ArgumentCaptor<LocalDate> cutoff = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository).deleteByTimeframeAndCandleDateBefore(eq(Timeframe.MINUTE), cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(TODAY.minusDays(60));
    }

    @Test
    @DisplayName("일봉은 삭제 대상이 아니다 — timeframe이 MINUTE으로 못박혀 있다")
    void never_deletes_daily_candles() {
        when(repository.countByTimeframeAndCandleDateBefore(eq(Timeframe.MINUTE), any()))
                .thenReturn(10L);
        when(repository.deleteByTimeframeAndCandleDateBefore(eq(Timeframe.MINUTE), any()))
                .thenReturn(10L);

        sut(60).purgeOldMinutes();

        verify(repository, never()).deleteByTimeframeAndCandleDateBefore(eq(Timeframe.DAILY), any());
    }

    @Test
    @DisplayName("지울 게 없으면 삭제를 호출하지 않는다")
    void skips_delete_when_nothing_to_purge() {
        when(repository.countByTimeframeAndCandleDateBefore(eq(Timeframe.MINUTE), any()))
                .thenReturn(0L);

        sut(60).purgeOldMinutes();

        verify(repository, never()).deleteByTimeframeAndCandleDateBefore(any(), any());
    }

    @Test
    @DisplayName("보존일수 0 이하는 무제한 보존 — 아무것도 지우지 않는다")
    void retention_zero_means_keep_everything() {
        sut(0).purgeOldMinutes();

        verify(repository, never()).countByTimeframeAndCandleDateBefore(any(), any());
        verify(repository, never()).deleteByTimeframeAndCandleDateBefore(any(), any());
    }
}
