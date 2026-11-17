package com.trading.dashboard;

import com.trading.backtest.MutableClock;
import com.trading.bucket.StrategyBucket;
import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.MinuteCandle;
import com.trading.market.Timeframe;
import com.trading.order.OrderHistory;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import com.trading.position.TradeResult;
import com.trading.position.TradeResultRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 칸 실현손익 어댑터 테스트 공용(대시보드 짝짓기 테스트도 같이 쓴다) — 주문·기록·분봉을 맵에 두고
 * 리포지토리(인터페이스) 목이 그것을 돌려준다. 시계는 앞으로 돌릴 수 있다(같은 회차 재사용·하루 단위 동작 검증용).
 */
final class SleeveAdapterFixture {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    final OrderHistoryRepository orderRepository = mock(OrderHistoryRepository.class);
    final TradeResultRepository tradeResultRepository = mock(TradeResultRepository.class);
    final CandleHistoryRepository candleRepository = mock(CandleHistoryRepository.class);
    final MutableClock clock;

    final List<OrderHistory> orders = new ArrayList<>();
    final List<TradeResult> tradeResults = new ArrayList<>();
    private final Map<String, Double> minuteCloses = new HashMap<>();
    private LocalDateTime now;
    private long nextId = 1;

    SleeveAdapterFixture(LocalDateTime now) {
        this.now = now;
        this.clock = new MutableClock(now.atZone(KST).toInstant());
        stubRepositories();
    }

    SleeveRealizedPnlAdapter adapter() {
        return new SleeveRealizedPnlAdapter(orderRepository, tradeResultRepository,
                new SellPriceEstimator(candleRepository), clock);
    }

    void advance(Duration duration) {
        now = now.plus(duration);
        clock.setTo(now.toLocalDate(), now.toLocalTime());
    }

    void close(String code, String date, double price) {
        minuteCloses.put(code + "|" + LocalDate.parse(date), price);
    }

    void buy(String code, String at, int qty, double price, StrategyBucket bucket) {
        filled(code, OrderSide.BUY, at, qty, price, bucket);
    }

    /** 모의 매도 — 체결가 0, 칸 이름표 없음 (그 상태 그대로) */
    void unpricedSell(String code, String at, int qty) {
        filled(code, OrderSide.SELL, at, qty, 0.0, null);
    }

    /** 체결가를 받은 매도 — 정상 경로라 TradeResult도 같은 시각에 남는다 */
    void recordedSell(String code, String at, int qty, double buyAvg, double sellPrice, StrategyBucket bucket) {
        filled(code, OrderSide.SELL, at, qty, sellPrice, null);
        TradeResult r = TradeResult.live(code, qty, buyAvg, sellPrice, bucket);
        LocalDateTime soldAt = LocalDateTime.parse(at).plusMinutes(1);
        ReflectionTestUtils.setField(r, "soldAt", soldAt);
        ReflectionTestUtils.setField(r, "tradeDate", soldAt.toLocalDate());
        tradeResults.add(r);
    }

    OrderHistory filled(String code, OrderSide side, String at, int qty, Double price, StrategyBucket bucket) {
        LocalDateTime time = LocalDateTime.parse(at);
        OrderHistory o = OrderHistory.accepted(code, side, qty, "ORD-" + nextId, bucket);
        ReflectionTestUtils.setField(o, "id", nextId++);
        ReflectionTestUtils.setField(o, "status", OrderStatus.FILLED);
        ReflectionTestUtils.setField(o, "requestedAt", time);
        ReflectionTestUtils.setField(o, "filledAt", time.plusMinutes(1));
        ReflectionTestUtils.setField(o, "filledQuantity", qty);
        ReflectionTestUtils.setField(o, "filledPrice", price);
        orders.add(o);
        return o;
    }

    private void stubRepositories() {
        when(orderRepository.findByStatusIn(anyList())).thenAnswer(i -> {
            List<OrderStatus> statuses = i.getArgument(0);
            return orders.stream().filter(o -> statuses.contains(o.getStatus())).toList();
        });
        when(orderRepository.findByFilledQuantityGreaterThan(anyInt())).thenAnswer(i ->
                orders.stream().filter(o -> o.getFilledQuantity() > (int) i.getArgument(0)).toList());
        when(tradeResultRepository.findAll()).thenAnswer(i -> List.copyOf(tradeResults));
        when(candleRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String code = invocation.getArgument(0);
                    Timeframe timeframe = invocation.getArgument(1);
                    LocalDate date = invocation.getArgument(2);
                    Double close = minuteCloses.get(code + "|" + date);
                    if (timeframe != Timeframe.MINUTE || close == null) return List.of();
                    return List.of(CandleHistory.ofMinute(code, new MinuteCandle(
                            date, LocalTime.of(10, 0), close, close, close, close, 1)));
                });
    }
}
