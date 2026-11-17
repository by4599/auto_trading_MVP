package com.trading.backtest;

import com.trading.market.Candle;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.order.OrderHistoryRepository;
import com.trading.risk.PendingOrderRule;
import com.trading.risk.RiskResult;
import com.trading.risk.RiskRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.trading.backtest.ScaleInSimFixture.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 금액 비중 진입 + 분할 매수 하루 시퀀스 (scale-in-lab). 시나리오: ATR 20 · 손절폭 30 · 돌파가 110(체결 ≈110.11),
 * 자산 1,000만원 · 종목 목표 20% = 200만원 → 3등분 666,667원. k=0.5면 2차 트리거 ≈120.11, 3차 ≈130.11.
 */
@DisplayName("DailyBarSimulator + ScaleInExecutor — 분할 체결 가격·수량·추가 매수 조건")
class DailyBarScaleInTest {

    private static final LocalDate DAY2 = TODAY.plusDays(1);

    private ScaleInSimFixture splitFixture(ScaleInProperties.StopBasis basis, List<RiskRule> addOnRules) {
        ScaleInSimFixture f = new ScaleInSimFixture(addOnRules);
        f.props.setEnabled(true);
        f.props.setTranches(3);
        f.props.setStepAtr(0.5);
        f.props.setStopBasis(basis);
        return f;
    }

    @Test
    @DisplayName("기본값(OFF)이면 기존 R 사이징 그대로 — 3333주, 추가 매수 없음")
    void disabled_keepsRSizing() {
        ScaleInSimFixture f = new ScaleInSimFixture(List.of());
        f.setBar(new Candle(TODAY, 100, 135, 99, 130, 2000));

        f.simulate(TODAY);

        assertThat(f.position().getQuantity()).isEqualTo(3333);
        assertThat(f.stats.counters()).isEmpty();
    }

    @Test
    @DisplayName("C100: 1회에 목표 20%(200만원)를 전량 매수하고 추가 매수는 없다")
    void c100_buysWholeTargetOnce() {
        ScaleInSimFixture f = new ScaleInSimFixture(List.of());
        f.props.setEnabled(true);
        f.props.setTranches(1);
        f.setBar(new Candle(TODAY, 100, 135, 99, 130, 2000));

        f.simulate(TODAY);

        Position pos = f.position();
        double rawNotional = pos.getQuantity() * pos.getAveragePrice() / 1.001;
        assertThat(rawNotional).isCloseTo(2_000_000, within(120.0)); // 단주 내림 1주 이내
        assertThat(f.stats.counters()).containsEntry(ScaleInExecutor.C_FIRST, 1)
                .doesNotContainKey(ScaleInExecutor.C_ADD);
    }

    @Test
    @DisplayName("분할: 같은 날 고가가 2·3차 트리거를 넘으면 트리거 가격에 1/3씩 — 6060 + 5550 + 5123주")
    void split_fillsBothTriggersAtTriggerPrice() {
        ScaleInSimFixture f = splitFixture(ScaleInProfile.FIRST, List.of());
        f.setBar(new Candle(TODAY, 100, 135, 99, 130, 2000));

        f.simulate(TODAY);

        assertThat(f.position().getQuantity()).isEqualTo(6060 + 5550 + 5123);
        assertThat(f.stats.counters()).containsEntry(ScaleInExecutor.C_ADD, 2);
        // 손절=1차가 기준: 추가 매수 뒤에도 1차 체결가 − 30 그대로
        assertThat(f.position().getStopPrice()).isCloseTo(110.11 - 30, within(0.02));
    }

    @Test
    @DisplayName("손절=평균단가 기준: 추가 매수 뒤 손절선 = 평균단가 − 손절폭(30)")
    void split_averageBasis_rebasesStop() {
        ScaleInSimFixture f = splitFixture(ScaleInProfile.AVG, List.of());
        f.setBar(new Candle(TODAY, 100, 135, 99, 130, 2000));

        f.simulate(TODAY);

        Position pos = f.position();
        assertThat(pos.getAveragePrice()).isGreaterThan(115);
        assertThat(pos.getStopPrice()).isCloseTo(pos.getAveragePrice() - 30, within(1e-6));
    }

    @Test
    @DisplayName("1차 후 하락하면 추가 매수 없음(물타기 금지)")
    void split_noAddOnWhenPriceFalls() {
        ScaleInSimFixture f = splitFixture(ScaleInProfile.FIRST, List.of());
        f.setBar(new Candle(TODAY, 100, 115, 95, 112, 2000));
        f.simulate(TODAY);
        f.setBar(new Candle(DAY2, 105, 108, 90, 95, 2000));

        f.simulate(DAY2);

        assertThat(f.position().getQuantity()).isEqualTo(6060);
        assertThat(f.stats.counters()).doesNotContainKey(ScaleInExecutor.C_ADD);
    }

    @Test
    @DisplayName("다음 날 갭 상승으로 시가가 트리거 위면 시가에 체결(더 비싸게) — 5333주 @125")
    void split_gapUp_fillsAtOpen() {
        ScaleInSimFixture f = splitFixture(ScaleInProfile.FIRST, List.of());
        f.setBar(new Candle(TODAY, 100, 115, 95, 112, 2000));
        f.simulate(TODAY);
        double firstFill = f.position().getAveragePrice();
        f.setBar(new Candle(DAY2, 125, 126, 121, 124, 2000)); // 2차 트리거 ≈120.11 < 시가 125, 3차 130.11 > 고가

        f.simulate(DAY2);

        Position pos = f.position();
        assertThat(pos.getQuantity()).isEqualTo(6060 + 5333);
        double addFill = (pos.getAveragePrice() * pos.getQuantity() - 6060 * firstFill) / 5333;
        assertThat(addFill).isCloseTo(125 * 1.001, within(1e-6));
    }

    @Test
    @DisplayName("같은 날 손절선과 트리거를 둘 다 건드리면 손절 먼저 — 추가 매수 없이 1차분만 손절")
    void split_stopOverlap_stopWins() {
        ScaleInSimFixture f = splitFixture(ScaleInProfile.FIRST, List.of());
        f.setBar(new Candle(TODAY, 100, 125, 70, 100, 2000)); // 저가 70 ≤ 손절 80.11, 고가 125 ≥ 120.11

        f.simulate(TODAY);

        assertThat(f.position()).isNull();
        assertThat(f.tradeRecorder.getTrades()).singleElement()
                .extracting(TradeRecorder.ClosedTrade::quantity).isEqualTo(6060);
        assertThat(f.stats.counters()).containsEntry(ScaleInExecutor.C_ADD_STOP_OVERLAP, 1);
    }

    @Test
    @DisplayName("(c) 지수 하락 추세: 분할은 1차만, 추가 매수 금지")
    void split_indexDowntrend_firstTrancheOnly() {
        ScaleInSimFixture f = splitFixture(ScaleInProfile.FIRST, List.of());
        f.props.setIndexGate(ScaleInProperties.IndexGate.FIRST_TRANCHE_ONLY);
        f.indexBelow = true;
        f.setBar(new Candle(TODAY, 100, 135, 99, 130, 2000));

        f.simulate(TODAY);

        assertThat(f.position().getQuantity()).isEqualTo(6060);
        assertThat(f.stats.counters()).containsEntry(ScaleInExecutor.C_ADD_INDEX, 1);
    }

    @Test
    @DisplayName("(c) 지수 하락 추세: C100도 목표의 1/3만 산다")
    void c100_indexDowntrend_buysOneThird() {
        ScaleInSimFixture f = new ScaleInSimFixture(List.of());
        f.props.setEnabled(true);
        f.props.setTranches(1);
        f.props.setIndexGate(ScaleInProperties.IndexGate.FIRST_TRANCHE_ONLY);
        f.indexBelow = true;
        f.setBar(new Candle(TODAY, 100, 135, 99, 130, 2000));

        f.simulate(TODAY);

        assertThat(f.position().getQuantity()).isEqualTo(6060);
        assertThat(f.stats.counters()).containsEntry(ScaleInExecutor.C_FIRST_LIMITED, 1);
    }

    @Test
    @DisplayName("진입일 손절 규칙: 당일부터면 진입일 저가(70 ≤ 손절 80.11)로 손절, 다음 날부터면 그날은 안 판다")
    void entryDayStop_sameDayVsNextDay() {
        ScaleInSimFixture sameDay = new ScaleInSimFixture(List.of());
        sameDay.setBar(new Candle(TODAY, 100, 115, 70, 112, 2000));
        sameDay.simulate(TODAY);
        assertThat(sameDay.position()).isNull();

        ScaleInSimFixture nextDay = new ScaleInSimFixture(List.of());
        nextDay.stopRules.setEntryDayStop(BacktestStopRules.EntryDayStop.FROM_NEXT_DAY);
        nextDay.setBar(new Candle(TODAY, 100, 115, 70, 112, 2000));
        nextDay.simulate(TODAY);
        assertThat(nextDay.position().getQuantity()).isEqualTo(3333);
        assertThat(nextDay.position().getStopPrice()).isCloseTo(nextDay.position().getAveragePrice() - 30, within(1e-6));
        // 판정 기준은 '장 시작 때 보유'가 아니라 '오늘 진입' — 같은 날 손절 뒤 재진입분도 오늘 진입분으로 본다
        assertThat(nextDay.stopRules.judgeSameDayStop(ScaleInSimFixture.CODE, TODAY)).isFalse();
        assertThat(nextDay.stopRules.judgeSameDayStop(ScaleInSimFixture.CODE, DAY2)).isTrue();
        assertThat(sameDay.stopRules.judgeSameDayStop(ScaleInSimFixture.CODE, TODAY)).isTrue();
    }

    @Test
    @DisplayName("다음 날부터 규칙이어도 이월 보유분은 다음 날 저가로 손절된다")
    void entryDayStop_nextDay_stillStopsCarried() {
        ScaleInSimFixture f = new ScaleInSimFixture(List.of());
        f.stopRules.setEntryDayStop(BacktestStopRules.EntryDayStop.FROM_NEXT_DAY);
        f.setBar(new Candle(TODAY, 100, 115, 70, 112, 2000));
        f.simulate(TODAY);
        f.setBar(new Candle(DAY2, 105, 108, 75, 95, 2000)); // 저가 75 ≤ 손절 80.11

        f.simulate(DAY2);

        assertThat(f.position()).isNull();
    }

    @Test
    @DisplayName("손절 배수 오버라이드: 수량은 사이징 배수(1.5)로 3333주 그대로, 손절선만 체결가 − ATR×3.0")
    void stopMultiplier_override_keepsQuantity() {
        ScaleInSimFixture f = new ScaleInSimFixture(List.of());
        f.stopRules.setStopMultiplier(3.0);
        f.setBar(new Candle(TODAY, 100, 115, 70, 112, 2000)); // 저가 70 > 새 손절 ≈50.11 → 생존

        f.simulate(TODAY);

        assertThat(f.position().getQuantity()).isEqualTo(3333);
        assertThat(f.position().getStopPrice()).isCloseTo(f.position().getAveragePrice() - 60, within(1e-6));
    }

    @Test
    @DisplayName("손절 없음(배수 0): 손절가 0원 — 저가가 어디까지 빠져도 손절되지 않는다(옛 엔진 동작)")
    void stopMultiplier_zero_meansNoStop() {
        ScaleInSimFixture f = new ScaleInSimFixture(List.of());
        f.stopRules.setStopMultiplier(0.0);
        f.setBar(new Candle(TODAY, 100, 115, 1, 112, 2000));

        f.simulate(TODAY);

        assertThat(f.position().getQuantity()).isEqualTo(3333);
        assertThat(f.position().getStopPrice()).isZero();
    }

    @Test
    @DisplayName("추가 매수는 보유 종목 중복 차단 룰(PendingOrderRule)은 면제, 다른 룰 거부는 그대로 따른다")
    void addOn_exemptsPendingRule_butHonorsOthers() {
        PositionRepository heldRepo = mock(PositionRepository.class);
        Position held = Position.empty(ScaleInSimFixture.CODE);
        held.applyBuy(1, 100);
        when(heldRepo.findByStockCode(anyString())).thenReturn(Optional.of(held));
        RiskRule pending = new PendingOrderRule(heldRepo, mock(OrderHistoryRepository.class));

        ScaleInSimFixture exempt = splitFixture(ScaleInProfile.FIRST, List.of(pending));
        exempt.setBar(new Candle(TODAY, 100, 135, 99, 130, 2000));
        exempt.simulate(TODAY);
        assertThat(exempt.position().getQuantity()).isEqualTo(6060 + 5550 + 5123);

        RiskRule rejectBuys = (signal, account) ->
                signal.isBuy() ? RiskResult.reject("테스트 — 매수 금지") : RiskResult.pass();
        ScaleInSimFixture blocked = splitFixture(ScaleInProfile.FIRST, List.of(pending, rejectBuys));
        blocked.setBar(new Candle(TODAY, 100, 135, 99, 130, 2000));
        blocked.simulate(TODAY);
        assertThat(blocked.position().getQuantity()).isEqualTo(6060);
        assertThat(blocked.stats.counters()).containsEntry(ScaleInExecutor.C_ADD_RISK, 1);
    }
}
