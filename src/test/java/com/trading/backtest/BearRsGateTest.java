package com.trading.backtest;

import com.trading.market.Candle;
import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.Timeframe;
import com.trading.risk.RiskLimitsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 하락장 상대강도 예외 스위치 — 리포지토리(인터페이스)만 목, 나머지는 실객체.
 *
 * <p>합성 시계열: 300일. 지수는 마지막 5일만 100 → 80 (전일 종가 &lt; MA120 → 하락장). 오늘 = 300일째.
 * N=60 지수 수익률 −20%. 종목 S1·S2 평탄(RS +20%), S3 −10%(RS +10%), S4 −25%(RS −5%), S5 −50%(RS −30%).
 */
@DisplayName("BearRsGate — 하락장 날만 상대강도로 진입 허용 (55_quant)")
class BearRsGateTest {

    private static final LocalDate START = LocalDate.of(2021, 1, 1);
    private static final int LENGTH = 300;
    private static final LocalDate TODAY = START.plusDays(LENGTH);
    private static final LocalDate CALM_DAY = START.plusDays(LENGTH - 5); // 전날까지 지수 평탄 → 하락장 아님
    private static final List<String> UNIVERSE = List.of("S1", "S2", "S3", "S4", "S5");

    private CandleHistoryRepository repo;
    private RiskLimitsProperties limits;
    private ScaleInRunStats stats;
    private BearRsGate gate;

    @BeforeEach
    void setUp() {
        repo = mock(CandleHistoryRepository.class);
        limits = new RiskLimitsProperties();
        limits.setRiskFractionPerTrade(0.0025);
        stats = new ScaleInRunStats();
        stats.beginRun("WF00-validate-K0.5");
        BacktestDataProperties properties = new BacktestDataProperties();
        stub(properties.getKospiStorageCode(), i -> i < LENGTH - 5 ? 100 : 80, -1);
        stub("S1", i -> 100, -1);
        stub("S2", i -> 100, -1);
        stub("S3", i -> i < LENGTH - 5 ? 100 : 90, -1);
        stub("S4", i -> i < LENGTH - 5 ? 100 : 75, -1);
        stub("S5", i -> i < LENGTH - 5 ? 100 : 50, -1);
        gate = new BearRsGate(repo, properties, limits, stats);
    }

    /** close(i) 시계열 + 고가 = 종가×1.01. spikeDay ≥ 0이면 그날 고가만 1,000. 오늘 봉은 극단값(쓰이면 안 됨) */
    private void stub(String code, IntToDoubleFunction close, int spikeDay) {
        List<CandleHistory> rows = new ArrayList<>();
        for (int i = 0; i < LENGTH; i++) {
            double c = close.applyAsDouble(i);
            double high = i == spikeDay ? 1_000 : c * 1.01;
            rows.add(CandleHistory.ofDaily(code, new Candle(START.plusDays(i), c, high, c * 0.99, c, 1000)));
        }
        rows.add(CandleHistory.ofDaily(code, new Candle(TODAY, 1, 1_000_000, 1, 1, 1000)));
        when(repo.findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                eq(code), eq(Timeframe.DAILY), any(), any())).thenReturn(rows);
    }

    @Test
    @DisplayName("꺼져 있으면 하락장 날에도 전부 허용 + 사이징 불변 (기존 모드 결과 불변)")
    void disabled_isNoOp() {
        assertThat(gate.isBearDay(TODAY)).isTrue();
        assertThat(gate.permits("S5", TODAY)).isTrue();
        AtomicReference<Double> seen = new AtomicReference<>();
        gate.executeSized(TODAY, () -> seen.set(limits.getRiskFractionPerTrade()));
        assertThat(seen.get()).isEqualTo(0.0025);
        assertThat(stats.counters()).isEmpty();
    }

    @Test
    @DisplayName("하락장 판정 — 전일 지수 종가 < MA120. 당일 봉(극단값)은 판정에 쓰이지 않는다")
    void bearDay_definition() {
        assertThat(gate.isBearDay(TODAY)).isTrue();
        assertThat(gate.isBearDay(CALM_DAY)).isFalse();
    }

    @Test
    @DisplayName("하락장이 아닌 날은 조건과 무관하게 허용")
    void calmDay_alwaysPermits() {
        gate.configure(BearRsConfig.of(60, BearRsConfig.Condition.B_TOP, false), UNIVERSE);
        assertThat(gate.permits("S5", CALM_DAY)).isTrue();
        assertThat(stats.counters()).isEmpty();
    }

    @Test
    @DisplayName("(a) RS > 0 — 지수보다 덜 떨어진 종목만")
    void conditionA_positiveRs() {
        gate.configure(BearRsConfig.of(60, BearRsConfig.Condition.A_POSITIVE, false), UNIVERSE);
        assertThat(gate.rs("S3", TODAY, 60).getAsDouble()).isCloseTo(0.10, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(gate.permits("S1", TODAY)).isTrue();
        assertThat(gate.permits("S3", TODAY)).isTrue();
        assertThat(gate.permits("S4", TODAY)).isFalse();
        assertThat(stats.counters()).containsEntry("하락장 허용", 2).containsEntry("하락장 불허", 1);
    }

    @Test
    @DisplayName("(b) 상위 20% — 5종목이면 k=1, 1등 동점(S1·S2)은 둘 다 허용")
    void conditionB_topQuintileWithTies() {
        gate.configure(BearRsConfig.of(60, BearRsConfig.Condition.B_TOP, false), UNIVERSE);
        assertThat(gate.permits("S1", TODAY)).isTrue();
        assertThat(gate.permits("S2", TODAY)).isTrue();
        assertThat(gate.permits("S3", TODAY)).isFalse(); // RS>0이지만 상위 20% 밖
    }

    @Test
    @DisplayName("(c) 상위 20% + 전일 종가 ≥ 0.9×250일 고가 — 창 안 고가가 높으면 불허")
    void conditionC_requiresNearHigh() {
        gate.configure(BearRsConfig.of(60, BearRsConfig.Condition.C_TOP_NEAR_HIGH, false), UNIVERSE);
        assertThat(gate.permits("S1", TODAY)).isTrue();   // 100 ≥ 0.9 × 101

        stub("S2", i -> 100, LENGTH - 100);               // 100일 전 고가 1,000 → 100 < 900
        BearRsGate fresh = new BearRsGate(repo, new BacktestDataProperties(), limits, stats);
        fresh.configure(BearRsConfig.of(60, BearRsConfig.Condition.C_TOP_NEAR_HIGH, false), UNIVERSE);
        assertThat(fresh.permits("S2", TODAY)).isFalse();
        assertThat(fresh.permits("S1", TODAY)).isTrue();
    }

    @Test
    @DisplayName("RS를 못 내면(종목 데이터 없음) 하락장 날 불허")
    void missingRs_refused() {
        gate.configure(BearRsConfig.of(60, BearRsConfig.Condition.A_POSITIVE, false), UNIVERSE);
        assertThat(gate.permits("NODATA", TODAY)).isFalse();
    }

    @Test
    @DisplayName("하락장분 절반 사이징 — 그 한 건만 1R 비율 절반, 끝나면 원복. 평시 진입은 그대로")
    void halfSize_onlyBearEntry() {
        gate.configure(BearRsConfig.of(60, BearRsConfig.Condition.A_POSITIVE, true), UNIVERSE);
        AtomicReference<Double> seen = new AtomicReference<>();
        gate.executeSized(TODAY, () -> seen.set(limits.getRiskFractionPerTrade()));
        assertThat(seen.get()).isEqualTo(0.00125);
        assertThat(limits.getRiskFractionPerTrade()).isEqualTo(0.0025);

        gate.executeSized(CALM_DAY, () -> seen.set(limits.getRiskFractionPerTrade()));
        assertThat(seen.get()).isEqualTo(0.0025);
    }

    @Test
    @DisplayName("주문이 예외를 던져도 1R 비율은 원복된다")
    void halfSize_restoresOnException() {
        gate.configure(BearRsConfig.of(60, BearRsConfig.Condition.A_POSITIVE, true), UNIVERSE);
        try {
            gate.executeSized(TODAY, () -> { throw new IllegalStateException("boom"); });
        } catch (IllegalStateException expected) {
            // 의도된 예외
        }
        assertThat(limits.getRiskFractionPerTrade()).isEqualTo(0.0025);
    }
}
