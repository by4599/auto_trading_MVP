package com.trading.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지수 장기추세 판정(§14.4)의 순수 단위 테스트 — Mockito 없이 합성 시계열만 쓴다.
 *
 * 가장 중요한 항목은 <b>선견편향 차단</b>이다: 진입 판단은 장중에 일어나므로
 * 당일 종가·당일 종가를 포함한 이동평균을 쓰면 미래를 보는 것이 된다.
 */
@DisplayName("BacktestIndexRegimeSource.belowTrend — 지수 장기추세 판정")
class BacktestIndexRegimeSourceTest {

    private static final LocalDate START = LocalDate.of(2022, 1, 3);

    /** 연속 일자에 종가를 채운 합성 지수 시계열 */
    private static NavigableMap<LocalDate, Double> series(double... closes) {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        LocalDate date = START;
        for (double close : closes) {
            map.put(date, close);
            date = date.plusDays(1);
        }
        return map;
    }

    private static LocalDate day(int indexFromStart) {
        return START.plusDays(indexFromStart);
    }

    @Test
    @DisplayName("선견편향 차단 — 당일 종가를 극단값으로 바꿔도 판정이 변하지 않는다")
    void ignoresTodayClose_noLookAhead() {
        LocalDate simDate = day(4); // 5번째 날이 '오늘'
        // 전일까지(0~3일) 종가는 전부 100 → 전일 종가 100 == MA4 100 → '아래' 아님
        assertThat(BacktestIndexRegimeSource.belowTrend(
                series(100, 100, 100, 100, 100), simDate, 4)).hasValue(false);

        // 당일 종가만 폭락시켜도 판정은 그대로여야 한다 (당일을 안 보므로)
        assertThat(BacktestIndexRegimeSource.belowTrend(
                series(100, 100, 100, 100, 1), simDate, 4)).hasValue(false);
        // 반대 방향 극단값도 마찬가지
        assertThat(BacktestIndexRegimeSource.belowTrend(
                series(100, 100, 100, 100, 1_000_000), simDate, 4)).hasValue(false);

        // 대조(Red 확인용): 만약 당일을 포함했다면 폭락 종가가 판정을 뒤집는다.
        // 하루 뒤를 simDate로 주면 그 폭락일이 '전일'이 되어 true가 된다.
        assertThat(BacktestIndexRegimeSource.belowTrend(
                series(100, 100, 100, 100, 1), day(5), 4)).hasValue(true);
    }

    @Test
    @DisplayName("표본이 MA 기간보다 적으면 empty (판단 불가 → 차단하지 않음)")
    void insufficientHistory_returnsEmpty() {
        // 당일 이전 종가가 3개뿐인데 MA4를 요구
        assertThat(BacktestIndexRegimeSource.belowTrend(
                series(100, 100, 100, 100), day(3), 4)).isEmpty();
        // 딱 4개면 판정 가능
        assertThat(BacktestIndexRegimeSource.belowTrend(
                series(100, 100, 100, 100, 100), day(4), 4)).isPresent();
    }

    @Test
    @DisplayName("경계 — 전일 종가가 이동평균과 같으면 '아래'가 아니다")
    void closeEqualToMovingAverage_isNotBelow() {
        // 전일까지 [98, 100, 102, 100] → MA4 = 100, 전일 종가 = 100
        assertThat(BacktestIndexRegimeSource.belowTrend(
                series(98, 100, 102, 100, 999), day(4), 4)).hasValue(false);
    }

    @Test
    @DisplayName("전일 종가가 MA 아래면 true, 위면 false (하락/상승 추세)")
    void belowAndAboveTrend() {
        // 하락: 전일까지 [110, 105, 100, 85] → MA4 = 100, 전일 종가 85 < 100
        assertThat(BacktestIndexRegimeSource.belowTrend(
                series(110, 105, 100, 85, 999), day(4), 4)).hasValue(true);
        // 상승: 전일까지 [85, 100, 105, 110] → MA4 = 100, 전일 종가 110 > 100
        assertThat(BacktestIndexRegimeSource.belowTrend(
                series(85, 100, 105, 110, 999), day(4), 4)).hasValue(false);
    }

    @Test
    @DisplayName("MA 기간은 최근 N개만 본다 — 같은 시계열도 MA4와 MA6의 판정이 갈린다")
    void usesOnlyLastNCloses() {
        // 전일까지 [1000, 1000, 80, 85, 90, 95] · 당일(day 6)은 미사용
        // MA4 = (80+85+90+95)/4 = 87.5 → 전일 종가 95 > 87.5 → 아래 아님
        assertThat(BacktestIndexRegimeSource.belowTrend(
                series(1000, 1000, 80, 85, 90, 95, 999), day(6), 4)).hasValue(false);
        // MA6 = 2350/6 ≈ 391.7 → 옛 고가가 평균을 끌어올려 아래
        Optional<Boolean> ma6 = BacktestIndexRegimeSource.belowTrend(
                series(1000, 1000, 80, 85, 90, 95, 999), day(6), 6);
        assertThat(ma6).hasValue(true);
    }

    @Test
    @DisplayName("입력이 비정상(null·기간 0 이하)이면 empty")
    void invalidInput_returnsEmpty() {
        assertThat(BacktestIndexRegimeSource.belowTrend(null, day(4), 4)).isEmpty();
        assertThat(BacktestIndexRegimeSource.belowTrend(series(100, 100), null, 4)).isEmpty();
        assertThat(BacktestIndexRegimeSource.belowTrend(series(100, 100), day(1), 0)).isEmpty();
    }
}
