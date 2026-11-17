package com.trading.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("55_quant 격자·하락장 거래 집계")
class BearRsGridAndStatsTest {

    @Test
    @DisplayName("사전 등록 14칸 — 코드와 순서 고정, 대조 2칸은 예외 없음")
    void grid_isPreRegisteredOrder() {
        List<BearRsProfile> grid = BearRsProfile.grid();
        assertThat(grid).extracting(BearRsProfile::code).containsExactly(
                "D0", "NOF",
                "R60a-F", "R60a-H", "R60b-F", "R60b-H", "R60c-F", "R60c-H",
                "R120a-F", "R120a-H", "R120b-F", "R120b-H", "R120c-F", "R120c-H");
        assertThat(grid.get(0).indexFilter()).isTrue();
        assertThat(grid.get(0).gate()).isNull();
        assertThat(grid.get(1).indexFilter()).isFalse();
        assertThat(grid.get(1).gate()).isNull();
        assertThat(grid.subList(2, 14)).allSatisfy(p -> {
            assertThat(p.indexFilter()).isFalse();
            assertThat(p.gate().topFraction()).isEqualTo(0.20);
            assertThat(p.gate().highRatio()).isEqualTo(0.9);
        });
    }

    private static TradeRecorder.ClosedTrade trade(LocalDate entry, double pnl) {
        return new TradeRecorder.ClosedTrade("X", entry, entry.plusDays(3), 1, 100, 100 + pnl, "t");
    }

    @Test
    @DisplayName("하락장 거래 집계 — 진입일 기준으로 거르고 2022 · 2024-08 하위 집계")
    void summarize_filtersByEntryDay() {
        LocalDate bear2022 = LocalDate.of(2022, 5, 2);
        LocalDate bearAug = LocalDate.of(2024, 8, 6);
        LocalDate calm = LocalDate.of(2023, 3, 2);
        var window = new WalkForwardEngine.WindowResult(null, 0.5, null, null, List.of(
                trade(bear2022, 10), trade(bear2022, -5), trade(bearAug, -4), trade(calm, 50)));
        BearRsTradeStats.Summary s = BearRsTradeStats.summarize("T", List.of(window), d -> !d.equals(calm));
        assertThat(s.all().count()).isEqualTo(3);
        assertThat(s.all().profitFactor()).isCloseTo(10.0 / 9.0, within(1e-9));
        assertThat(s.y2022().count()).isEqualTo(2);
        assertThat(s.aug2024().count()).isEqualTo(1);
        assertThat(s.aug2024().expectancyPct()).isCloseTo(-0.04, within(1e-9));
        assertThat(BearRsTradeStats.markdown(List.of(s))).contains("표본 부족(<100)");
    }
}
