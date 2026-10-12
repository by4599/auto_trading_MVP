package com.trading.backtest;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 두 진입의 트레이드 겹침 (흔적레이더 검증 §18) — "매집 흔적이 결국 돈치안과 같은 종목·같은 날을 사는가".
 *
 * <p>트레이드 단위라 슬롯 경합(동시 5종목)에 따라 달라지는 경로 의존 값이다 — 신호 자체의 겹침이 아니라
 * "실제로 산 것"의 겹침으로 읽는다.
 *
 * @param total   칸의 검증 트레이드 수
 * @param sameDay 기준선에 같은 종목·같은 진입일 트레이드가 있는 수
 * @param nearby  기준선에 같은 종목·진입일 ±windowDays(달력일) 이내 트레이드가 있는 수 (같은 날 포함)
 */
record TradeOverlap(int total, int sameDay, int nearby) {

    static TradeOverlap measure(List<TradeRecorder.ClosedTrade> cell,
                                List<TradeRecorder.ClosedTrade> baseline, int windowDays) {
        Map<String, List<LocalDate>> baselineEntries = baseline.stream().collect(Collectors.groupingBy(
                TradeRecorder.ClosedTrade::stockCode,
                Collectors.mapping(TradeRecorder.ClosedTrade::entryDate, Collectors.toList())));
        int same = 0;
        int near = 0;
        for (TradeRecorder.ClosedTrade t : cell) {
            List<LocalDate> dates = baselineEntries.getOrDefault(t.stockCode(), List.of());
            if (dates.contains(t.entryDate())) same++;
            if (dates.stream().anyMatch(d -> Math.abs(ChronoUnit.DAYS.between(d, t.entryDate())) <= windowDays)) {
                near++;
            }
        }
        return new TradeOverlap(cell.size(), same, near);
    }

    double sameDayRatio() {
        return total == 0 ? 0 : (double) sameDay / total;
    }

    double nearbyRatio() {
        return total == 0 ? 0 : (double) nearby / total;
    }
}
