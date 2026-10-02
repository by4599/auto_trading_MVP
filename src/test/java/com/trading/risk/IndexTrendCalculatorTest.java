package com.trading.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 지수 장기추세 판정 — "전일 종가 &lt; 전일까지의 N일 이동평균"(BACKTEST-DESIGN §14.4).
 * 백테스트 판정과 한 글자도 다르지 않아야 한다(대조는 {@code backtest.IndexTrendParityTest}).
 */
@DisplayName("IndexTrendCalculator — 지수 전일 종가 vs 전일까지의 이동평균")
class IndexTrendCalculatorTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);

    /** D1부터 하루씩 종가를 넣는다 (날짜 간격은 판정과 무관 — 표본 개수만 센다) */
    private static NavigableMap<LocalDate, Double> series(double... closes) {
        NavigableMap<LocalDate, Double> map = new TreeMap<>();
        for (int i = 0; i < closes.length; i++) map.put(D1.plusDays(i), closes[i]);
        return map;
    }

    private static LocalDate day(int index) {
        return D1.plusDays(index);
    }

    @Test
    @DisplayName("전일 종가가 이동평균 아래면 true (하락 추세)")
    void below_when_previous_close_under_average() {
        // MA4 = (110+110+110+90)/4 = 105, 전일 종가 90 < 105
        assertThat(IndexTrendCalculator.belowTrend(series(110, 110, 110, 90), day(4), 4)).contains(true);
    }

    @Test
    @DisplayName("전일 종가가 이동평균 위면 false")
    void above_when_previous_close_over_average() {
        assertThat(IndexTrendCalculator.belowTrend(series(90, 90, 90, 110), day(4), 4)).contains(false);
    }

    @Test
    @DisplayName("경계: 전일 종가 == 이동평균이면 '아래'가 아니다 (백테스트와 같은 경계)")
    void equal_is_not_below() {
        assertThat(IndexTrendCalculator.belowTrend(series(100, 100, 100, 100), day(4), 4)).contains(false);
    }

    @Test
    @DisplayName("당일 봉은 절대 쓰지 않는다 — 당일 값을 극단으로 바꿔도 판정 불변 (선견편향 차단)")
    void ignores_same_day_bar() {
        NavigableMap<LocalDate, Double> withToday = series(110, 110, 110, 90, 1_000_000);
        NavigableMap<LocalDate, Double> withoutToday = series(110, 110, 110, 90);

        assertThat(IndexTrendCalculator.belowTrend(withToday, day(4), 4))
                .isEqualTo(IndexTrendCalculator.belowTrend(withoutToday, day(4), 4))
                .contains(true);
    }

    @Test
    @DisplayName("표본이 기간보다 적으면 판정 불가(empty)")
    void empty_when_not_enough_samples() {
        assertThat(IndexTrendCalculator.belowTrend(series(100, 100, 100), day(3), 4)).isEmpty();
    }

    @Test
    @DisplayName("입력이 없거나 기간이 0 이하이면 판정 불가")
    void empty_on_invalid_input() {
        assertThat(IndexTrendCalculator.belowTrend(null, day(4), 4)).isEmpty();
        assertThat(IndexTrendCalculator.belowTrend(series(100, 100), null, 1)).isEmpty();
        assertThat(IndexTrendCalculator.belowTrend(series(100, 100), day(2), 0)).isEmpty();
    }

    @Test
    @DisplayName("read()는 로그용 숫자(전일 날짜·전일 종가·이동평균)를 함께 준다")
    void read_exposes_values_for_logging() {
        Optional<IndexTrendCalculator.Reading> reading =
                IndexTrendCalculator.read(series(110, 110, 110, 90), day(4), 4);

        assertThat(reading).isPresent();
        assertThat(reading.get().previousDate()).isEqualTo(day(3));
        assertThat(reading.get().previousClose()).isEqualTo(90.0);
        assertThat(reading.get().movingAverage()).isCloseTo(105.0, within(1e-9));
        assertThat(reading.get().below()).isTrue();
        assertThat(reading.get().gapPercent()).isCloseTo((90.0 / 105.0 - 1) * 100, within(1e-9));
    }
}
