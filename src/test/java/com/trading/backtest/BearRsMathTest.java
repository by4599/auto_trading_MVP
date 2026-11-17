package com.trading.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("BearRsMath — 하락장 상대강도 순수 계산 (55_quant §0.3)")
class BearRsMathTest {

    private static final LocalDate START = LocalDate.of(2022, 1, 1);

    private static NavigableMap<LocalDate, Double> series(double... values) {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        for (int i = 0; i < values.length; i++) map.put(START.plusDays(i), values[i]);
        return map;
    }

    private static NavigableMap<LocalDate, Double> flat(int length, double value) {
        double[] v = new double[length];
        java.util.Arrays.fill(v, value);
        return series(v);
    }

    private static LocalDate day(int i) {
        return START.plusDays(i);
    }

    @Test
    @DisplayName("N일 수익률 — 전일 종가 ÷ N거래일 전 종가 − 1, 당일 봉은 쓰지 않는다")
    void trailingReturn_usesPreviousCloseOnly() {
        // 0~3일: 100, 110, 120, 132 · 4일(오늘): 1,000,000 (극단값 — 쓰이면 안 됨)
        NavigableMap<LocalDate, Double> s = series(100, 110, 120, 132, 1_000_000);
        assertThat(BearRsMath.trailingReturn(s, day(4), 3).getAsDouble()).isCloseTo(0.32, within(1e-9));
        assertThat(BearRsMath.trailingReturn(s, day(4), 1).getAsDouble()).isCloseTo(0.10, within(1e-9));
    }

    @Test
    @DisplayName("N일 수익률 — 표본이 N+1개 미만이면 없음")
    void trailingReturn_needsNPlusOneSamples() {
        NavigableMap<LocalDate, Double> s = series(100, 110, 120);
        assertThat(BearRsMath.trailingReturn(s, day(3), 2)).isPresent();
        assertThat(BearRsMath.trailingReturn(s, day(3), 3)).isEmpty();
    }

    @Test
    @DisplayName("RS = 종목 수익률 − 지수 수익률")
    void relativeStrength_isStockMinusIndex() {
        NavigableMap<LocalDate, Double> stock = series(100, 100, 100);
        NavigableMap<LocalDate, Double> index = series(100, 90, 80);
        assertThat(BearRsMath.relativeStrength(stock, index, day(3), 2).getAsDouble()).isCloseTo(0.20, within(1e-9));
        assertThat(BearRsMath.relativeStrength(stock, series(100), day(3), 2)).isEmpty();
    }

    @Test
    @DisplayName("상위 20% 문턱 — k = ceil(0.2·n)번째 값, 동점은 포함")
    void topThreshold_ceilAndTies() {
        // n=6 → k=ceil(1.2)=2 → 내림차순 두 번째 값
        assertThat(BearRsMath.topThreshold(List.of(0.1, 0.5, 0.3, 0.2, 0.4, 0.0), 0.2).getAsDouble()).isEqualTo(0.4);
        // n=5 → k=1, 1등이 둘이면 문턱 = 그 값 → 둘 다 포함
        assertThat(BearRsMath.topThreshold(List.of(0.2, 0.2, 0.1, 0.0, -0.1), 0.2).getAsDouble()).isEqualTo(0.2);
        assertThat(BearRsMath.topThreshold(List.of(), 0.2)).isEmpty();
    }

    @Test
    @DisplayName("52주 고가 근접 — 직전 250거래일 고가 최댓값 대비, 250개 미만이면 불허")
    void nearHigh_requiresFullLookback() {
        NavigableMap<LocalDate, Double> closes = flat(250, 95);
        NavigableMap<LocalDate, Double> highs = flat(250, 100);
        assertThat(BearRsMath.nearHigh(closes, highs, day(250), 250, 0.9)).isTrue();   // 95 ≥ 90
        assertThat(BearRsMath.nearHigh(closes, highs, day(249), 250, 0.9)).isFalse();  // 표본 249개
    }

    @Test
    @DisplayName("52주 고가 근접 — 당일 고가는 제외, 창 밖 옛 고가도 제외")
    void nearHigh_excludesTodayAndOldHighs() {
        NavigableMap<LocalDate, Double> closes = flat(252, 95);
        TreeMap<LocalDate, Double> highs = new TreeMap<>(flat(252, 100));
        highs.put(day(251), 1_000.0);  // 오늘 고가 — 무시돼야 한다
        assertThat(BearRsMath.nearHigh(closes, highs, day(251), 250, 0.9)).isTrue();
        highs.put(day(0), 1_000.0);    // 251일 전 — 직전 250거래일 창 밖
        assertThat(BearRsMath.nearHigh(closes, highs, day(251), 250, 0.9)).isTrue();
        highs.put(day(1), 1_000.0);    // 250일 전 — 창 안 → 95 < 900
        assertThat(BearRsMath.nearHigh(closes, highs, day(251), 250, 0.9)).isFalse();
    }
}
