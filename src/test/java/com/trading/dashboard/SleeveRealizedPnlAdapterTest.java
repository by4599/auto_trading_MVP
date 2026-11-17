package com.trading.dashboard;

import com.trading.bucket.SleeveRealized;
import com.trading.bucket.StrategyBucket;
import com.trading.order.OrderHistory;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import com.trading.position.TradeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 칸 실현손익 원천 — TradeResult가 있으면 그 값, 없으면 대시보드 매도가 추정기(분봉 → 일봉 → 측정 불가).
 *
 * <p>모의투자는 매도 체결가를 주지 않아(CLAUDE.md 결함 5) 대부분의 매도에 TradeResult가 없다.
 * 리포지토리(인터페이스)만 목, 추정기·짝짓기는 실객체. 부하(캐시·조회 횟수)는 {@code SleeveRealizedPnlAdapterLoadTest}.
 */
@DisplayName("SleeveRealizedPnlAdapter — 칸 실현손익 (기록값 우선, 없으면 추정)")
class SleeveRealizedPnlAdapterTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 15);

    private final SleeveAdapterFixture f = new SleeveAdapterFixture(TO.atTime(10, 0));

    private SleeveRealized realized(StrategyBucket bucket) {
        return f.adapter().realized(bucket, FROM, TO);
    }

    @Test
    @DisplayName("TradeResult가 있는 매도는 그 값을 쓰고, 추정으로 한 번 더 세지 않는다")
    void recorded_trade_result_wins_and_is_not_double_counted() {
        f.buy("005930", "2026-10-06T10:00", 10, 70_000, StrategyBucket.TREND);
        f.recordedSell("005930", "2026-10-08T10:00", 10, 70_000, 73_000, StrategyBucket.TREND);
        f.close("005930", "2026-10-08", 99_999);   // 추정이 끼어들면 숫자가 틀어진다

        SleeveRealized r = realized(StrategyBucket.TREND);

        assertThat(r.pnl()).isEqualTo(30_000);
        assertThat(r.recordedCount()).isEqualTo(1);
        assertThat(r.estimatedCount()).isZero();
    }

    @Test
    @DisplayName("TradeResult가 없는 매도는 그날 분봉 종가로 추정한다 (칸은 매수 쪽 이름표)")
    void unrecorded_sale_is_estimated_from_minute_close() {
        f.buy("000660", "2026-10-06T10:00", 5, 100_000, StrategyBucket.TREND);
        f.unpricedSell("000660", "2026-10-08T10:00", 5);
        f.close("000660", "2026-10-08", 96_000);

        SleeveRealized r = realized(StrategyBucket.TREND);

        assertThat(r.pnl()).isEqualTo(-20_000);
        assertThat(r.estimatedCount()).isEqualTo(1);
        assertThat(realized(StrategyBucket.VB)).isEqualTo(SleeveRealized.none());
    }

    @Test
    @DisplayName("추정할 분봉이 없으면 측정 불가 — 0원으로 넣고 개수와 마지막 날짜를 보고한다")
    void unmeasurable_sale_counts_zero_and_reports_date() {
        f.buy("000660", "2026-10-06T10:00", 5, 100_000, StrategyBucket.TREND);
        f.unpricedSell("000660", "2026-10-08T10:00", 5);

        SleeveRealized r = realized(StrategyBucket.TREND);

        assertThat(r.pnl()).isZero();
        assertThat(r.unmeasurableCount()).isEqualTo(1);
        assertThat(r.latestUnmeasurableDate()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    @DisplayName("매도가 0원으로 박힌 TradeResult(2026-07-30 헛청산 같은 결함 기록)는 쓰지 않고 추정으로 대신한다")
    void zero_price_trade_result_is_ignored() {
        f.buy("035420", "2026-10-06T10:00", 2, 200_000, StrategyBucket.VB);
        f.unpricedSell("035420", "2026-10-08T10:00", 2);
        TradeResult broken = TradeResult.live("035420", 2, 200_000, 0, StrategyBucket.VB);
        ReflectionTestUtils.setField(broken, "soldAt", LocalDateTime.parse("2026-10-08T10:01"));
        ReflectionTestUtils.setField(broken, "tradeDate", LocalDate.of(2026, 10, 8));
        f.tradeResults.add(broken);
        f.close("035420", "2026-10-08", 205_000);

        SleeveRealized r = realized(StrategyBucket.VB);

        assertThat(r.pnl()).isEqualTo(10_000);   // 전액 손실(-400,000)이 아니다
        assertThat(r.recordedCount()).isZero();
    }

    @Test
    @DisplayName("기간은 매도 날짜 기준 [시작, 끝) — 기간 앞의 매수와도 정상적으로 짝짓는다")
    void window_is_by_sale_date() {
        f.buy("005930", "2026-09-20T10:00", 10, 70_000, StrategyBucket.TREND);   // 기간 앞 매수
        f.unpricedSell("005930", "2026-10-02T10:00", 10);
        f.close("005930", "2026-10-02", 71_000);
        f.buy("000660", "2026-10-14T10:00", 1, 100_000, StrategyBucket.TREND);
        f.unpricedSell("000660", "2026-10-15T10:00", 1);                         // 끝 날짜는 제외
        f.close("000660", "2026-10-15", 150_000);
        SleeveRealizedPnlAdapter adapter = f.adapter();

        assertThat(adapter.realized(StrategyBucket.TREND, FROM, TO).pnl()).isEqualTo(10_000);
        assertThat(adapter.realized(StrategyBucket.TREND, LocalDate.of(2026, 9, 1), FROM))
                .isEqualTo(SleeveRealized.none());
    }

    @Test
    @DisplayName("짝지을 매수가 없는 매도는 측정 불가로 세고 이름표 없는 칸(VB)에 귀속한다")
    void unmatched_sale_goes_to_vb_as_unmeasurable() {
        f.unpricedSell("068270", "2026-10-08T10:00", 3);

        assertThat(realized(StrategyBucket.VB).unmeasurableCount()).isEqualTo(1);
        assertThat(realized(StrategyBucket.TREND).unmeasurableCount()).isZero();
    }

    // ── 46_audit M-5: 주인 없는 옛 매수 조각 ───────────────────────────────────

    @Test
    @DisplayName("A동 매도는 주문 없이 0주가 된 옛 B동 매수와 짝짓지 않는다 — 직전 매수의 칸(A동) 조각부터 쓴다")
    void trend_sale_skips_an_orphan_b_sleeve_lot() {
        f.buy("066570", "2026-09-01T10:00", 5, 100_000, StrategyBucket.VB);    // 매도 기록 없이 사라진 옛 매수
        f.buy("066570", "2026-10-06T10:00", 5, 80_000, StrategyBucket.TREND);
        f.unpricedSell("066570", "2026-10-08T10:00", 5);
        f.close("066570", "2026-10-08", 84_000);

        SleeveRealized trend = realized(StrategyBucket.TREND);

        assertThat(trend.pnl()).isEqualTo(20_000);       // 옛 가격(10만)이면 -80,000이 VB로 갔다
        assertThat(trend.estimatedCount()).isEqualTo(1);
        assertThat(realized(StrategyBucket.VB)).isEqualTo(SleeveRealized.none());
    }

    @Test
    @DisplayName("체결분이 없는 취소 주문은 짝짓기에 끼지 않는다 — 직전 매수의 칸을 정하는 데도 쓰지 않는다")
    void cancelled_order_without_fill_is_ignored() {
        f.buy("000660", "2026-10-06T10:00", 5, 100_000, StrategyBucket.TREND);
        OrderHistory cancelled = f.filled("000660", OrderSide.BUY, "2026-10-07T10:00", 5, 99_000.0, StrategyBucket.VB);
        ReflectionTestUtils.setField(cancelled, "status", OrderStatus.CANCELLED);
        ReflectionTestUtils.setField(cancelled, "filledQuantity", 0);
        f.unpricedSell("000660", "2026-10-08T10:00", 5);
        f.close("000660", "2026-10-08", 96_000);

        SleeveRealized trend = realized(StrategyBucket.TREND);

        assertThat(trend.pnl()).isEqualTo(-20_000);
        assertThat(trend.estimatedCount()).isEqualTo(1);
        assertThat(realized(StrategyBucket.VB)).isEqualTo(SleeveRealized.none());
    }

    @Test
    @DisplayName("매수 체결가가 숫자가 아니면(NaN) 측정 불가로 뺀다 — 손익이 NaN이 돼 칸 계산이 멈추지 않게 (46b N-3)")
    void nan_buy_price_is_unmeasurable() {
        f.buy("000660", "2026-10-06T10:00", 5, Double.NaN, StrategyBucket.TREND);
        f.unpricedSell("000660", "2026-10-08T10:00", 5);
        f.close("000660", "2026-10-08", 96_000);

        SleeveRealized trend = realized(StrategyBucket.TREND);

        assertThat(trend.pnl()).isZero();
        assertThat(trend.unmeasurableCount()).isEqualTo(1);
    }
}
