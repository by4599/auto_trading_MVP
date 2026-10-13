package com.trading.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분봉 페이지 누적 종료 조건 — KIS가 장 시작 아래 커서에 같은 창을 되돌려주는 탓에
 * 전 종목이 페이지 상한(60)을 소진하던 문제(2026-08-04 실측)의 회귀 방지.
 */
@DisplayName("KisCandleHistoryClient.accumulatePage — 분봉 페이지 누적·종료 판정")
class KisCandleHistoryClientTest {

    private static final LocalTime OPEN = LocalTime.of(9, 0);

    private KisCandleHistoryClient.MinuteData row(String hhmmss, double close) {
        return new KisCandleHistoryClient.MinuteData(
                "20260804", hhmmss, "100", "101", "99", String.valueOf(close), "500");
    }

    @Test
    @DisplayName("새 분이 담기면 true, 분 단위로 저장된다")
    void accumulates_new_minutes() {
        Map<LocalTime, MinuteCandle> into = new TreeMap<>();

        boolean added = KisCandleHistoryClient.accumulatePage(
                List.of(row("150000", 100), row("145900", 101)), OPEN, into);

        assertThat(added).isTrue();
        assertThat(into).hasSize(2);
        assertThat(into.keySet()).containsExactly(LocalTime.of(14, 59), LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("이미 받은 분만 들어오면 false — 여기서 페이지네이션을 멈춘다")
    void returns_false_when_page_has_no_new_minutes() {
        Map<LocalTime, MinuteCandle> into = new TreeMap<>();
        KisCandleHistoryClient.accumulatePage(List.of(row("150000", 100)), OPEN, into);

        boolean added = KisCandleHistoryClient.accumulatePage(
                List.of(row("150000", 999)), OPEN, into);

        assertThat(added).isFalse();
        assertThat(into).hasSize(1);
        assertThat(into.get(LocalTime.of(15, 0)).close()).isEqualTo(100); // 첫 값 유지
    }

    @Test
    @DisplayName("장 시작 이전 봉은 버린다 — 그 페이지에 그것뿐이면 false")
    void drops_pre_market_rows() {
        Map<LocalTime, MinuteCandle> into = new TreeMap<>();

        boolean added = KisCandleHistoryClient.accumulatePage(
                List.of(row("085900", 100), row("083000", 100)), OPEN, into);

        assertThat(added).isFalse();
        assertThat(into).isEmpty();
    }

    @Test
    @DisplayName("09:00 정각은 장중으로 포함한다")
    void includes_market_open_minute() {
        Map<LocalTime, MinuteCandle> into = new TreeMap<>();

        boolean added = KisCandleHistoryClient.accumulatePage(List.of(row("090000", 100)), OPEN, into);

        assertThat(added).isTrue();
        assertThat(into).containsKey(OPEN);
    }
}
