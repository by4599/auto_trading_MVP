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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 테마 파급 통계 (B-4 확장) — 앵커 공시 → 체인 횡단면 중앙값 접기,
 * 커버리지 하한, 클러스터 병합, 시장별 벤치마크를 합성 캔들로 검증.
 */
@DisplayName("SpilloverStatsBacktester — 앵커 공시 → 밸류체인 파급 통계")
class SpilloverStatsBacktesterTest {

    private static final LocalDate BASE = LocalDate.of(2026, 1, 5); // 월요일
    private static final String ANCHOR = "900001";

    private DisclosureRepository disclosureRepository;
    private CandleHistoryRepository candleHistoryRepository;
    private BacktestDataProperties properties;
    private SpilloverStatsBacktester sut;

    @BeforeEach
    void setUp() {
        disclosureRepository = mock(DisclosureRepository.class);
        candleHistoryRepository = mock(CandleHistoryRepository.class);
        properties = new BacktestDataProperties();
        properties.setEventThemes(Map.of(
                "semi-anchor", List.of(ANCHOR),
                "semi-chain", List.of("100001", "100002", "100003")));
        properties.setKosdaqSymbols(List.of()); // 기본: 전 종목 KOSPI 벤치마크
        sut = new SpilloverStatsBacktester(disclosureRepository, candleHistoryRepository, properties);
    }

    /** BASE부터 연속 거래일 캔들 — 시가 100, 종가는 지정된 값으로 count개 */
    private void givenCandles(String stockCode, double close, int count) {
        List<CandleHistory> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            candles.add(CandleHistory.ofDaily(stockCode, new Candle(
                    BASE.plusDays(i), 100.0, Math.max(100.0, close) + 1,
                    Math.min(100.0, close) - 1, close, 1000)));
        }
        when(candleHistoryRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        eq(stockCode), eq(Timeframe.DAILY), any(), any()))
                .thenReturn(candles);
    }

    private static DisclosureItem anchorEvent(String type, LocalDate disclosedAt) {
        return DisclosureItem.of(ANCHOR, "앵커회사", "R" + disclosedAt + type,
                "보고서", disclosedAt, "POSITIVE", type);
    }

    private List<SpilloverStatsBacktester.SpilloverStat> compute() {
        return sut.compute(BASE.minusDays(10), BASE.plusDays(40));
    }

    @Test
    @DisplayName("앵커 공시 1건 → 체인 3종목 초과수익의 횡단면 중앙값이 1표본이 된다")
    void anchor_event_folds_chain_into_cross_sectional_median() {
        givenCandles("KOSPI", 101.0, 30);   // 지수 +1%
        givenCandles("100001", 110.0, 30);  // +10% → 초과 +9%
        givenCandles("100002", 103.0, 30);  // +3%  → 초과 +2%
        givenCandles("100003", 96.0, 30);   // −4%  → 초과 −5%
        // 앵커 자신의 캔들은 스텁하지 않는다 — 파급 통계는 앵커 주가를 쓰지 않음을 함께 검증
        when(disclosureRepository.findAll())
                .thenReturn(List.of(anchorEvent("SUPPLY_CONTRACT", BASE)));

        List<SpilloverStatsBacktester.SpilloverStat> stats = compute();

        assertThat(stats).hasSize(1);
        SpilloverStatsBacktester.SpilloverStat s = stats.get(0);
        assertThat(s.theme()).isEqualTo("semi");
        assertThat(s.eventType()).isEqualTo("SUPPLY_CONTRACT");
        assertThat(s.chainSize()).isEqualTo(3);
        assertThat(s.samples()).isEqualTo(1);                      // 이벤트 1건 = 표본 1
        assertThat(s.at(1).median()).isCloseTo(0.02, within(1e-9)); // 중앙값 = +2%
        assertThat(s.at(5).median()).isCloseTo(0.02, within(1e-9));
        assertThat(s.winRateD5()).isEqualTo(1.0);
        assertThat(s.registryKey()).isEqualTo("SPILL:semi:SUPPLY_CONTRACT");
    }

    @Test
    @DisplayName("체인 커버리지가 최소치(3종목) 미만인 이벤트는 표본에서 제외된다")
    void events_below_min_coverage_excluded() {
        givenCandles("KOSPI", 101.0, 30);
        givenCandles("100001", 110.0, 30);
        givenCandles("100002", 103.0, 30);
        // 100003 캔들 없음 → 커버리지 2 < 3
        when(disclosureRepository.findAll())
                .thenReturn(List.of(anchorEvent("SUPPLY_CONTRACT", BASE)));

        assertThat(compute()).isEmpty();
    }

    @Test
    @DisplayName("같은 앵커·유형의 5거래일 내 연속 공시는 첫 건만 채택된다 (지수 캘린더 기준)")
    void clusters_merged_on_index_calendar() {
        givenCandles("KOSPI", 101.0, 40);
        givenCandles("100001", 110.0, 40);
        givenCandles("100002", 103.0, 40);
        givenCandles("100003", 96.0, 40);
        when(disclosureRepository.findAll()).thenReturn(List.of(
                anchorEvent("DIVIDEND", BASE),              // 지수 진입 idx 1 — 채택
                anchorEvent("DIVIDEND", BASE.plusDays(2)),  // idx 3 — 병합 제외
                anchorEvent("DIVIDEND", BASE.plusDays(9))));// idx 10 — 채택

        assertThat(compute().get(0).samples()).isEqualTo(2);
    }

    @Test
    @DisplayName("KOSDAQ 소속 체인 종목은 KOSDAQ 지수 대비 초과수익으로 계산한다")
    void kosdaq_members_use_kosdaq_benchmark() {
        properties.setKosdaqSymbols(List.of("100001", "100002", "100003"));
        givenCandles("KOSPI", 101.0, 30);   // +1% (앵커 클러스터 캘린더용)
        givenCandles("KOSDAQ", 105.0, 30);  // +5%
        givenCandles("100001", 110.0, 30);  // +10% → KOSDAQ 대비 +5%
        givenCandles("100002", 108.0, 30);  // +8%  → +3%
        givenCandles("100003", 106.0, 30);  // +6%  → +1%
        when(disclosureRepository.findAll())
                .thenReturn(List.of(anchorEvent("SUPPLY_CONTRACT", BASE)));

        assertThat(compute().get(0).at(1).median()).isCloseTo(0.03, within(1e-9));
    }

    @Test
    @DisplayName("지수 캔들이 없으면 파급 통계를 내지 않는다 (원수익률 폴백 없음)")
    void no_benchmark_no_stats() {
        givenCandles("100001", 110.0, 30);
        givenCandles("100002", 103.0, 30);
        givenCandles("100003", 96.0, 30);
        when(disclosureRepository.findAll())
                .thenReturn(List.of(anchorEvent("SUPPLY_CONTRACT", BASE)));

        assertThat(compute()).isEmpty();
    }

    @Test
    @DisplayName("체인 종목 자신의 공시는 파급 표본이 아니다 (앵커 공시만 집계)")
    void chain_member_own_disclosures_ignored() {
        givenCandles("KOSPI", 101.0, 30);
        givenCandles("100001", 110.0, 30);
        givenCandles("100002", 103.0, 30);
        givenCandles("100003", 96.0, 30);
        when(disclosureRepository.findAll()).thenReturn(List.of(
                DisclosureItem.of("100001", "체인회사", "R1", "보고서",
                        BASE, "POSITIVE", "SUPPLY_CONTRACT")));

        assertThat(compute()).isEmpty();
    }

    @Test
    @DisplayName("anchor-chain 키 쌍이 없는 테마는 건너뛴다 (규약 미충족 시 무해)")
    void themes_without_chain_pair_skipped() {
        properties.setEventThemes(Map.of(
                "semi-anchor", List.of(ANCHOR),   // 체인 키 없음
                "battery", List.of("247540")));   // 앵커 키 아님
        givenCandles("KOSPI", 101.0, 30);
        when(disclosureRepository.findAll())
                .thenReturn(List.of(anchorEvent("SUPPLY_CONTRACT", BASE)));

        assertThat(compute()).isEmpty();
    }
}
