package com.trading.dashboard;

import com.trading.bucket.StrategyBucket;
import com.trading.order.OrderHistory;
import com.trading.order.OrderSide;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 체결된 주문을 <b>선입선출(FIFO)</b>로 짝지어 거래 단위로 만든다 (2026-09-22 신설).
 *
 * <p><b>짝짓기 규칙</b>
 * <ul>
 *   <li>종목별로 매수를 줄 세우고, 매도가 나오면 <b>가장 오래된 매수부터</b> 채운다.</li>
 *   <li>매도 한 건이 매수 여러 건을 덮으면 <b>거래도 그만큼 나뉜다</b>
 *       (매수 2건을 한 번에 팔면 거래 2건으로 센다).</li>
 *   <li>칸(bucket)은 매수 쪽 것을 쓴다 — 매도에는 칸이 붙지 않는다.</li>
 *   <li>아직 안 팔린 매수는 그냥 남긴다. 측정 불가가 아니라 <b>열린 포지션</b>이다.</li>
 * </ul>
 *
 * <p><b>측정 불가로 세는 것</b> — 매도 수량 기준으로 셋 중 하나다:
 * ① 매도가 추정 실패(분봉·일봉 없음) ② 짝지을 매수 기록 없음 ③ 매수 체결가가 0/없음.
 *
 * <p>기간(days)으로 자르는 일은 여기서 하지 않는다. <b>짝짓기는 항상 전 기간</b>으로 하고
 * 결과를 매도 시각으로 걸러야, 기간 앞에 있던 매수가 잘려 멀쩡한 거래가 "짝 없음"이 되지 않는다.
 */
final class TradePairer {

    static final String NO_SELL_PRICE = "매도가 추정 불가 — 그 시각의 분봉·일봉이 없음";
    static final String NO_MATCHING_BUY = "짝지을 매수 기록 없음";
    static final String NO_BUY_PRICE = "매수 체결가 없음";

    /** 집계에서 뺀 매도 조각 한 건 */
    record Unmeasurable(String stockCode, LocalDateTime soldAt, int quantity, String reason) {}

    record Result(List<EstimatedTrade> trades, List<Unmeasurable> unmeasurable) {}

    /** 아직 안 팔린 매수 조각 (불변 — 부분 소진은 새 객체로 갈아 끼운다) */
    private record Lot(int quantity, Double price, StrategyBucket bucket, LocalDateTime at) {
        Lot minus(int q) { return new Lot(quantity - q, price, bucket, at); }
        boolean priced()  { return price != null && price > 0; }
    }

    private TradePairer() {}

    static Result pair(List<OrderHistory> filledOrders, SellPriceEstimator.Lookup lookup) {
        List<EstimatedTrade> trades = new ArrayList<>();
        List<Unmeasurable> unmeasurable = new ArrayList<>();
        Map<String, Deque<Lot>> openLots = new HashMap<>();

        for (OrderHistory order : chronological(filledOrders)) {
            Deque<Lot> lots = openLots.computeIfAbsent(order.getStockCode(), k -> new ArrayDeque<>());
            if (order.getSide() == OrderSide.BUY) {
                lots.addLast(new Lot(order.getFilledQuantity(), order.getFilledPrice(),
                        StrategyBucket.orDefault(order.getBucket()), fillTimeOf(order)));
            } else {
                consume(order, lots, lookup, trades, unmeasurable);
            }
        }
        return new Result(trades, unmeasurable);
    }

    private static void consume(OrderHistory sell, Deque<Lot> lots, SellPriceEstimator.Lookup lookup,
                                List<EstimatedTrade> trades, List<Unmeasurable> unmeasurable) {
        LocalDateTime soldAt = fillTimeOf(sell);
        SellPriceEstimator.Estimate estimate = lookup.estimate(sell.getStockCode(), soldAt);

        int remaining = sell.getFilledQuantity();
        while (remaining > 0) {
            Lot lot = lots.pollFirst();
            if (lot == null) {
                unmeasurable.add(new Unmeasurable(sell.getStockCode(), soldAt, remaining, NO_MATCHING_BUY));
                return;
            }
            int matched = Math.min(remaining, lot.quantity());
            record(sell, lot, matched, soldAt, estimate, trades, unmeasurable);

            if (lot.quantity() > matched) lots.addFirst(lot.minus(matched));
            remaining -= matched;
        }
    }

    private static void record(OrderHistory sell, Lot lot, int matched, LocalDateTime soldAt,
                               SellPriceEstimator.Estimate estimate,
                               List<EstimatedTrade> trades, List<Unmeasurable> unmeasurable) {
        if (!estimate.isMeasurable()) {
            unmeasurable.add(new Unmeasurable(sell.getStockCode(), soldAt, matched, NO_SELL_PRICE));
        } else if (!lot.priced()) {
            unmeasurable.add(new Unmeasurable(sell.getStockCode(), soldAt, matched, NO_BUY_PRICE));
        } else {
            trades.add(new EstimatedTrade(sell.getStockCode(), lot.bucket(), lot.at(), soldAt,
                    matched, lot.price(), estimate.price(), estimate.source()));
        }
    }

    private static List<OrderHistory> chronological(List<OrderHistory> orders) {
        Comparator<OrderHistory> byFillTime = Comparator.comparing(TradePairer::fillTimeOf);
        Comparator<OrderHistory> byId = Comparator.comparingLong(o -> o.getId() == null ? 0L : o.getId());
        return orders.stream().sorted(byFillTime.thenComparing(byId)).toList();
    }

    /**
     * 거래가 실제로 일어난 것으로 보는 시각.
     *
     * <p>보통은 {@code filledAt}이다. 다만 모의에서는 체결 확인이 대사(reconcile) 경로로
     * 늦게 오는 일이 있어 <b>날짜를 넘겨 찍히기도 한다</b>(실측: 15:29:55 접수 → 다음날 00:00:27
     * 체결 기록). 그러면 그 다음날 캔들에서 가격을 찾게 되므로, 날짜가 어긋난 경우에는
     * 접수 시각을 쓴다 — 전부 시장가 주문이라 접수 직후에 체결됐다고 보는 편이 실제에 가깝다.
     */
    static LocalDateTime fillTimeOf(OrderHistory order) {
        LocalDateTime filledAt = order.getFilledAt();
        LocalDateTime requestedAt = order.getRequestedAt();
        if (filledAt == null) return requestedAt;
        return filledAt.toLocalDate().equals(requestedAt.toLocalDate()) ? filledAt : requestedAt;
    }
}
