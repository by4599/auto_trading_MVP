package com.trading.dashboard;

import com.trading.bucket.StrategyBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대시보드 성적의 칸 우선 짝짓기 (46_audit M-5) — 같은 종목은 한 번에 한 칸만 들고 있으므로 매도는
 * 직전 매수의 칸 조각부터 쓴다. 주문 없이 0주가 된 옛 매수가 다른 칸 매도와 짝지어지던 문제를 고정한다.
 * {@code TradeStatsServiceTest}는 이미 300줄을 넘어 이 파일로 따로 둔다. 날짜: 2026-09-22 화(시스템 도구 확인).
 */
@DisplayName("TradeStatsService — 칸 우선 짝짓기 (주인 없는 옛 매수 조각)")
class TradeStatsCrossBucketTest {

    private final SleeveAdapterFixture f = new SleeveAdapterFixture(LocalDate.of(2026, 9, 22).atTime(20, 0));

    private TradeStatsService sut() {
        return new TradeStatsService(f.orderRepository, new SellPriceEstimator(f.candleRepository), f.clock);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buckets(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("buckets");
    }

    @Test
    @DisplayName("주문 없이 0주가 된 옛 매수(다른 칸)는 나중 매도와 짝짓지 않는다 — 직전 매수의 칸 조각부터")
    void orphan_lot_of_another_bucket_is_not_paired() {
        f.buy("066570", "2026-09-01T10:00", 5, 100_000, StrategyBucket.VB);   // 매도 기록 없이 사라진 옛 매수
        f.buy("066570", "2026-09-10T10:00", 5, 80_000, StrategyBucket.MIX);
        f.unpricedSell("066570", "2026-09-11T10:00", 5);
        f.close("066570", "2026-09-11", 84_000);

        Map<String, Object> result = sut().byBucket(30);

        assertThat(buckets(result)).singleElement().satisfies(row -> {
            assertThat(row.get("bucket")).isEqualTo("MIX");
            assertThat(row.get("totalPnl")).isEqualTo(20_000L);   // 옛 선입선출이면 VB −80,000
        });
        assertThat(result.get("crossBucketPieces")).isEqualTo(0);
    }

    @Test
    @DisplayName("직전 칸 조각이 모자라 다른 칸 조각까지 쓰면 거래는 나뉘고 '칸 넘김' 건수로 알린다")
    void overflow_into_another_bucket_is_flagged() {
        f.buy("066570", "2026-09-01T10:00", 5, 100_000, StrategyBucket.VB);
        f.buy("066570", "2026-09-10T10:00", 3, 80_000, StrategyBucket.MIX);
        f.unpricedSell("066570", "2026-09-11T10:00", 5);
        f.close("066570", "2026-09-11", 84_000);

        Map<String, Object> result = sut().summary(30);

        assertThat(result.get("totalTrades")).isEqualTo(2);        // MIX 3주 + (칸 넘김) VB 2주
        assertThat(result.get("crossBucketPieces")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 칸이 사고팔기를 반복하는 평범한 경우는 예전과 같다 — 칸 넘김 0건")
    void ordinary_round_trips_are_unchanged() {
        f.buy("005930", "2026-09-01T10:00", 2, 100_000, StrategyBucket.VB);
        f.buy("005930", "2026-09-02T10:00", 3, 110_000, StrategyBucket.VB);
        f.unpricedSell("005930", "2026-09-03T10:00", 5);
        f.close("005930", "2026-09-03", 120_000);

        Map<String, Object> result = sut().summary(30);

        assertThat(result.get("totalTrades")).isEqualTo(2);
        assertThat(result.get("totalPnl")).isEqualTo(70_000L);      // (120−100)×2 + (120−110)×3 천원
        assertThat(result.get("crossBucketPieces")).isEqualTo(0);
    }
}
