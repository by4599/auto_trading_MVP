package com.trading.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AtrCalculator — ATR(14) 단순평균")
class AtrCalculatorTest {

    private final AtrCalculator sut = new AtrCalculator();

    /** 매일 같은 레인지(고가-저가)로 움직이고 갭 없는 캔들 시리즈 */
    private static List<Candle> flatSeries(int count, double range) {
        List<Candle> candles = new ArrayList<>();
        double mid = 100_000;
        for (int i = 0; i < count; i++) {
            candles.add(new Candle(LocalDate.now().minusDays(count - i),
                    mid, mid + range / 2, mid - range / 2, mid, 1000));
        }
        return candles;
    }

    @Test
    @DisplayName("갭 없는 일정 레인지 15개 → ATR = 레인지")
    void constant_range_atr_equals_range() {
        OptionalDouble atr = sut.atr(flatSeries(15, 2_000));

        assertThat(atr).isPresent();
        assertThat(atr.getAsDouble()).isEqualTo(2_000.0);
    }

    @Test
    @DisplayName("갭 상승 캔들의 TR은 |고가-전일종가| 채택")
    void gap_uses_true_range() {
        List<Candle> candles = new ArrayList<>(flatSeries(15, 2_000));
        // 마지막 캔들을 갭 상승으로 교체: 전일 종가 100,000 → 고가 105,000/저가 104,000
        // TR = max(1000, |105000-100000|=5000, |104000-100000|=4000) = 5000
        candles.set(14, new Candle(LocalDate.now(), 104_500, 105_000, 104_000, 104_500, 1000));

        OptionalDouble atr = sut.atr(candles);

        // 13개 TR=2000 + 1개 TR=5000 → (13*2000 + 5000) / 14
        assertThat(atr.getAsDouble()).isEqualTo((13 * 2_000.0 + 5_000.0) / 14);
    }

    @Test
    @DisplayName("15개 초과 캔들 → 최근 15개만 사용")
    void uses_only_recent_window() {
        List<Candle> candles = new ArrayList<>(flatSeries(10, 9_999)); // 오래된 노이즈
        candles.addAll(flatSeries(15, 2_000));                          // 최근 15개

        assertThat(sut.atr(candles).getAsDouble()).isEqualTo(2_000.0);
    }

    @Test
    @DisplayName("캔들 14개 이하 → empty")
    void insufficient_data_returns_empty() {
        assertThat(sut.atr(flatSeries(14, 2_000))).isEmpty();
        assertThat(sut.atr(List.of())).isEmpty();
        assertThat(sut.atr(null)).isEmpty();
    }

    @Test
    @DisplayName("전부 0인 캔들(파싱 실패) → empty")
    void zero_candles_return_empty() {
        List<Candle> zeros = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            zeros.add(new Candle(LocalDate.now().minusDays(15 - i), 0, 0, 0, 0, 0));
        }
        assertThat(sut.atr(zeros)).isEmpty();
    }
}
