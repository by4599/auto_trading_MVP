package com.trading.dashboard;

import com.trading.bucket.StrategyBucket;
import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.MinuteCandle;
import com.trading.market.Timeframe;
import com.trading.order.OrderHistory;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 거래 기준 성적 — 매수(정확) + 매도(추정)로 낸 승률·손익비·종목별·칸별.
 *
 * <p>여기서 고정하는 약속:
 * <ul>
 *   <li>짝짓기는 <b>선입선출(FIFO)</b>이고, 매도 한 건이 매수 여러 건을 덮으면 거래도 나뉜다</li>
 *   <li>매도가를 추정하지 못한 건은 <b>집계에서 빠지고 {@code unmeasurable}로 센다</b>
 *       — 0원으로 계산해 손익을 부풀리지 않는다</li>
 *   <li>기간(days)은 <b>매도 시각</b> 기준이다 — 기간 앞의 매수가 잘려 "짝 없음"이 되지 않게
 *       짝짓기는 전 기간으로 한다</li>
 * </ul>
 *
 * <p>Java 25 Mockito 제약: 리포지토리(인터페이스)만 목, 서비스·추정기는 실객체로 조립한다.
 */
@DisplayName("TradeStatsService — 거래 기준 성적(추정)")
class TradeStatsServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);

    private final OrderHistoryRepository orderRepository = mock(OrderHistoryRepository.class);
    private final CandleHistoryRepository candleRepository = mock(CandleHistoryRepository.class);
    private final Clock clock = Clock.fixed(TODAY.atTime(20, 0).atZone(KST).toInstant(), KST);

    private final List<OrderHistory> orders = new ArrayList<>();
    private final Map<String, Double> minuteCloses = new HashMap<>();   // "종목|날짜" → 종가

    private long nextId = 1;

    private TradeStatsService sut() {
        when(orderRepository.findByFilledQuantityGreaterThan(anyInt())).thenReturn(List.copyOf(orders));
        when(candleRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String code = invocation.getArgument(0);
                    Timeframe timeframe = invocation.getArgument(1);
                    LocalDate date = invocation.getArgument(2);
                    if (timeframe != Timeframe.MINUTE) return List.of();
                    Double close = minuteCloses.get(code + "|" + date);
                    return close == null ? List.of()
                            : List.of(CandleHistory.ofMinute(code, new MinuteCandle(
                                    date, java.time.LocalTime.of(10, 0), close, close, close, close, 1)));
                });
        return new TradeStatsService(orderRepository, new SellPriceEstimator(candleRepository), clock);
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private void givenClose(String stockCode, String date, double close) {
        minuteCloses.put(stockCode + "|" + LocalDate.parse(date), close);
    }

    private void buy(String stockCode, String at, int qty, Double price, StrategyBucket bucket) {
        orders.add(filled(stockCode, OrderSide.BUY, at, qty, price, bucket));
    }

    private void sell(String stockCode, String at, int qty) {
        // 모의 매도 체결가는 0으로 온다 (CLAUDE.md 결함 5) — 그 상태 그대로 넣는다
        orders.add(filled(stockCode, OrderSide.SELL, at, qty, 0.0, null));
    }

    private OrderHistory filled(String stockCode, OrderSide side, String at, int qty,
                                Double price, StrategyBucket bucket) {
        LocalDateTime time = LocalDateTime.parse(at);
        OrderHistory o = OrderHistory.accepted(stockCode, side, qty, "ORD-" + nextId, bucket);
        ReflectionTestUtils.setField(o, "id", nextId++);
        ReflectionTestUtils.setField(o, "status", OrderStatus.FILLED);
        ReflectionTestUtils.setField(o, "requestedAt", time);
        ReflectionTestUtils.setField(o, "filledAt", time.plusMinutes(1));
        ReflectionTestUtils.setField(o, "filledQuantity", qty);
        ReflectionTestUtils.setField(o, "filledPrice", price);
        return o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }

    /** 이긴 거래 1건(+20,000)과 진 거래 1건(-10,000) */
    private void givenOneWinOneLoss() {
        buy("005930", "2026-09-01T10:00", 10, 70_000.0, StrategyBucket.VB);
        sell("005930", "2026-09-02T10:00", 10);
        givenClose("005930", "2026-09-02", 72_000);

        buy("000660", "2026-09-03T10:00", 5, 100_000.0, StrategyBucket.MIX);
        sell("000660", "2026-09-04T10:00", 5);
        givenClose("000660", "2026-09-04", 98_000);
    }

    // ── 요약 ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("요약")
    class Summary {

        @Test
        @DisplayName("승률·손익비·평균이익/손실을 추정 매도가로 계산한다")
        void computes_win_rate_and_payoff() {
            givenOneWinOneLoss();

            Map<String, Object> result = sut().summary(30);

            assertThat(result.get("totalTrades")).isEqualTo(2);
            assertThat(result.get("wins")).isEqualTo(1);
            assertThat(result.get("losses")).isEqualTo(1);
            assertThat(result.get("winRatePercent")).isEqualTo(50.0);
            assertThat(result.get("totalPnl")).isEqualTo(10_000L);      // +20,000 - 10,000
            assertThat(result.get("avgProfit")).isEqualTo(20_000L);
            assertThat(result.get("avgLoss")).isEqualTo(10_000L);
            assertThat(result.get("payoffRatio")).isEqualTo(2.0);
            assertThat(result.get("profitFactor")).isEqualTo(2.0);
        }

        @Test
        @DisplayName("추정임을 반드시 알린다 — estimated 플래그·경고 문구·짝짓기 규칙")
        void always_declares_that_it_is_an_estimate() {
            givenOneWinOneLoss();

            Map<String, Object> result = sut().summary(30);

            assertThat(result.get("estimated")).isEqualTo(true);
            assertThat((String) result.get("warning")).contains("추정치");
            assertThat((String) result.get("matchingRule")).contains("선입선출");
        }

        @Test
        @DisplayName("추정을 어디서 얻었는지(분봉/일봉) 건수를 준다")
        void reports_where_the_estimate_came_from() {
            givenOneWinOneLoss();

            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) sut().summary(30).get("sellSource");

            assertThat(source.get("minute")).isEqualTo(2L);
            assertThat(source.get("daily")).isEqualTo(0L);
        }

        @Test
        @DisplayName("거래가 하나도 없으면 0건으로 답하고 비율은 null (0%로 꾸미지 않는다)")
        void empty_history_is_not_dressed_up() {
            Map<String, Object> result = sut().summary(30);

            assertThat(result.get("totalTrades")).isEqualTo(0);
            assertThat(result.get("winRatePercent")).isNull();
            assertThat(result.get("payoffRatio")).isNull();
            assertThat(result.get("unmeasurable")).isEqualTo(0);
        }
    }

    // ── 짝짓기 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("선입선출 짝짓기")
    class Pairing {

        @Test
        @DisplayName("매도 한 건이 매수 두 건을 덮으면 거래 두 건이 된다 (오래된 매수부터)")
        void one_sell_over_two_buys_becomes_two_trades() {
            buy("005930", "2026-09-01T10:00", 2, 100_000.0, StrategyBucket.VB);
            buy("005930", "2026-09-02T10:00", 3, 110_000.0, StrategyBucket.VB);
            sell("005930", "2026-09-03T10:00", 5);
            givenClose("005930", "2026-09-03", 120_000);

            Map<String, Object> result = sut().summary(30);

            assertThat(result.get("totalTrades")).isEqualTo(2);
            // (120,000-100,000)×2 + (120,000-110,000)×3 = 40,000 + 30,000
            assertThat(result.get("totalPnl")).isEqualTo(70_000L);
        }

        @Test
        @DisplayName("아직 안 판 매수는 측정 불가가 아니다 — 그냥 열린 포지션이라 집계에 없다")
        void open_position_is_not_counted_as_unmeasurable() {
            buy("005930", "2026-09-01T10:00", 10, 70_000.0, StrategyBucket.VB);

            Map<String, Object> result = sut().summary(30);

            assertThat(result.get("totalTrades")).isEqualTo(0);
            assertThat(result.get("unmeasurable")).isEqualTo(0);
        }

        @Test
        @DisplayName("칸은 매수 쪽 것을 쓴다 — 매도 주문에는 칸이 붙지 않는다")
        void bucket_comes_from_the_buy_order() {
            givenOneWinOneLoss();

            List<Map<String, Object>> buckets = list(sut().byBucket(30), "buckets");

            assertThat(buckets).extracting(row -> row.get("bucket"))
                    .containsExactlyInAnyOrder("VB", "MIX");
        }
    }

    // ── 측정 불가 ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("측정 불가")
    class Unmeasurable {

        @Test
        @DisplayName("캔들이 없어 매도가를 못 구하면 집계에서 빼고 건수로 센다")
        void missing_candle_is_excluded_and_counted() {
            givenOneWinOneLoss();
            buy("035420", "2026-09-05T10:00", 3, 200_000.0, StrategyBucket.VB);
            sell("035420", "2026-09-06T10:00", 3);      // 그날 캔들을 주지 않는다

            Map<String, Object> result = sut().summary(30);

            assertThat(result.get("totalTrades")).isEqualTo(2);          // 멀쩡한 2건만
            assertThat(result.get("totalPnl")).isEqualTo(10_000L);       // 손익도 안 흔들린다
            assertThat(result.get("unmeasurable")).isEqualTo(1);
            assertThat(result.get("unmeasurableQuantity")).isEqualTo(3);
            assertThat(list(result, "unmeasurableReasons")).extracting(row -> row.get("reason"))
                    .contains(TradePairer.NO_SELL_PRICE);
        }

        @Test
        @DisplayName("짝지을 매수가 없는 매도도 측정 불가로 센다")
        void sell_without_a_matching_buy_is_counted() {
            sell("012330", "2026-09-06T10:00", 2);
            givenClose("012330", "2026-09-06", 250_000);

            Map<String, Object> result = sut().summary(30);

            assertThat(result.get("totalTrades")).isEqualTo(0);
            assertThat(result.get("unmeasurable")).isEqualTo(1);
            assertThat(list(result, "unmeasurableReasons")).extracting(row -> row.get("reason"))
                    .containsExactly(TradePairer.NO_MATCHING_BUY);
        }

        @Test
        @DisplayName("매수 체결가가 0원인 행(결함 잔재)도 측정 불가로 뺀다")
        void buy_without_a_price_is_counted() {
            buy("066570", "2026-09-05T10:00", 4, 0.0, StrategyBucket.VB);
            sell("066570", "2026-09-06T10:00", 4);
            givenClose("066570", "2026-09-06", 90_000);

            Map<String, Object> result = sut().summary(30);

            assertThat(result.get("totalTrades")).isEqualTo(0);
            assertThat(list(result, "unmeasurableReasons")).extracting(row -> row.get("reason"))
                    .containsExactly(TradePairer.NO_BUY_PRICE);
        }
    }

    // ── 기간 ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("기간 자르기")
    class Window {

        @Test
        @DisplayName("기간 밖에 팔린 거래는 빠진다 (매도 시각 기준)")
        void trades_sold_before_the_window_are_dropped() {
            givenOneWinOneLoss();   // 09-02, 09-04 매도

            // 오늘이 09-22이므로 days=7이면 09-16부터
            assertThat(sut().summary(7).get("totalTrades")).isEqualTo(0);
            assertThat(sut().summary(30).get("totalTrades")).isEqualTo(2);
        }

        @Test
        @DisplayName("기간 앞의 매수는 잘리지 않는다 — 짝짓기는 전 기간으로 한다")
        void a_buy_before_the_window_still_pairs() {
            buy("005930", "2026-08-01T10:00", 10, 70_000.0, StrategyBucket.VB);   // 기간 훨씬 이전
            sell("005930", "2026-09-20T10:00", 10);                                // 기간 안
            givenClose("005930", "2026-09-20", 72_000);

            Map<String, Object> result = sut().summary(7);

            assertThat(result.get("totalTrades")).isEqualTo(1);
            assertThat(result.get("unmeasurable")).isEqualTo(0);   // "짝 없음"으로 둔갑하지 않는다
            assertThat(result.get("totalPnl")).isEqualTo(20_000L);
        }
    }

    // ── 분포 ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("종목별 · 칸별")
    class Breakdown {

        @Test
        @DisplayName("종목별 성적은 손익이 큰 쪽이 먼저 나온다")
        void by_stock_is_sorted_by_pnl() {
            givenOneWinOneLoss();

            List<Map<String, Object>> stocks = list(sut().byStock(30), "stocks");

            assertThat(stocks).extracting(row -> row.get("stockCode"))
                    .containsExactly("005930", "000660");
            assertThat(stocks.getFirst().get("totalPnl")).isEqualTo(20_000L);
            assertThat(stocks.getLast().get("totalPnl")).isEqualTo(-10_000L);
        }

        @Test
        @DisplayName("칸별 성적에는 사람이 읽는 이름표가 붙는다")
        void by_bucket_carries_a_display_name() {
            givenOneWinOneLoss();

            List<Map<String, Object>> buckets = list(sut().byBucket(30), "buckets");

            assertThat(buckets.getFirst().get("bucket")).isEqualTo("VB");
            assertThat(buckets.getFirst().get("displayName")).isEqualTo(StrategyBucket.VB.getDisplayName());
        }

        @Test
        @DisplayName("종목별·칸별 응답에도 추정 경고와 측정 불가 건수가 들어 있다")
        void breakdowns_carry_the_same_warning() {
            givenOneWinOneLoss();

            for (Map<String, Object> result : List.of(sut().byStock(30), sut().byBucket(30))) {
                assertThat(result.get("estimated")).isEqualTo(true);
                assertThat(result).containsKey("warning");
                assertThat(result).containsKey("unmeasurable");
            }
        }
    }

    // ── 체결 시각 보정 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("체결 기록이 날짜를 넘겨 찍힌 매도는 접수 날짜로 되돌려 캔들을 찾는다")
    void fill_recorded_after_midnight_uses_the_request_date() {
        // 실측 사례: 09-08 15:29:55 접수 → 09-09 00:00:27 체결 기록(대사 경로)
        buy("005930", "2026-09-19T10:00", 10, 70_000.0, StrategyBucket.VB);
        OrderHistory lateSell = filled("005930", OrderSide.SELL, "2026-09-20T15:29:55", 10, 0.0, null);
        ReflectionTestUtils.setField(lateSell, "filledAt", LocalDateTime.parse("2026-09-21T00:00:27"));
        orders.add(lateSell);
        givenClose("005930", "2026-09-20", 72_000);   // 접수한 날에만 캔들이 있다

        Map<String, Object> result = sut().summary(30);

        assertThat(result.get("totalTrades")).isEqualTo(1);
        assertThat(result.get("totalPnl")).isEqualTo(20_000L);
    }
}
