package com.trading.bucket;

import java.time.LocalDate;

/**
 * 한 칸의 실현손익 묶음 — 금액과 "어떻게 구한 값인지"의 건수를 함께 나른다.
 *
 * <p>모의투자는 매도 체결가를 주지 않아(CLAUDE.md 결함 5) 대부분의 매도는 추정이다. 그래서 금액만
 * 넘기지 않고 출처별 건수를 붙인다: 기록(TradeResult)·추정(매도가 추정기)·측정 불가(0원으로 넣음).
 *
 * @param latestUnmeasurableDate 측정 불가 매도 중 가장 늦은 날 — 오늘이면 아직 분봉이 안 쌓인 것이라
 *                               판정을 보류한다(SleeveEquityCalculator). 없으면 null
 */
public record SleeveRealized(double pnl, int recordedCount, int estimatedCount,
                             int unmeasurableCount, LocalDate latestUnmeasurableDate) {

    public static SleeveRealized none() {
        return new SleeveRealized(0, 0, 0, 0, null);
    }

    /** 두 구간을 합친다 — 날짜는 더 늦은 쪽 */
    public SleeveRealized plus(SleeveRealized other) {
        return new SleeveRealized(
                pnl + other.pnl,
                recordedCount + other.recordedCount,
                estimatedCount + other.estimatedCount,
                unmeasurableCount + other.unmeasurableCount,
                later(latestUnmeasurableDate, other.latestUnmeasurableDate));
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
