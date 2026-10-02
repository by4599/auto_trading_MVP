package com.trading.backtest;

import com.trading.risk.IndexTrendCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모의투자 지수 필터가 <b>백테스트에서 검증된 판정과 똑같이</b> 계산하는지 고정한다.
 *
 * <p>검증된 설정(BACKTEST-DESIGN §14.4·§15.7)의 지수 필터는
 * {@link BacktestIndexRegimeSource#belowTrend}로 채점됐다. 모의투자는 백테스트 코드를 건드리지 않으려고
 * 같은 판정을 {@link IndexTrendCalculator}에 따로 두었으므로, 둘이 갈라지면 "검증된 필터"라는
 * 말이 거짓이 된다. 무작위 지수 경로 수천 개 지점에서 두 함수의 답이 전부 같아야 한다.
 * (백테스트 쪽 함수가 패키지 전용이라 이 테스트만 backtest 패키지에 둔다 — 운영 코드 무변경.)
 */
@DisplayName("지수 추세 판정 — 모의투자 계산 == 백테스트 계산 (parity)")
class IndexTrendParityTest {

    @Test
    @DisplayName("무작위 지수 경로 × 판정일 × MA 기간 전 조합에서 두 판정이 일치한다")
    void paper_calculator_matches_backtest_judgment() {
        Random random = new Random(20261001L);
        NavigableMap<LocalDate, Double> closes = new TreeMap<>();
        LocalDate start = LocalDate.of(2025, 1, 2);
        double level = 2_500;
        for (int i = 0; i < 400; i++) {
            level *= 1 + (random.nextGaussian() * 0.012);
            closes.put(start.plusDays(i), Math.round(level * 100) / 100.0);
        }

        int compared = 0;
        int belowCount = 0;
        for (int period : new int[] {1, 5, 20, 96, 120, 144, 200}) {
            for (int offset = 0; offset <= 402; offset++) {
                LocalDate simDate = start.plusDays(offset);
                var expected = BacktestIndexRegimeSource.belowTrend(closes, simDate, period);
                var actual = IndexTrendCalculator.belowTrend(closes, simDate, period);
                assertThat(actual).as("period=%d simDate=%s", period, simDate).isEqualTo(expected);
                compared++;
                if (expected.orElse(false)) belowCount++;
            }
        }
        // 무작위 경로가 한쪽 판정만 만들면 대조가 무의미하다 — 양쪽이 충분히 섞였는지 확인
        assertThat(compared).isGreaterThan(2_000);
        assertThat(belowCount).isBetween(compared / 10, compared * 9 / 10);
    }

    @Test
    @DisplayName("경계(전일 종가 == 이동평균)와 표본 부족에서도 일치한다")
    void boundaries_match() {
        NavigableMap<LocalDate, Double> flat = new TreeMap<>();
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 10; i++) flat.put(start.plusDays(i), 100.0);

        for (int period = 1; period <= 12; period++) {
            for (int offset = 0; offset <= 11; offset++) {
                LocalDate simDate = start.plusDays(offset);
                assertThat(IndexTrendCalculator.belowTrend(flat, simDate, period))
                        .isEqualTo(BacktestIndexRegimeSource.belowTrend(flat, simDate, period));
            }
        }
    }
}
