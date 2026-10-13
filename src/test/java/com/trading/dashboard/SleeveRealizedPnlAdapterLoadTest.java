package com.trading.dashboard;

import com.trading.bucket.StrategyBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 칸 실현손익 어댑터의 부하 — 1분 감시가 손절·강제청산과 같은 스레드(scheduling-1)에서 부르므로 가벼워야 한다
 * (46_audit M-2). 약속: ① 시간이 지났다는 이유만으로 다시 계산하지 않는다(주문·기록·날짜가 바뀔 때만)
 * ② 분봉은 요청 기간 안의 매도만, 한 매도당 하루 한 번 ③ 같은 회차(수 초 안)의 DB 조회는 한 번
 * ④ 체결 주문은 상태 인덱스로 읽는다(실패 주문 수만 건 전체 훑기 금지).
 * 날짜 사실관계(시스템 도구 확인): 2026-10-14 수.
 */
@DisplayName("SleeveRealizedPnlAdapter — 감시 스레드 부하 (캐시·조회 횟수)")
class SleeveRealizedPnlAdapterLoadTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 15);
    private static final Duration NEXT_ROUND = Duration.ofSeconds(61);

    private final SleeveAdapterFixture f = new SleeveAdapterFixture(LocalDate.of(2026, 10, 14).atTime(10, 0));

    private void givenOneEstimatedTrendSale() {
        f.buy("000660", "2026-10-06T10:00", 5, 100_000, StrategyBucket.TREND);
        f.unpricedSell("000660", "2026-10-08T10:00", 5);
        f.close("000660", "2026-10-08", 96_000);
    }

    private void verifyNoCandleRead() {
        verify(f.candleRepository, never())
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("체결 주문은 상태 인덱스로 읽는다 — 인덱스 없는 '체결 수량 > 0' 전체 훑기를 쓰지 않는다")
    void reads_filled_orders_through_the_status_index() {
        givenOneEstimatedTrendSale();

        f.adapter().realized(StrategyBucket.TREND, FROM, TO);

        verify(f.orderRepository).findByStatusIn(anyList());
        verify(f.orderRepository, never()).findByFilledQuantityGreaterThan(anyInt());
    }

    @Test
    @DisplayName("주문·기록이 그대로면 몇 시간이 지나도 분봉을 다시 읽지 않는다 (10분 만료 없음)")
    void no_time_based_recompute() {
        givenOneEstimatedTrendSale();
        SleeveRealizedPnlAdapter adapter = f.adapter();
        adapter.realized(StrategyBucket.TREND, FROM, TO);
        clearInvocations(f.candleRepository);

        for (int round = 0; round < 120; round++) {    // 1분 회차 두 시간
            f.advance(NEXT_ROUND);
            assertThat(adapter.realized(StrategyBucket.TREND, FROM, TO).pnl()).isEqualTo(-20_000);
        }

        verifyNoCandleRead();
    }

    @Test
    @DisplayName("같은 회차(수 초 안)에 칸마다 불러도 DB 조회는 한 번, 다음 회차엔 다시 읽는다")
    void one_database_read_per_round() {
        givenOneEstimatedTrendSale();
        SleeveRealizedPnlAdapter adapter = f.adapter();

        adapter.realized(StrategyBucket.TREND, FROM, TO);
        adapter.realized(StrategyBucket.VB, FROM, TO);
        adapter.realized(StrategyBucket.TREND, LocalDate.of(2026, 7, 20), LocalDate.of(2026, 9, 14));
        verify(f.orderRepository, times(1)).findByStatusIn(anyList());

        f.advance(NEXT_ROUND);
        adapter.realized(StrategyBucket.TREND, FROM, TO);
        verify(f.orderRepository, times(2)).findByStatusIn(anyList());
    }

    @Test
    @DisplayName("분봉은 요청 기간 안의 매도만 읽는다 — 굳힌 기간(30일 넘은) 매도는 다시 추정하지 않는다")
    void estimates_only_sales_inside_the_window() {
        f.buy("005930", "2026-08-03T10:00", 10, 70_000, StrategyBucket.VB);
        f.unpricedSell("005930", "2026-08-05T10:00", 10);                  // 기간 밖
        f.close("005930", "2026-08-05", 72_000);
        givenOneEstimatedTrendSale();                                        // 기간 안

        f.adapter().realized(StrategyBucket.TREND, FROM, TO);
        f.adapter().realized(StrategyBucket.VB, FROM, TO);

        verify(f.candleRepository, never())
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        eq("005930"), any(), any(), any());
    }

    @Test
    @DisplayName("새 체결이 생기면 짝짓기를 다시 하되, 분봉은 새 매도 것만 읽는다")
    void new_fill_reads_candles_only_for_the_new_sale() {
        givenOneEstimatedTrendSale();
        SleeveRealizedPnlAdapter adapter = f.adapter();
        adapter.realized(StrategyBucket.TREND, FROM, TO);
        clearInvocations(f.candleRepository);

        f.buy("005930", "2026-10-09T10:00", 1, 70_000, StrategyBucket.TREND);
        f.unpricedSell("005930", "2026-10-12T10:00", 1);
        f.close("005930", "2026-10-12", 72_000);
        f.advance(NEXT_ROUND);

        assertThat(adapter.realized(StrategyBucket.TREND, FROM, TO).pnl()).isEqualTo(-20_000 + 2_000);
        verify(f.candleRepository, atLeastOnce())
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        eq("005930"), any(), any(), any());
        verify(f.candleRepository, never())
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        eq("000660"), any(), any(), any());
    }

    @Test
    @DisplayName("오늘 판 매도의 '측정 불가'는 기억하지 않는다 — 장 마감 뒤 분봉이 쌓이면 다음 회차에 바로 잡힌다")
    void todays_unmeasurable_sale_is_retried() {
        f.buy("000660", "2026-10-13T10:00", 5, 100_000, StrategyBucket.TREND);
        f.unpricedSell("000660", "2026-10-14T09:30", 5);   // 오늘 — 아직 분봉 없음
        SleeveRealizedPnlAdapter adapter = f.adapter();
        assertThat(adapter.realized(StrategyBucket.TREND, FROM, TO).unmeasurableCount()).isEqualTo(1);

        f.close("000660", "2026-10-14", 96_000);           // 15:40 수집
        f.advance(Duration.ofSeconds(6));

        assertThat(adapter.realized(StrategyBucket.TREND, FROM, TO).pnl()).isEqualTo(-20_000);
    }

    @Test
    @DisplayName("최신 값이 꼭 필요할 때(사람의 해제) 요청하면 회차 재사용 없이 DB를 다시 읽는다 (46b N-2)")
    void refresh_request_skips_round_reuse() {
        givenOneEstimatedTrendSale();
        SleeveRealizedPnlAdapter adapter = f.adapter();
        adapter.realized(StrategyBucket.TREND, FROM, TO);

        f.buy("005930", "2026-10-09T10:00", 1, 70_000, StrategyBucket.TREND);   // 같은 회차 안에 반영된 체결
        f.unpricedSell("005930", "2026-10-12T10:00", 1);
        f.close("005930", "2026-10-12", 72_000);
        adapter.refreshOnNextCall();

        assertThat(adapter.realized(StrategyBucket.TREND, FROM, TO).pnl()).isEqualTo(-20_000 + 2_000);
        verify(f.orderRepository, times(2)).findByStatusIn(anyList());
    }

    @Test
    @DisplayName("시계가 뒤로 가면(경과 시간이 음수) 재사용하지 않고 다시 읽는다 (46b N-2)")
    void clock_going_backwards_reloads() {
        givenOneEstimatedTrendSale();
        SleeveRealizedPnlAdapter adapter = f.adapter();
        adapter.realized(StrategyBucket.TREND, FROM, TO);

        f.advance(Duration.ofSeconds(-30));
        adapter.realized(StrategyBucket.TREND, FROM, TO);

        verify(f.orderRepository, times(2)).findByStatusIn(anyList());
    }
}
