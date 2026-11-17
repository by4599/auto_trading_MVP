package com.trading.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("InverseOverlayStats · InverseData · BearInverseReport — 63_quant 집계·데이터·칸 표시")
class InverseOverlayStatsTest {

    private static final double CASH = 10_000_000;
    private static final LocalDate D = LocalDate.of(2022, 5, 2);

    private static TradeRecorder.ClosedTrade trade(LocalDate entry, double pnl) {
        return new TradeRecorder.ClosedTrade(InverseOverlay.CODE, entry, entry.plusDays(1), 1, 1_000_000, 1_000_000 + pnl, "t");
    }

    @Test
    @DisplayName("창 연결 — 창마다 1,000만원 재시작 수익률을 곱하고, 창내 최악 낙폭·인버스 건수를 센다")
    void aggregate_chainsWindowsLikeEngine() {
        InverseOverlay.WindowOutcome w1 = new InverseOverlay.WindowOutcome(List.of(10_500_000.0, 11_000_000.0),
                List.of(trade(D, 500_000)));
        InverseOverlay.WindowOutcome w2 = new InverseOverlay.WindowOutcome(List.of(9_000_000.0, 9_500_000.0), List.of());
        InverseOverlayStats.Cell c = InverseOverlayStats.aggregate("x",
                List.of(List.of(), List.of(trade(D, -200_000))), List.of(w1, w2), CASH);
        assertThat(c.metrics().tradeCount()).isEqualTo(2);
        assertThat(c.inverseTrades()).isEqualTo(1);
        assertThat(c.metrics().finalEquity()).isCloseTo(CASH * 1.10 * 0.95, within(1e-3));
        assertThat(c.metrics().profitFactor()).isCloseTo(2.5, within(1e-9));
        assertThat(c.worstWindowMdd()).isCloseTo(0.10, within(1e-9));   // 창2: 1,000만 → 900만
        assertThat(c.daysBelow3()).isEqualTo(1);
        assertThat(c.daysBelow5()).isEqualTo(1);
    }

    @Test
    @DisplayName("창 수가 다르면 거부")
    void aggregate_rejectsMismatchedWindows() {
        assertThatThrownBy(() -> InverseOverlayStats.aggregate("x", List.of(), List.of(
                new InverseOverlay.WindowOutcome(List.of(CASH), List.of())), CASH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("하위 집계 — 2022 · 2024-08 진입분만")
    void subset_filtersByEntryDate() {
        InverseOverlay.WindowOutcome o = new InverseOverlay.WindowOutcome(List.of(CASH), List.of(
                trade(D, 300_000), trade(LocalDate.of(2024, 8, 5), -100_000), trade(LocalDate.of(2023, 1, 2), 50_000)));
        InverseOverlayStats.Subset y = InverseOverlayStats.subset(List.of(o), InverseOverlayStats.year(2022));
        assertThat(y.count()).isEqualTo(1);
        assertThat(y.pnl()).isCloseTo(300_000, within(1e-6));
        InverseOverlayStats.Subset aug = InverseOverlayStats.subset(List.of(o),
                InverseOverlayStats.month(InverseOverlayStats.AUG_2024));
        assertThat(aug.count()).isEqualTo(1);
        assertThat(aug.profitFactor()).isZero();
    }

    @Test
    @DisplayName("합성 −1배 — 전일 종가 대비 지수 수익률을 뒤집고, 고가·저가가 서로 바뀌며, 종가에 보수를 일할 차감")
    void synthetic_invertsDailyReturns() {
        List<InverseOverlay.Bar> idx = List.of(
                new InverseOverlay.Bar(D, 2500, 2510, 2490, 2500),
                new InverseOverlay.Bar(D.plusDays(1), 2525, 2550, 2450, 2450));
        List<InverseOverlay.Bar> s = InverseData.synthetic(idx, 10_000);
        InverseOverlay.Bar b = s.get(1);
        assertThat(b.open()).isCloseTo(9_900, within(1e-6));
        assertThat(b.high()).isCloseTo(10_200, within(1e-6));
        assertThat(b.low()).isCloseTo(9_800, within(1e-6));
        assertThat(b.close()).isCloseTo(10_200 * (1 - InverseData.SYNTH_ANNUAL_FEE / 252), within(1e-6));
    }

    @Test
    @DisplayName("수취 금지 시각 — 평일 08:30~15:40 KST만 막는다")
    void marketHours_blockWindow() {
        ZoneId kst = InverseData.KST;
        assertThat(InverseData.isMarketHours(ZonedDateTime.of(2026, 11, 17, 10, 0, 0, 0, kst))).isTrue();
        assertThat(InverseData.isMarketHours(ZonedDateTime.of(2026, 11, 17, 8, 29, 0, 0, kst))).isFalse();
        assertThat(InverseData.isMarketHours(ZonedDateTime.of(2026, 11, 17, 15, 40, 0, 0, kst))).isFalse();
        assertThat(InverseData.isMarketHours(ZonedDateTime.of(2026, 11, 21, 10, 0, 0, 0, kst))).isFalse();   // 토요일
    }

    @Test
    @DisplayName("칸 표시 (b) — 합성 총수익이 A동 단독보다 높아야 통과, (c) — 창내 최악 15% 초과면 실패")
    void flags_followPreRegisteredRule() {
        InverseOverlayStats.Cell alone = cell(1.0, 0.05, 0.05);
        InverseOverlayStats.Cell better = cell(1.2, 0.06, 0.10);
        InverseOverlayStats.Cell deep = cell(1.2, 0.06, 0.16);
        InverseOverlayStats.Cell solo = cell(0.1, 0.02, 0.02);
        assertThat(BearInverseReport.flags(alone, better, solo).b()).isTrue();
        assertThat(BearInverseReport.flags(alone, better, solo).c()).isTrue();
        assertThat(BearInverseReport.flags(alone, deep, solo).c()).isFalse();
        assertThat(BearInverseReport.flags(better, alone, solo).b()).isFalse();
    }

    @Test
    @DisplayName("하루 마감 기록 — 검증 런만 라벨별로 남기고, clear()로 비운다")
    void runStatsTape_recordsValidateRunsOnly() {
        ScaleInRunStats stats = new ScaleInRunStats();
        stats.beginRun("WF00-train-K0.5");
        stats.sampleDayEnd(D, 1, 2);
        stats.beginRun("WF00-validate-K0.5");
        stats.sampleDayEnd(D, 3, 4);
        stats.sampleDayEnd(D.plusDays(1), 5, 6);
        assertThat(stats.tapes()).containsOnlyKeys("WF00-validate-K0.5");
        assertThat(stats.tapes().get("WF00-validate-K0.5"))
                .containsExactly(new ScaleInRunStats.DayEnd(D, 3, 4), new ScaleInRunStats.DayEnd(D.plusDays(1), 5, 6));
        assertThat(stats.averageCashRatio()).isCloseTo((0.75 + 5.0 / 6) / 2, within(1e-12));
        stats.clear();
        assertThat(stats.tapes()).isEmpty();
    }

    private static InverseOverlayStats.Cell cell(double totalReturn, double mdd, double worst) {
        BacktestMetrics m = new BacktestMetrics(150, 0.5, 1.5, 0.01, mdd, totalReturn, CASH * (1 + totalReturn), 2, 5);
        return new InverseOverlayStats.Cell("c", m, worst, 3, 0, 0);
    }
}
