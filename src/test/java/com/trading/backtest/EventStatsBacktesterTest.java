package com.trading.backtest;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 이벤트 유형별 반응 통계 (B-4) — 합성 캔들로 D+N 수익률·선견 편향 차단 검증.
 */
@DisplayName("EventStatsBacktester — 공시 유형별 D+N 반응 통계")
class EventStatsBacktesterTest {

    private static final LocalDate BASE = LocalDate.of(2026, 1, 5); // 월요일

    private DisclosureRepository disclosureRepository;
    private CandleHistoryRepository candleHistoryRepository;
    private EventStatsBacktester sut;

    @BeforeEach
    void setUp() {
        disclosureRepository = mock(DisclosureRepository.class);
        candleHistoryRepository = mock(CandleHistoryRepository.class);
        // KOSPI 캔들 미스텁 시 빈 리스트 → 원수익률 폴백 (기존 단순 시나리오 유지)
        sut = new EventStatsBacktester(disclosureRepository, candleHistoryRepository,
                new BacktestDataProperties());
    }

    /** BASE부터 연속 거래일 캔들 — 시가 100, 종가는 지정된 배열 */
    private void givenCandles(String stockCode, double open, double... closes) {
        List<CandleHistory> candles = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            candles.add(CandleHistory.ofDaily(stockCode, new Candle(
                    BASE.plusDays(i), open, Math.max(open, closes[i]) + 1,
                    Math.min(open, closes[i]) - 1, closes[i], 1000)));
        }
        when(candleHistoryRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        eq(stockCode), eq(Timeframe.DAILY), any(), any()))
                .thenReturn(candles);
    }

    private static DisclosureItem event(String stockCode, String type, LocalDate disclosedAt) {
        return DisclosureItem.of(stockCode, "테스트회사", "R" + disclosedAt + type + stockCode,
                "보고서", disclosedAt, "NEUTRAL", type);
    }

    @Test
    @DisplayName("진입은 공시일 다음 거래일 시가 — D+1 수익률 = 종가/시가 − 1")
    void entry_next_day_open() {
        // BASE~BASE+24 캔들 25개: 시가 100, 종가 전부 110
        double[] closes = new double[25];
        java.util.Arrays.fill(closes, 110.0);
        givenCandles("005930", 100.0, closes);
        // 공시일 = BASE → 진입 = BASE+1 캔들 시가(100)
        when(disclosureRepository.findAll())
                .thenReturn(List.of(event("005930", "SUPPLY_CONTRACT", BASE)));

        List<EventStatsBacktester.EventStat> stats =
                sut.compute(List.of("005930"), BASE.minusDays(10), BASE.plusDays(30));

        assertThat(stats).hasSize(1);
        EventStatsBacktester.EventStat s = stats.get(0);
        assertThat(s.eventType()).isEqualTo("SUPPLY_CONTRACT");
        assertThat(s.at(1).median()).isCloseTo(0.10, within(1e-9));  // 110/100 - 1
        assertThat(s.at(5).median()).isCloseTo(0.10, within(1e-9));
        assertThat(s.winRateD5()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("하락 반응 유형 → 음의 중앙값, 승률 0")
    void negative_reaction() {
        double[] closes = new double[25];
        java.util.Arrays.fill(closes, 90.0);
        givenCandles("005930", 100.0, closes);
        when(disclosureRepository.findAll())
                .thenReturn(List.of(event("005930", "PAID_INCREASE", BASE)));

        List<EventStatsBacktester.EventStat> stats =
                sut.compute(List.of("005930"), BASE.minusDays(10), BASE.plusDays(30));

        assertThat(stats.get(0).at(5).median()).isCloseTo(-0.10, within(1e-9));
        assertThat(stats.get(0).winRateD5()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("캔들 없는 종목의 공시 → 표본 제외 (통계 없음)")
    void skips_events_without_candles() {
        when(candleHistoryRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        any(), any(), any(), any()))
                .thenReturn(List.of());
        when(disclosureRepository.findAll())
                .thenReturn(List.of(event("005930", "SUPPLY_CONTRACT", BASE)));

        assertThat(sut.compute(List.of("005930"), BASE.minusDays(10), BASE.plusDays(30))).isEmpty();
    }

    @Test
    @DisplayName("기간 끝 근처 공시 → 짧은 horizon만 집계 (D+20 미달분 제외)")
    void partial_horizons_near_period_end() {
        double[] closes = new double[8];
        java.util.Arrays.fill(closes, 105.0);
        givenCandles("005930", 100.0, closes); // 캔들 8개 — D+5까지만 가능
        when(disclosureRepository.findAll())
                .thenReturn(List.of(event("005930", "DIVIDEND", BASE)));

        EventStatsBacktester.EventStat s =
                sut.compute(List.of("005930"), BASE.minusDays(10), BASE.plusDays(30)).get(0);

        assertThat(s.at(5).n()).isEqualTo(1);
        assertThat(s.at(20).n()).isEqualTo(0); // 표본 밖
    }

    @Test
    @DisplayName("중앙값과 p25 — 5개 표본 [−5%, 0%, +2%, +4%, +10%]")
    void median_and_p25() {
        // 5건의 공시, 각각 다른 날 진입 — 종가 시퀀스로 D+1 수익률을 개별 제어
        // 단순화: 5개 종목에 각 1건씩, D+1 수익률 -5/0/+2/+4/+10%
        double[][] returns = {{-0.05}, {0.0}, {0.02}, {0.04}, {0.10}};
        List<DisclosureItem> events = new ArrayList<>();
        List<String> symbols = new ArrayList<>();
        for (int i = 0; i < returns.length; i++) {
            String code = "10000" + i;
            symbols.add(code);
            double[] closes = new double[10];
            java.util.Arrays.fill(closes, 100.0 * (1 + returns[i][0]));
            givenCandles(code, 100.0, closes);
            events.add(event(code, "EARNINGS", BASE));
        }
        when(disclosureRepository.findAll()).thenReturn(events);

        EventStatsBacktester.EventStat s =
                sut.compute(symbols, BASE.minusDays(10), BASE.plusDays(30)).get(0);

        assertThat(s.at(1).n()).isEqualTo(5);
        assertThat(s.at(1).median()).isCloseTo(0.02, within(1e-9)); // 정렬 3번째 (ceil(0.5×5)=3)
        assertThat(s.at(1).p25()).isCloseTo(0.0, within(1e-9));     // 정렬 2번째 (ceil(0.25×5)=2)
    }

    // ── v2: 벤치마크 조정 + 클러스터 병합 ────────────────────────────────────

    @Test
    @DisplayName("KOSPI 캔들 존재 → 초과수익 = 종목 +10% − 지수 +4% = +6%")
    void benchmark_adjusted_excess_return() {
        double[] stockCloses = new double[25];
        java.util.Arrays.fill(stockCloses, 110.0);   // 종목 +10%
        givenCandles("005930", 100.0, stockCloses);
        double[] kospiCloses = new double[25];
        java.util.Arrays.fill(kospiCloses, 104.0);   // KOSPI +4% (시가 100)
        givenCandles("KOSPI", 100.0, kospiCloses);
        when(disclosureRepository.findAll())
                .thenReturn(List.of(event("005930", "SUPPLY_CONTRACT", BASE)));

        EventStatsBacktester.EventStat s =
                sut.compute(List.of("005930"), BASE.minusDays(10), BASE.plusDays(30)).get(0);

        assertThat(s.at(1).median()).isCloseTo(0.06, within(1e-9));
        assertThat(s.at(5).median()).isCloseTo(0.06, within(1e-9));
    }

    @Test
    @DisplayName("같은 종목·유형 5거래일 내 연속 이벤트 → 첫 건만 표본 (클러스터 병합)")
    void clusters_merged_within_window() {
        double[] closes = new double[30];
        java.util.Arrays.fill(closes, 105.0);
        givenCandles("005930", 100.0, closes);
        when(disclosureRepository.findAll()).thenReturn(List.of(
                event("005930", "INSIDER_OWNERSHIP", BASE),              // 진입 idx 1 — 채택
                event("005930", "INSIDER_OWNERSHIP", BASE.plusDays(2)),  // 진입 idx 3 — 병합 제외
                event("005930", "INSIDER_OWNERSHIP", BASE.plusDays(9))));// 진입 idx 10 — 채택

        EventStatsBacktester.EventStat s =
                sut.compute(List.of("005930"), BASE.minusDays(10), BASE.plusDays(40)).get(0);

        assertThat(s.at(1).n()).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 종목의 동시 이벤트는 병합하지 않는다")
    void different_stocks_not_merged() {
        double[] closes = new double[30];
        java.util.Arrays.fill(closes, 105.0);
        givenCandles("005930", 100.0, closes);
        givenCandles("000660", 100.0, closes);
        when(disclosureRepository.findAll()).thenReturn(List.of(
                event("005930", "DIVIDEND", BASE),
                event("000660", "DIVIDEND", BASE)));

        EventStatsBacktester.EventStat s =
                sut.compute(List.of("005930", "000660"), BASE.minusDays(10), BASE.plusDays(40)).get(0);

        assertThat(s.at(1).n()).isEqualTo(2);
    }
}
