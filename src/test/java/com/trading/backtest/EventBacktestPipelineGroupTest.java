package com.trading.backtest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시가총액 세분화(LARGE/MIDSMALL) 그룹 태깅 — B-3 유니버스 확장 재검증(2026-07-20)의
 * §8 재검증 로직. large-benchmark/6종목은 LARGE, 나머지 chain류는 MIDSMALL로
 * 태깅되는지, semi-anchor(중복 종목)가 이미 태깅된 값을 덮어쓰지 않는지 검증.
 */
@DisplayName("EventBacktestPipeline — 시가총액 그룹 태깅")
class EventBacktestPipelineGroupTest {

    private BacktestDataProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BacktestDataProperties();
        properties.setSymbols(List.of("005930", "000660", "373220", "005380", "035420", "068270"));
        properties.setEventThemes(Map.of(
                "semi-anchor", List.of("005930", "000660"),
                "semi-chain", List.of("036930", "240810"),
                "battery", List.of("247540"),
                "large-benchmark", List.of("402340", "105560")
        ));
    }

    @Test
    @DisplayName("B-3 6종목 + large-benchmark → LARGE")
    void tags_large_symbols() {
        Map<String, String> group = EventBacktestPipeline.buildGroupTags(properties);

        assertThat(group.get("005930")).isEqualTo("LARGE");
        assertThat(group.get("068270")).isEqualTo("LARGE");
        assertThat(group.get("402340")).isEqualTo("LARGE");
        assertThat(group.get("105560")).isEqualTo("LARGE");
    }

    @Test
    @DisplayName("semi-chain/battery 등 chain류 → MIDSMALL")
    void tags_midsmall_symbols() {
        Map<String, String> group = EventBacktestPipeline.buildGroupTags(properties);

        assertThat(group.get("036930")).isEqualTo("MIDSMALL");
        assertThat(group.get("240810")).isEqualTo("MIDSMALL");
        assertThat(group.get("247540")).isEqualTo("MIDSMALL");
    }

    @Test
    @DisplayName("semi-anchor 중복 종목(005930/000660)은 이미 LARGE로 태깅되어 있어 그대로 유지")
    void anchor_duplicates_stay_large() {
        Map<String, String> group = EventBacktestPipeline.buildGroupTags(properties);

        assertThat(group.get("005930")).isEqualTo("LARGE");
        assertThat(group.get("000660")).isEqualTo("LARGE");
    }

    @Test
    @DisplayName("어느 목록에도 없는 종목은 태깅되지 않는다")
    void untagged_symbol_absent() {
        assertThat(EventBacktestPipeline.buildGroupTags(properties)).doesNotContainKey("999999");
    }
}
