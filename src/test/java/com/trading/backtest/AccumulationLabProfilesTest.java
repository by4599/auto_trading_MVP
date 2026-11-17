package com.trading.backtest;

import com.trading.strategy.AccumulationRule;
import com.trading.strategy.AccumulationRule.Family;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * accumulation-lab 격자 = 사전 등록(`_workspace/51_quant` §0.4) 그대로인지, 겹침 집계가 맞는지 못 박는다.
 * 순수 단위 테스트 — 스프링·Mockito 없이 상수와 계산만 본다.
 */
@DisplayName("AccumulationLab — 사전 등록 격자 10칸 · 고정 조건 · 겹침 집계")
class AccumulationLabProfilesTest {

    @Test
    @DisplayName("격자는 사전 등록 10칸 — 순서·값 고정 (A 3칸 · B 4칸 · C 1칸 · NT 2칸)")
    void gridMatchesPreRegistration() {
        List<AccumulationLab.Cell> grid = AccumulationLab.grid();

        assertThat(grid).hasSize(10);
        assertThat(grid).extracting(c -> c.rule().family()).containsExactly(
                Family.QUIET_SPIKE, Family.QUIET_SPIKE, Family.QUIET_SPIKE,
                Family.CLUSTER_BASE, Family.CLUSTER_BASE, Family.CLUSTER_BASE, Family.CLUSTER_BASE,
                Family.OBV_DIVERGENCE, Family.QUIET_SPIKE, Family.CLUSTER_BASE);
        assertThat(grid.subList(0, 3)).extracting(c -> c.rule().spikeMultiple()).containsExactly(2.0, 3.0, 4.0);
        assertThat(grid.subList(3, 7)).extracting(c -> c.rule().clusterMinDays()).containsExactly(3, 3, 4, 4);
        assertThat(grid.subList(3, 7)).extracting(c -> c.rule().maxBaseRange()).containsExactly(0.11, 0.17, 0.11, 0.17);
        assertThat(grid.get(8).rule()).isEqualTo(AccumulationRule.quietSpike(3.0).withoutTrend());
        assertThat(grid.get(9).rule()).isEqualTo(AccumulationRule.clusterBase(3, 0.17).withoutTrend());
    }

    @Test
    @DisplayName("NT 2칸만 종목 추세 조건이 없고, 전 칸이 거래량 평균 창 20일")
    void trendFilterOnlyOffForNtCells() {
        List<AccumulationLab.Cell> grid = AccumulationLab.grid();

        assertThat(grid.subList(0, 8)).allSatisfy(c -> assertThat(c.rule().trendFilter()).isTrue());
        assertThat(grid.subList(8, 10)).allSatisfy(c -> {
            assertThat(c.rule().trendFilter()).isFalse();
            assertThat(c.name()).contains("NT");
        });
        assertThat(grid).allSatisfy(c -> assertThat(c.rule().volumeAvgWindow()).isEqualTo(20));
        assertThat(grid).extracting(AccumulationLab.Cell::name).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("조건부 후속(§0.5-5)은 A가족 중심 칸 A3의 거래량 평균창 20·16·24일 — 다른 값은 그대로")
    void sensitivityCellsVaryOnlyTheAverageWindow() {
        List<AccumulationLab.Cell> cells = AccumulationLab.sensitivityCells();

        assertThat(cells).extracting(c -> c.rule().volumeAvgWindow()).containsExactly(20, 16, 24);
        assertThat(cells).allSatisfy(c -> {
            assertThat(c.rule().family()).isEqualTo(Family.QUIET_SPIKE);
            assertThat(c.rule().spikeMultiple()).isEqualTo(3.0);
            assertThat(c.rule().trendFilter()).isTrue();
        });
        assertThat(cells.get(0).rule()).isEqualTo(AccumulationLab.grid().get(1).rule()); // 본 실행 A3와 같은 규칙
    }

    @Test
    @DisplayName("고정 조건은 A동 검증 설정(§15.7 D0) — 0.25R · 동시5 · 지수 MA120 · 돈치안 20/120")
    void fixedConditionsMatchD0() {
        assertThat(AccumulationLab.SZ2_RISK_FRACTION).isEqualTo(0.0025);
        assertThat(AccumulationLab.MAX_POSITIONS).isEqualTo(5);
        assertThat(AccumulationLab.INDEX_TREND_MA).isEqualTo(120);
        assertThat(AccumulationLab.DONCHIAN_LOOKBACK).isEqualTo(20);
        assertThat(AccumulationLab.DONCHIAN_TREND_MA).isEqualTo(120);
    }

    // ── 겹침 집계 ─────────────────────────────────────────────────────────────

    private static TradeRecorder.ClosedTrade trade(String code, LocalDate entry) {
        return new TradeRecorder.ClosedTrade(code, entry, entry.plusDays(3), 10, 1000, 1010, "X");
    }

    @Test
    @DisplayName("같은 종목·같은 진입일만 '같은 날 겹침', ±5일 이내는 '근접 겹침'(같은 날 포함)으로 센다")
    void overlapCountsSameDayAndNearby() {
        LocalDate d = LocalDate.of(2024, 3, 4);
        List<TradeRecorder.ClosedTrade> cell = List.of(
                trade("005930", d),               // 같은 날 겹침
                trade("000660", d.plusDays(4)),   // 기준선 000660은 d → 4일 차이: 근접
                trade("035420", d.plusDays(6)),   // 기준선 035420은 d → 6일 차이: 겹침 아님
                trade("068270", d));              // 기준선에 없는 종목
        List<TradeRecorder.ClosedTrade> baseline = List.of(
                trade("005930", d), trade("000660", d), trade("035420", d), trade("005380", d));

        TradeOverlap o = TradeOverlap.measure(cell, baseline, 5);

        assertThat(o.total()).isEqualTo(4);
        assertThat(o.sameDay()).isEqualTo(1);
        assertThat(o.nearby()).isEqualTo(2);
        assertThat(o.sameDayRatio()).isEqualTo(0.25);
    }

    @Test
    @DisplayName("칸 트레이드가 없으면 0건·비율 0 (0으로 나누지 않는다)")
    void overlapEmpty() {
        TradeOverlap o = TradeOverlap.measure(List.of(), List.of(trade("005930", LocalDate.of(2024, 1, 2))), 5);

        assertThat(o.total()).isZero();
        assertThat(o.sameDayRatio()).isZero();
        assertThat(o.nearbyRatio()).isZero();
    }
}
