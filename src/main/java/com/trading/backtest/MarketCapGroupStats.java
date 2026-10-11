package com.trading.backtest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 시가총액 세분화 재검증 (2026-07-20) — 대형주는 공시에 둔감하다는 §8 통설의 정량 확인.
 *
 * <p>같은 유형을 LARGE/MIDSMALL로 나눠 별도 집계한다. eventType을 "&lt;유형&gt;:&lt;그룹&gt;"으로
 * 태깅해 기존 통계 엔진·레지스트리·리포트 서식을 그대로 재사용한다.
 */
@Component
@Profile("backtest")
public class MarketCapGroupStats {

    /**
     * B-3 원본 대형주 6종목 — 고정 상수로 둔다. properties.getSymbols()(backtest.symbols)는
     * VB 유니버스 확장 실험(2026-07-20)에서 중소형주까지 포함하도록 이미 넓혀졌으므로,
     * 그 프로퍼티를 LARGE 판정 기준으로 재사용하면 전 종목이 LARGE로 오분류된다
     * (실제로 이 버그로 EVENT-REPORT-20260720-1056.md의 MIDSMALL 행이 전부 비었던 사고 있었음).
     */
    private static final List<String> B3_LARGE_CAP_SYMBOLS = List.of(
            "005930", "000660", "373220", "005380", "035420", "068270");

    private final EventStatsBacktester statsBacktester;
    private final BacktestDataProperties properties;

    public MarketCapGroupStats(EventStatsBacktester statsBacktester,
                               BacktestDataProperties properties) {
        this.statsBacktester = statsBacktester;
        this.properties = properties;
    }

    /**
     * 종목 → 그룹 태그. event-themes의 large-benchmark + B3_LARGE_CAP_SYMBOLS → LARGE,
     * 그 외 chain류(semi-chain/battery/bio/game/robot) → MIDSMALL. semi-anchor는 B-3
     * 6종목과 중복이라 건너뛴다(이미 LARGE로 태깅됨).
     */
    static Map<String, String> buildGroupTags(BacktestDataProperties properties) {
        Map<String, String> group = new HashMap<>();
        for (String s : B3_LARGE_CAP_SYMBOLS) {
            group.put(s, "LARGE");
        }
        for (Map.Entry<String, List<String>> e : properties.getEventThemes().entrySet()) {
            String theme = e.getKey();
            if (theme.endsWith("-anchor")) continue;
            String tag = "large-benchmark".equals(theme) ? "LARGE" : "MIDSMALL";
            for (String s : e.getValue()) {
                group.putIfAbsent(s, tag);
            }
        }
        return group;
    }

    public List<EventStatsBacktester.EventStat> compute(
            List<String> symbols, LocalDate from, LocalDate to) {
        Map<String, String> groupOf = buildGroupTags(properties);
        List<String> large = symbols.stream()
                .filter(s -> "LARGE".equals(groupOf.get(s))).toList();
        List<String> midsmall = symbols.stream()
                .filter(s -> "MIDSMALL".equals(groupOf.get(s))).toList();

        List<EventStatsBacktester.EventStat> grouped = new java.util.ArrayList<>();
        if (!large.isEmpty()) {
            grouped.addAll(tagGroup(statsBacktester.compute(large, from, to), "LARGE"));
        }
        if (!midsmall.isEmpty()) {
            grouped.addAll(tagGroup(statsBacktester.compute(midsmall, from, to), "MIDSMALL"));
        }
        return grouped;
    }

    /** eventType에 그룹 접미사를 붙인 새 EventStat — 레지스트리 키 30자 제한 내 */
    private static List<EventStatsBacktester.EventStat> tagGroup(
            List<EventStatsBacktester.EventStat> stats, String group) {
        return stats.stream()
                .map(s -> new EventStatsBacktester.EventStat(
                        s.eventType() + ":" + group, s.samples(), s.winRateD5(), s.horizons()))
                .toList();
    }
}
