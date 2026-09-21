package com.trading.dashboard;

import com.trading.market.Candle;
import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.MinuteCandle;
import com.trading.market.Timeframe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 매도 체결가 추정 — <b>폴백 3단계</b>(분봉 → 일봉 → 측정 불가)를 고정한다.
 *
 * <p>모의 체결조회가 매도에 빈 응답을 주는 결함(CLAUDE.md 결함 5)으로 매도 체결가가
 * 0원으로 남기 때문에 필요한 장치다. <b>0원으로 꾸미지 않고 "측정 불가"를 그대로 돌려준다</b>는
 * 것이 이 테스트의 핵심이다.
 *
 * <p>Java 25 Mockito 제약: 리포지토리(인터페이스)만 목, 추정기는 실객체.
 */
@DisplayName("SellPriceEstimator — 매도가 추정 폴백 3단계")
class SellPriceEstimatorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 8);
    private static final String CODE = "005930";

    private final CandleHistoryRepository candleRepository = mock(CandleHistoryRepository.class);
    private final SellPriceEstimator sut = new SellPriceEstimator(candleRepository);

    private void givenMinutes(CandleHistory... candles) {
        when(candleRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        eq(CODE), eq(Timeframe.MINUTE), eq(DAY), eq(DAY)))
                .thenReturn(List.of(candles));
    }

    private void givenDaily(CandleHistory... candles) {
        when(candleRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        eq(CODE), eq(Timeframe.DAILY), eq(DAY), eq(DAY)))
                .thenReturn(List.of(candles));
    }

    private static CandleHistory minute(String time, double close) {
        return CandleHistory.ofMinute(CODE, new MinuteCandle(
                DAY, LocalTime.parse(time), close, close, close, close, 1_000));
    }

    private static CandleHistory daily(double close) {
        return CandleHistory.ofDaily(CODE, new Candle(DAY, close, close, close, close, 1_000));
    }

    private SellPriceEstimator.Estimate estimateAt(String time) {
        return sut.newLookup().estimate(CODE, LocalDateTime.of(DAY, LocalTime.parse(time)));
    }

    // ── 1단계: 분봉 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("분봉이 있으면 체결 시각에 가장 가까운 봉의 종가를 쓴다")
    void uses_the_nearest_minute_close() {
        givenMinutes(minute("09:00", 70_000), minute("10:20", 72_500), minute("14:00", 68_000));

        SellPriceEstimator.Estimate estimate = estimateAt("10:20:55");

        assertThat(estimate.price()).isEqualTo(72_500.0);
        assertThat(estimate.source()).isEqualTo(SellPriceEstimator.Source.MINUTE);
        assertThat(estimate.isMeasurable()).isTrue();
    }

    @Test
    @DisplayName("시각이 두 봉 사이면 더 가까운 쪽을 고른다")
    void picks_the_closer_of_two_neighbours() {
        givenMinutes(minute("10:20", 72_500), minute("10:30", 73_900));

        assertThat(estimateAt("10:28:00").price()).isEqualTo(73_900.0);
        assertThat(estimateAt("10:22:00").price()).isEqualTo(72_500.0);
    }

    // ── 2단계: 일봉 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("분봉이 없으면 그날 일봉 종가로 내려간다")
    void falls_back_to_the_daily_close() {
        givenMinutes();                 // 분봉 없음
        givenDaily(daily(71_200));

        SellPriceEstimator.Estimate estimate = estimateAt("10:20:55");

        assertThat(estimate.price()).isEqualTo(71_200.0);
        assertThat(estimate.source()).isEqualTo(SellPriceEstimator.Source.DAILY);
    }

    // ── 3단계: 측정 불가 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("분봉도 일봉도 없으면 측정 불가 — 0원으로 꾸미지 않는다")
    void reports_unmeasurable_when_nothing_is_available() {
        givenMinutes();
        givenDaily();

        SellPriceEstimator.Estimate estimate = estimateAt("10:20:55");

        assertThat(estimate.price()).isNull();
        assertThat(estimate.source()).isEqualTo(SellPriceEstimator.Source.NONE);
        assertThat(estimate.isMeasurable()).isFalse();
    }

    // ── 조회 비용 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 종목·같은 날은 한 번만 읽는다 (분봉은 하루 391행)")
    void caches_per_stock_and_day() {
        givenMinutes(minute("10:20", 72_500));
        SellPriceEstimator.Lookup lookup = sut.newLookup();

        lookup.estimate(CODE, LocalDateTime.of(DAY, LocalTime.of(10, 20)));
        lookup.estimate(CODE, LocalDateTime.of(DAY, LocalTime.of(11, 30)));
        lookup.estimate(CODE, LocalDateTime.of(DAY, LocalTime.of(14, 0)));

        verify(candleRepository, times(1))
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        eq(CODE), eq(Timeframe.MINUTE), eq(DAY), eq(DAY));
    }

    @Test
    @DisplayName("조회기를 새로 만들면 캐시도 새로 시작한다 (싱글턴에 상태를 남기지 않는다)")
    void a_new_lookup_starts_with_an_empty_cache() {
        givenMinutes(minute("10:20", 72_500));

        estimateAt("10:20:00");
        estimateAt("10:20:00");

        verify(candleRepository, times(2))
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        eq(CODE), eq(Timeframe.MINUTE), eq(DAY), eq(DAY));
    }
}
