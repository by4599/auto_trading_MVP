package com.trading.backtest;

import com.trading.backtest.EntryTriggerBacktester.Trigger;
import com.trading.backtest.EntryTriggerBacktester.TriggerStat;
import com.trading.market.Candle;
import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.Timeframe;
import com.trading.research.DisclosureItem;
import com.trading.research.DisclosureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 진입 트리거 실험 (B-4 확장 3단계) — 트리거별 진입가 산식(보수 근사),
 * 발동 기한, 발동률/표본 분리를 합성 캔들로 검증.
 */
@DisplayName("EntryTriggerBacktester — 즉시/눌림반등/돌파 진입 격리 비교")
class EntryTriggerBacktesterTest {

    private static final LocalDate BASE = LocalDate.of(2026, 1, 5); // 월요일
    private static final String ANCHOR = "900001";
    private static final List<String> CHAIN = List.of("100001", "100002", "100003");

    private DisclosureRepository disclosureRepository;
    private CandleHistoryRepository candleHistoryRepository;
    private BacktestDataProperties properties;
    private EntryTriggerBacktester sut;

    @BeforeEach
    void setUp() {
        disclosureRepository = mock(DisclosureRepository.class);
        candleHistoryRepository = mock(CandleHistoryRepository.class);
        properties = new BacktestDataProperties();
        properties.setEventThemes(Map.of(
                "semi-anchor", List.of(ANCHOR),
                "semi-chain", CHAIN));
        properties.setKosdaqSymbols(List.of());
        sut = new EntryTriggerBacktester(disclosureRepository, candleHistoryRepository, properties);
    }

    /** BASE부터 연속 거래일 캔들 — 행마다 {시가, 고가, 저가, 종가} */
    private void givenCandles(String stockCode, double[][] ohlc) {
        List<CandleHistory> candles = new ArrayList<>();
        for (int i = 0; i < ohlc.length; i++) {
            candles.add(CandleHistory.ofDaily(stockCode, new Candle(
                    BASE.plusDays(i), ohlc[i][0], ohlc[i][1], ohlc[i][2], ohlc[i][3], 1000)));
        }
        when(candleHistoryRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        eq(stockCode), eq(Timeframe.DAILY), any(), any()))
                .thenReturn(candles);
    }

    /** 시가 100 고정, 종가 close 고정인 밋밋한 캔들 count개 */
    private static double[][] flatRows(double close, int count) {
        double[][] rows = new double[count][];
        for (int i = 0; i < count; i++) {
            rows[i] = new double[]{100.0, Math.max(100.0, close) + 1,
                    Math.min(100.0, close) - 1, close};
        }
        return rows;
    }

    private void givenEvent() {
        when(disclosureRepository.findAll()).thenReturn(List.of(
                DisclosureItem.of(ANCHOR, "앵커회사", "R1", "보고서",
                        BASE, "POSITIVE", "SUPPLY_CONTRACT")));
    }

    private TriggerStat stat(List<TriggerStat> stats, Trigger trigger) {
        return stats.stream().filter(s -> s.trigger() == trigger).findFirst().orElseThrow();
    }

    private List<TriggerStat> compute() {
        return sut.compute(BASE.minusDays(10), BASE.plusDays(40));
    }

    @Test
    @DisplayName("IMMEDIATE = 다음날 시가 진입, D+10 종가 청산 — 밋밋한 캔들에선 나머지 트리거 미발동")
    void immediate_baseline_and_no_fire_on_flat_candles() {
        givenCandles("KOSPI", flatRows(100.0, 15)); // 지수 변동 없음 → 초과수익 = 원수익률
        for (String member : CHAIN) {
            givenCandles(member, flatRows(110.0, 15)); // 시가 100, 종가 110 고정
        }
        givenEvent();

        List<TriggerStat> stats = compute();

        TriggerStat immediate = stat(stats, Trigger.IMMEDIATE);
        assertThat(immediate.samples()).isEqualTo(1);
        assertThat(immediate.fireRate()).isEqualTo(1.0);
        assertThat(immediate.quantiles().median()).isCloseTo(0.10, within(1e-9)); // 110/100−1
        // 종가가 한 번도 안 꺾이고(눌림 없음) 고가가 이벤트일 고가를 넘지 않으면 미발동
        assertThat(stat(stats, Trigger.PULLBACK_REBOUND).fireRate()).isEqualTo(0.0);
        assertThat(stat(stats, Trigger.PULLBACK_REBOUND).samples()).isEqualTo(0);
        assertThat(stat(stats, Trigger.BREAKOUT).fireRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("BREAKOUT — 이벤트일 고가(105) 첫 돌파일에 max(시가, 기준가)로 진입한다")
    void breakout_enters_at_max_of_open_and_reference() {
        givenCandles("KOSPI", flatRows(100.0, 15));
        double[][] rows = {
                {100, 105, 95, 100},   // d0 (이벤트일) — 기준 고가 105
                {100, 104, 96, 100},   // D+1 — 미돌파
                {100, 104, 96, 100},   // D+2 — 미돌파
                {103, 106, 99, 104},   // D+3 — 고가 106 > 105 → 진입 = max(103, 105) = 105
                {100, 116, 90, 115.5}, {100, 116, 90, 115.5}, {100, 116, 90, 115.5},
                {100, 116, 90, 115.5}, {100, 116, 90, 115.5}, {100, 116, 90, 115.5},
                {100, 116, 90, 115.5},
                {100, 116, 90, 115.5}, // D+10 (idx 11) — 청산 종가 115.5
        };
        for (String member : CHAIN) givenCandles(member, rows);
        givenEvent();

        TriggerStat breakout = stat(compute(), Trigger.BREAKOUT);

        assertThat(breakout.samples()).isEqualTo(1);
        assertThat(breakout.fireRate()).isEqualTo(1.0);
        assertThat(breakout.quantiles().median()).isCloseTo(0.10, within(1e-9)); // 115.5/105−1
    }

    @Test
    @DisplayName("PULLBACK_REBOUND — 눌림(종가 하락) 후 눌림일 고가(103) 돌파일에 진입한다")
    void pullback_rebound_enters_above_pullback_high() {
        givenCandles("KOSPI", flatRows(100.0, 15));
        double[][] rows = {
                {100, 105, 95, 100},   // d0 — 종가 100
                {100, 103, 94, 98},    // D+1 — 종가 98 < 100 → 눌림, 고가 103
                {101, 104, 97, 102},   // D+2 — 고가 104 > 103 → 진입 = max(101, 103) = 103
                {100, 114, 95, 113.3}, {100, 114, 95, 113.3}, {100, 114, 95, 113.3},
                {100, 114, 95, 113.3}, {100, 114, 95, 113.3}, {100, 114, 95, 113.3},
                {100, 114, 95, 113.3}, {100, 114, 95, 113.3},
                {100, 114, 95, 113.3}, // D+10 (idx 11) — 청산 종가 113.3
        };
        for (String member : CHAIN) givenCandles(member, rows);
        givenEvent();

        TriggerStat rebound = stat(compute(), Trigger.PULLBACK_REBOUND);

        assertThat(rebound.samples()).isEqualTo(1);
        assertThat(rebound.fireRate()).isEqualTo(1.0);
        assertThat(rebound.quantiles().median()).isCloseTo(0.10, within(1e-9)); // 113.3/103−1
    }

    @Test
    @DisplayName("트리거가 D+5까지 완성되지 않으면 미발동 — D+6의 반등은 늦다")
    void signals_completing_after_deadline_do_not_fire() {
        givenCandles("KOSPI", flatRows(100.0, 15));
        double[][] rows = {
                {100, 105, 95, 100},   // d0
                {100, 102, 96, 101},   // D+1 — 상승
                {100, 103, 96, 102},   // D+2 — 상승
                {100, 104, 96, 103},   // D+3 — 상승
                {100, 105, 96, 104},   // D+4 — 상승
                {100, 105, 96, 103},   // D+5 — 종가 103 < 104 → 눌림 (기한 마지막 날)
                {110, 200, 96, 111},   // D+6 — 고가 200이지만 기한 밖 → 반등 인정 안 함
                {100, 105, 96, 104}, {100, 105, 96, 104}, {100, 105, 96, 104},
                {100, 105, 96, 104},
                {100, 105, 96, 104},   // D+10 (idx 11)
        };
        for (String member : CHAIN) givenCandles(member, rows);
        givenEvent();

        TriggerStat rebound = stat(compute(), Trigger.PULLBACK_REBOUND);

        assertThat(rebound.fireRate()).isEqualTo(0.0);
        assertThat(rebound.samples()).isEqualTo(0);
    }

    @Test
    @DisplayName("발동 멤버가 커버리지(3종목) 미만이면 발동률엔 잡히되 표본은 되지 않는다")
    void fired_below_coverage_counts_fire_rate_but_not_samples() {
        givenCandles("KOSPI", flatRows(100.0, 15));
        givenCandles("100001", flatRows(110.0, 15));
        givenCandles("100002", flatRows(110.0, 15));
        // 100003 캔들 없음 → 적격 2, IMMEDIATE 발동 2 < 커버리지 3
        givenEvent();

        TriggerStat immediate = stat(compute(), Trigger.IMMEDIATE);

        assertThat(immediate.fireRate()).isEqualTo(1.0); // 적격 멤버는 전부 발동
        assertThat(immediate.samples()).isEqualTo(0);    // 횡단면 커버리지 미달 — 표본 아님
    }

    @Test
    @DisplayName("지수 캔들이 없으면 트리거 실험을 하지 않는다 (초과수익 기반)")
    void no_benchmark_no_stats() {
        for (String member : CHAIN) givenCandles(member, flatRows(110.0, 15));
        givenEvent();

        assertThat(compute()).isEmpty();
    }
}
