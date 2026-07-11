package com.trading.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WalkForwardEngine — 6/3/3 윈도우 분할 수학")
class WalkForwardEngineTest {

    @Test
    @DisplayName("3년 → 학습 6개월/검증 3개월/3개월 롤링 = 검증 윈도우 10개")
    void threeYears_yieldsTenWindows() {
        LocalDate from = LocalDate.of(2023, 7, 10);
        LocalDate to   = LocalDate.of(2026, 7, 9);

        List<WalkForwardEngine.Window> windows = WalkForwardEngine.windows(from, to);

        assertThat(windows).hasSize(10);
        // 첫 윈도우: 학습 2023-07-10~2024-01-09, 검증 2024-01-10~2024-04-09
        WalkForwardEngine.Window first = windows.get(0);
        assertThat(first.trainFrom()).isEqualTo(from);
        assertThat(first.trainTo()).isEqualTo(LocalDate.of(2024, 1, 9));
        assertThat(first.validateFrom()).isEqualTo(LocalDate.of(2024, 1, 10));
        assertThat(first.validateTo()).isEqualTo(LocalDate.of(2024, 4, 9));
    }

    @Test
    @DisplayName("윈도우는 3개월씩 밀리고 검증 구간이 종료일을 넘으면 버린다")
    void windowsRollByThreeMonths_andClipAtEnd() {
        LocalDate from = LocalDate.of(2023, 7, 10);
        LocalDate to   = LocalDate.of(2026, 7, 9);

        List<WalkForwardEngine.Window> windows = WalkForwardEngine.windows(from, to);

        assertThat(windows.get(1).trainFrom()).isEqualTo(from.plusMonths(3));
        WalkForwardEngine.Window last = windows.get(windows.size() - 1);
        assertThat(last.validateTo()).isBeforeOrEqualTo(to);
        // 다음 윈도우가 있었다면 to를 넘는다 — 클리핑 확인
        assertThat(last.validateTo().plusMonths(3)).isAfter(to);
    }

    @Test
    @DisplayName("기간이 9개월 미만이면 윈도우가 없다")
    void tooShortPeriod_yieldsNoWindows() {
        assertThat(WalkForwardEngine.windows(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 31))).isEmpty();
    }
}
