package com.trading.dashboard;

import com.trading.market.CandleHistoryRepository;
import com.trading.order.OrderHistory;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 성적 조회가 요청마다 전량 스캔하지 않는가 (감사 24_audit M-3).
 *
 * <p>화면은 이 API 3종({@code /trades}·{@code /by-stock}·{@code /by-bucket})을 <b>60초마다</b>
 * 폴링한다. 캐시가 없으면 폴링 한 바퀴마다 체결 주문 전량 + 분봉을 세 번 다시 훑는다.
 * 거래는 하루 몇 건 늘어날 뿐이라 30초쯤 낡아도 무해하다.
 *
 * <p>시계는 테스트가 직접 굴린다 — TTL이 지났는지를 진짜 시간에 기대지 않는다.
 */
@DisplayName("TradeStatsService — 조회 캐시(30초)")
class TradeStatsCacheTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);

    /** 테스트가 손으로 굴리는 시계 */
    private static final class MovableClock extends Clock {
        private Instant now = TODAY.atTime(20, 0).atZone(KST).toInstant();
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return KST; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final OrderHistoryRepository orderRepository = mock(OrderHistoryRepository.class);
    private final CandleHistoryRepository candleRepository = mock(CandleHistoryRepository.class);
    private final MovableClock clock = new MovableClock();

    private final TradeStatsService sut = new TradeStatsService(
            orderRepository, new SellPriceEstimator(candleRepository), clock);

    private void givenOneBuy() {
        OrderHistory buy = OrderHistory.accepted("005930", OrderSide.BUY, 10, "ORD-1", null);
        ReflectionTestUtils.setField(buy, "id", 1L);
        ReflectionTestUtils.setField(buy, "status", OrderStatus.FILLED);
        ReflectionTestUtils.setField(buy, "requestedAt", LocalDateTime.of(2026, 9, 20, 10, 0));
        ReflectionTestUtils.setField(buy, "filledAt", LocalDateTime.of(2026, 9, 20, 10, 1));
        ReflectionTestUtils.setField(buy, "filledQuantity", 10);
        ReflectionTestUtils.setField(buy, "filledPrice", 70_000.0);

        when(orderRepository.findByFilledQuantityGreaterThan(anyInt())).thenReturn(List.of(buy));
        when(candleRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        anyString(), any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("같은 기간을 연달아 물으면 주문 원장을 한 번만 읽는다 (화면 폴링 3종 = 1회 스캔)")
    void repeated_calls_within_the_ttl_scan_once() {
        givenOneBuy();

        sut.summary(30);
        sut.byStock(30);
        sut.byBucket(30);

        verify(orderRepository, times(1)).findByFilledQuantityGreaterThan(anyInt());
    }

    @Test
    @DisplayName("30초가 지나면 다시 읽는다 — 새 거래가 영원히 안 보이면 안 된다")
    void a_new_scan_happens_after_the_ttl() {
        givenOneBuy();

        sut.summary(30);
        clock.advanceSeconds(31);
        sut.summary(30);

        verify(orderRepository, times(2)).findByFilledQuantityGreaterThan(anyInt());
    }

    @Test
    @DisplayName("기간이 다르면 따로 계산한다 — 30일 캐시를 7일 요청에 돌려주지 않는다")
    void different_periods_do_not_share_a_cache_entry() {
        givenOneBuy();

        sut.summary(30);
        sut.summary(7);

        verify(orderRepository, times(2)).findByFilledQuantityGreaterThan(anyInt());
        assertThat(sut.summary(7).get("days")).isEqualTo(7);
        assertThat(sut.summary(30).get("days")).isEqualTo(30);
    }

    @Test
    @DisplayName("캐시를 써도 답은 같다")
    void the_cached_answer_is_identical() {
        givenOneBuy();

        assertThat(sut.summary(30)).isEqualTo(sut.summary(30));
    }
}
