package com.trading.risk;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;

/**
 * 지수 장기추세 판정의 순수 계산 (BACKTEST-DESIGN §14.4) — "지수 <b>전일 종가</b>가
 * <b>전일까지의</b> N거래일 이동평균 아래인가".
 *
 * <p>검증된 설정은 백테스트의 {@code BacktestIndexRegimeSource.belowTrend}로 채점됐다. 모의투자는
 * 백테스트 코드(회귀 앵커)를 건드리지 않으려고 같은 계산을 여기에 따로 둔다 — 둘이 갈라지면
 * "검증된 필터"라는 말이 거짓이 되므로 {@code backtest.IndexTrendParityTest}가 두 결과의 일치를 고정한다.
 *
 * <p>규칙(백테스트와 동일): 판정일(오늘) 봉은 절대 쓰지 않는다 — 진입 판단은 장중이라 오늘 종가와
 * 오늘을 포함한 이동평균은 미래 정보다. 전일 종가 == 이동평균이면 "아래"가 아니다.
 * 표본이 기간보다 적으면 판정 불가(empty).
 */
public final class IndexTrendCalculator {

    private IndexTrendCalculator() {}

    /** 판정 한 건 — 로그·알림에 숫자를 그대로 보이려고 판정과 근거를 함께 담는다 */
    public record Reading(LocalDate previousDate, double previousClose, double movingAverage) {

        public boolean below() {
            return previousClose < movingAverage;
        }

        /** 전일 종가가 이동평균에서 몇 % 떨어져 있나 (음수 = 아래) */
        public double gapPercent() {
            return (previousClose / movingAverage - 1) * 100;
        }
    }

    public static Optional<Boolean> belowTrend(NavigableMap<LocalDate, Double> closes,
                                               LocalDate today, int maPeriod) {
        return read(closes, today, maPeriod).map(Reading::below);
    }

    public static Optional<Reading> read(NavigableMap<LocalDate, Double> closes,
                                         LocalDate today, int maPeriod) {
        if (closes == null || today == null || maPeriod <= 0) return Optional.empty();
        // headMap(today, false) — 오늘을 포함하지 않는다. 이 한 줄이 선견편향 차단선이다.
        NavigableMap<LocalDate, Double> past = closes.headMap(today, false);
        if (past.size() < maPeriod) return Optional.empty();

        List<Double> values = new ArrayList<>(past.values());
        List<Double> window = values.subList(values.size() - maPeriod, values.size());
        double sum = 0;
        for (double close : window) sum += close;
        return Optional.of(new Reading(past.lastKey(), window.get(window.size() - 1), sum / maPeriod));
    }
}
