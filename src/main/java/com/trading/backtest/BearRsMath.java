package com.trading.backtest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.OptionalDouble;

/**
 * 하락장 상대강도 예외(55_quant §0.3)의 순수 계산 — 스프링·DB 없이 시계열만 받는다.
 *
 * <p><b>선견편향 차단</b>: 모든 함수는 {@code simDate} <b>미만</b>의 봉만 쓴다(당일 봉 미사용).
 * 진입 판단은 장중(10:00 근사)이므로 당일 종가·고가는 아직 모르는 값이다.
 */
final class BearRsMath {

    private BearRsMath() {
    }

    /** simDate 전날까지의 값(오래된 것부터) */
    static List<Double> pastValues(NavigableMap<LocalDate, Double> series, LocalDate simDate) {
        if (series == null || simDate == null) return List.of();
        return new ArrayList<>(series.headMap(simDate, false).values());
    }

    /** 전일 종가 ÷ n거래일 전 종가 − 1 (그 시계열 자신의 거래일 기준). 표본이 n+1개 미만이면 없음 */
    static OptionalDouble trailingReturn(NavigableMap<LocalDate, Double> closes, LocalDate simDate, int n) {
        if (n <= 0) return OptionalDouble.empty();
        List<Double> past = pastValues(closes, simDate);
        if (past.size() < n + 1) return OptionalDouble.empty();
        double last = past.get(past.size() - 1);
        double base = past.get(past.size() - 1 - n);
        if (base <= 0) return OptionalDouble.empty();
        return OptionalDouble.of(last / base - 1);
    }

    /** RS_N = 종목 N일 수익률 − 지수 N일 수익률. 둘 중 하나라도 없으면 없음 */
    static OptionalDouble relativeStrength(NavigableMap<LocalDate, Double> stockCloses,
                                           NavigableMap<LocalDate, Double> indexCloses,
                                           LocalDate simDate, int n) {
        OptionalDouble stock = trailingReturn(stockCloses, simDate, n);
        OptionalDouble index = trailingReturn(indexCloses, simDate, n);
        if (stock.isEmpty() || index.isEmpty()) return OptionalDouble.empty();
        return OptionalDouble.of(stock.getAsDouble() - index.getAsDouble());
    }

    /**
     * 상위 비율 문턱 — 내림차순 정렬해 k = ceil(fraction·n)번째 값. 이 값 이상이면 상위(동점 포함).
     * 값이 없거나 비율이 0 이하면 없음.
     */
    static OptionalDouble topThreshold(List<Double> values, double fraction) {
        if (values == null || values.isEmpty() || fraction <= 0) return OptionalDouble.empty();
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.reverseOrder());
        int k = (int) Math.ceil(fraction * sorted.size());
        k = Math.max(1, Math.min(k, sorted.size()));
        return OptionalDouble.of(sorted.get(k - 1));
    }

    /**
     * 52주 고가 근접 — 전일 종가 ≥ ratio × 직전 lookback거래일 고가 최댓값(당일 제외).
     * 고가 표본이 lookback개 미만이거나 전일 종가가 없으면 false(불허).
     */
    static boolean nearHigh(NavigableMap<LocalDate, Double> closes, NavigableMap<LocalDate, Double> highs,
                            LocalDate simDate, int lookback, double ratio) {
        List<Double> pastHighs = pastValues(highs, simDate);
        List<Double> pastCloses = pastValues(closes, simDate);
        if (lookback <= 0 || pastHighs.size() < lookback || pastCloses.isEmpty()) return false;
        double maxHigh = pastHighs.subList(pastHighs.size() - lookback, pastHighs.size()).stream()
                .mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
        double previousClose = pastCloses.get(pastCloses.size() - 1);
        return previousClose >= ratio * maxHigh;
    }
}
