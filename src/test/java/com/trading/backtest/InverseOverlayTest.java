package com.trading.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("InverseOverlay — 하락장 인버스 덧붙이기 (63_quant §0.3)")
class InverseOverlayTest {

    private static final LocalDate START = LocalDate.of(2022, 3, 1);
    private static final double CASH = 10_000_000;
    private static final double SLIP = BacktestCosts.SLIPPAGE_RATE;

    private static LocalDate day(int i) {
        return START.plusDays(i);
    }

    /** A동이 아무것도 안 하는 날들 — 현금 = 자산 = 1,000만원 */
    private static List<ScaleInRunStats.DayEnd> flatTape(int days) {
        List<ScaleInRunStats.DayEnd> tape = new ArrayList<>();
        for (int i = 0; i < days; i++) tape.add(new ScaleInRunStats.DayEnd(day(i), CASH, CASH));
        return tape;
    }

    /** 모든 날 시가=고가=저가=종가=price 인 봉 */
    private static Map<LocalDate, InverseOverlay.Bar> flatBars(int days, double price) {
        Map<LocalDate, InverseOverlay.Bar> bars = new HashMap<>();
        for (int i = 0; i < days; i++) bars.put(day(i), new InverseOverlay.Bar(day(i), price, price, price, price));
        return bars;
    }

    private static Predicate<LocalDate> bearOn(int... idx) {
        Set<LocalDate> set = new java.util.HashSet<>();
        for (int i : idx) set.add(day(i));
        return set::contains;
    }

    @Test
    @DisplayName("하락장 날이 없으면 합성 = A동 그대로, 거래 0건")
    void noBearDays_compositeEqualsBase() {
        InverseOverlay.WindowOutcome o = InverseOverlay.simulate(flatTape(5), flatBars(5, 10_000), d -> false,
                InverseOverlay.Config.of(1.0, InverseOverlay.Exit.E0), SLIP, CASH);
        assertThat(o.trades()).isEmpty();
        assertThat(o.composite()).containsOnly(CASH);
    }

    @Test
    @DisplayName("E0 — 하락장 첫날 시가 매수(전일 합성 자산 × 비중), 하락장 아닌 첫날 시가 매도, 비용은 주식과 같다")
    void e0_buysAtOpenSellsAtOpenOfFirstNonBearDay() {
        Map<LocalDate, InverseOverlay.Bar> bars = flatBars(6, 10_000);
        bars.put(day(4), new InverseOverlay.Bar(day(4), 11_000, 11_000, 11_000, 11_000));
        InverseOverlay.WindowOutcome o = InverseOverlay.simulate(flatTape(6), bars, bearOn(1, 2, 3),
                InverseOverlay.Config.of(0.5, InverseOverlay.Exit.E0), SLIP, CASH);

        double buyFill = 10_000 * (1 + SLIP);
        int qty = (int) Math.floor(5_000_000 / (buyFill * (1 + BacktestCosts.COMMISSION_RATE)));
        double cashOut = BacktestCosts.buyCashOut(buyFill, qty);
        double cashIn = BacktestCosts.sellCashIn(11_000 * (1 - SLIP), qty);
        assertThat(o.trades()).hasSize(1);
        TradeRecorder.ClosedTrade t = o.trades().get(0);
        assertThat(t.entryDate()).isEqualTo(day(1));
        assertThat(t.exitDate()).isEqualTo(day(4));
        assertThat(t.exitReason()).isEqualTo("RegimeUp");
        assertThat(t.quantity()).isEqualTo(qty);
        assertThat(t.pnl()).isCloseTo(cashIn - cashOut, within(1e-6));
        // 보유 중 마감 자산 = A동 + 평가손익(종가 × 수량 − 매수 현금)
        assertThat(o.composite().get(1)).isCloseTo(CASH + qty * 10_000 - cashOut, within(1e-6));
        assertThat(o.composite().get(5)).isCloseTo(CASH + cashIn - cashOut, within(1e-6));
    }

    @Test
    @DisplayName("수량 한도 — 전일 A동 현금이 목표보다 적으면 현금까지만 산다")
    void entry_cappedByPreviousCash() {
        List<ScaleInRunStats.DayEnd> tape = new ArrayList<>(flatTape(3));
        tape.set(0, new ScaleInRunStats.DayEnd(day(0), 1_000_000, CASH));   // 나머지 900만원은 A동 주식
        InverseOverlay.WindowOutcome o = InverseOverlay.simulate(tape, flatBars(3, 10_000), bearOn(1, 2),
                InverseOverlay.Config.of(1.0, InverseOverlay.Exit.E0), SLIP, CASH);
        double buyFill = 10_000 * (1 + SLIP);
        assertThat(o.trades().get(0).quantity())
                .isEqualTo((int) Math.floor(1_000_000 / (buyFill * (1 + BacktestCosts.COMMISSION_RATE))));
        assertThat(o.trades().get(0).exitReason()).isEqualTo("WindowEnd");
    }

    @Test
    @DisplayName("E2 — 저가가 손절선에 닿으면 손절선 가격에 팔고, 하락장 아닌 날이 오기 전엔 다시 안 산다")
    void e2_stopFillsAtStopAndBlocksUntilRegimeReset() {
        Map<LocalDate, InverseOverlay.Bar> bars = flatBars(8, 10_000);
        bars.put(day(2), new InverseOverlay.Bar(day(2), 9_800, 9_900, 9_200, 9_400));
        InverseOverlay.WindowOutcome o = InverseOverlay.simulate(flatTape(8), bars, bearOn(1, 2, 3, 4, 6, 7),
                InverseOverlay.Config.of(1.0, InverseOverlay.Exit.E2), SLIP, CASH);
        assertThat(o.trades()).hasSize(2);
        assertThat(o.trades().get(0).exitReason()).isEqualTo("Stop");
        assertThat(o.trades().get(0).proceeds())
                .isCloseTo(BacktestCosts.sellCashIn(9_300 * (1 - SLIP), o.trades().get(0).quantity()), within(1e-6));
        // 3·4일은 하락장이지만 금지, 5일 하락장 아님 → 해제, 6일 재진입
        assertThat(o.trades().get(1).entryDate()).isEqualTo(day(6));
    }

    @Test
    @DisplayName("E1 — 시가가 이미 트레일 선 아래면 시가에 판다 (고점 = 진입 시가와 이후 종가 중 최고)")
    void e1_gapBelowTrailFillsAtOpen() {
        Map<LocalDate, InverseOverlay.Bar> bars = flatBars(5, 10_000);
        bars.put(day(2), new InverseOverlay.Bar(day(2), 10_000, 11_000, 10_000, 11_000));   // 고점 11,000
        bars.put(day(3), new InverseOverlay.Bar(day(3), 10_000, 10_100, 9_900, 10_000));    // 선 10,120 아래 시가
        InverseOverlay.WindowOutcome o = InverseOverlay.simulate(flatTape(5), bars, bearOn(1, 2, 3, 4),
                InverseOverlay.Config.of(1.0, InverseOverlay.Exit.E1), SLIP, CASH);
        TradeRecorder.ClosedTrade t = o.trades().get(0);
        assertThat(t.exitReason()).isEqualTo("Trail");
        assertThat(t.exitDate()).isEqualTo(day(3));
        assertThat(t.proceeds()).isCloseTo(BacktestCosts.sellCashIn(10_000 * (1 - SLIP), t.quantity()), within(1e-6));
        assertThat(o.trades()).hasSize(1);   // 4일은 금지
    }

    @Test
    @DisplayName("E2 — 진입일에도 손절 판정을 한다(불리하게)")
    void e2_entryDayStopIsChecked() {
        Map<LocalDate, InverseOverlay.Bar> bars = flatBars(4, 10_000);
        bars.put(day(1), new InverseOverlay.Bar(day(1), 10_000, 10_000, 9_000, 9_500));
        InverseOverlay.WindowOutcome o = InverseOverlay.simulate(flatTape(4), bars, bearOn(1, 2, 3),
                InverseOverlay.Config.of(0.3, InverseOverlay.Exit.E2), SLIP, CASH);
        assertThat(o.trades()).hasSize(1);
        assertThat(o.trades().get(0).entryDate()).isEqualTo(o.trades().get(0).exitDate());
        assertThat(o.trades().get(0).exitReason()).isEqualTo("Stop");
    }

    @Test
    @DisplayName("E3 — 진입 후 40거래일째 종가에 팔고 금지, E0은 계속 든다")
    void e3_maxHoldClosesOnFortiethDay() {
        int days = 45;
        int[] bear = new int[days - 1];
        for (int i = 0; i < bear.length; i++) bear[i] = i + 1;
        InverseOverlay.WindowOutcome e3 = InverseOverlay.simulate(flatTape(days), flatBars(days, 10_000), bearOn(bear),
                InverseOverlay.Config.of(1.0, InverseOverlay.Exit.E3), SLIP, CASH);
        assertThat(e3.trades()).hasSize(1);
        assertThat(e3.trades().get(0).exitReason()).isEqualTo("MaxHold");
        assertThat(e3.trades().get(0).exitDate()).isEqualTo(day(41));
        InverseOverlay.WindowOutcome e0 = InverseOverlay.simulate(flatTape(days), flatBars(days, 10_000), bearOn(bear),
                InverseOverlay.Config.of(1.0, InverseOverlay.Exit.E0), SLIP, CASH);
        assertThat(e0.trades()).hasSize(1);
        assertThat(e0.trades().get(0).exitReason()).isEqualTo("WindowEnd");
    }

    @Test
    @DisplayName("격자 12칸 — 비중 3 × 청산 4, 순서 고정")
    void grid_isTwelveCellsInFixedOrder() {
        List<InverseOverlay.Config> grid = BearInverseLab.grid();
        assertThat(grid).hasSize(12);
        assertThat(grid.get(0).code()).isEqualTo("W30-E0");
        assertThat(grid.get(11).code()).isEqualTo("W100-E3");
    }
}
