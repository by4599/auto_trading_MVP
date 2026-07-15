package com.trading.dashboard;

import com.trading.position.TradeResult;
import com.trading.position.TradeResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerformanceServiceTest {

    private TradeResultRepository repository;
    private PerformanceService sut;

    @BeforeEach
    void setUp() {
        repository = mock(TradeResultRepository.class);
        sut = new PerformanceService(repository);
    }

    private static TradeResult result(String code, double buyAvg, double sell, int qty, LocalDate date) {
        TradeResult r = TradeResult.live(code, qty, buyAvg, sell);
        ReflectionTestUtils.setField(r, "tradeDate", date);
        ReflectionTestUtils.setField(r, "soldAt", date.atTime(15, 0));
        return r;
    }

    @Test
    void daily_buckets_accumulate_cumulative_pnl() {
        LocalDate d1 = LocalDate.of(2026, 7, 1);
        LocalDate d2 = LocalDate.of(2026, 7, 2);
        when(repository.findByTradeDateBetweenOrderBySoldAtAsc(any(), any())).thenReturn(List.of(
                result("A", 100, 110, 10, d1),   // +100
                result("B", 100,  95, 10, d1),   //  -50
                result("A", 100, 120, 10, d2))); // +200

        Map<String, Object> out = sut.performance("daily", 30);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) out.get("buckets");
        assertThat(buckets).hasSize(2);
        assertThat(buckets.get(0).get("label")).isEqualTo("2026-07-01");
        assertThat(buckets.get(0).get("pnl")).isEqualTo(50L);
        assertThat(buckets.get(0).get("cumPnl")).isEqualTo(50L);
        assertThat(buckets.get(1).get("cumPnl")).isEqualTo(250L);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(summary.get("totalPnl")).isEqualTo(250L);
        assertThat(summary.get("tradeCount")).isEqualTo(3);
        assertThat(summary.get("winCount")).isEqualTo(2L);
        assertThat((Double) summary.get("winRate")).isEqualTo(66.7);
    }

    @Test
    void weekly_and_monthly_labels_group_correctly() {
        when(repository.findByTradeDateBetweenOrderBySoldAtAsc(any(), any())).thenReturn(List.of(
                result("A", 100, 110, 1, LocalDate.of(2026, 6, 30)),  // 2026-W27 / 2026-06
                result("A", 100, 110, 1, LocalDate.of(2026, 7, 1)),   // 2026-W27 / 2026-07
                result("A", 100, 110, 1, LocalDate.of(2026, 7, 6)))); // 2026-W28 / 2026-07

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> weekly =
                (List<Map<String, Object>>) sut.performance("weekly", 90).get("buckets");
        assertThat(weekly).extracting(b -> b.get("label"))
                .containsExactly("2026-W27", "2026-W28");
        assertThat(weekly.get(0).get("trades")).isEqualTo(2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> monthly =
                (List<Map<String, Object>>) sut.performance("monthly", 90).get("buckets");
        assertThat(monthly).extracting(b -> b.get("label"))
                .containsExactly("2026-06", "2026-07");
    }

    @Test
    void empty_results_produce_null_win_rate_and_no_buckets() {
        when(repository.findByTradeDateBetweenOrderBySoldAtAsc(any(), any())).thenReturn(List.of());

        Map<String, Object> out = sut.performance("daily", 30);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(summary.get("winRate")).isNull();
        assertThat((List<?>) out.get("buckets")).isEmpty();
    }
}
