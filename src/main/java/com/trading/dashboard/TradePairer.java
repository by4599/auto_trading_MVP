package com.trading.dashboard;

import com.trading.bucket.StrategyBucket;
import com.trading.order.OrderHistory;
import com.trading.order.OrderSide;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * 체결된 주문을 짝지어 거래 단위로 만든다 (2026-09-22 신설 · 2026-10-10 칸 우선 짝짓기, 46_audit M-5).
 *
 * <p><b>짝짓기 규칙</b>
 * <ul>
 *   <li>종목별로 매수 조각을 시간순으로 쌓는다.</li>
 *   <li>매도가 나오면 <b>그 매도 직전에 마지막으로 체결된 그 종목 매수의 칸</b>(주인 칸) 조각부터
 *       오래된 순(선입선출)으로 채운다. 같은 종목은 한 번에 한 칸만 들고 있기 때문이다 — {@code PendingOrderRule}이
 *       그 종목을 보유 중(Position 수량 &gt; 0)이거나 미체결 매수(ACCEPTED)가 있으면 새 매수를 거부한다.
 *       매도 주문에는 칸이 붙지 않아 매도 쪽에서는 칸을 알 수 없다.</li>
 *   <li>주인 칸 조각이 모자라면 다른 칸 조각으로 넘어가되 그 조각에 <b>칸 넘김</b>(crossBucket) 표시를 남긴다.
 *       예전에는 전 기간 단일 선입선출이라, 주문 없이 0주가 된 옛 매수(실측 066570: 09-01 매수 5주가 짝 없이
 *       남음)가 나중의 다른 칸 매도와 짝지어져 손익이 엉뚱한 칸에 붙었다.</li>
 *   <li>매도 한 건이 매수 여러 건을 덮으면 <b>거래도 그만큼 나뉜다</b>. 칸은 매수 쪽 것을 쓴다.</li>
 *   <li>아직 안 팔린 매수는 그냥 남긴다. 측정 불가가 아니라 <b>열린 포지션</b>이다.</li>
 * </ul>
 * ⚠ 같은 칸 안의 옛 조각은 여전히 먼저 쓰인다(칸 안 선입선출) — 그 칸 실현손익이 "가장 최근 매수가 − 옛 매수가"
 * × 그 조각 수량만큼 어긋날 수 있다. 라운드트립이 거듭돼도 차이는 서로 상쇄돼 한 조각 크기를 넘지 않는다.
 *
 * <p><b>측정 불가로 세는 것</b> — 매도 수량 기준으로 셋 중 하나다:
 * ① 매도가 추정 실패(분봉·일봉 없음) ② 짝지을 매수 기록 없음 ③ 매수 체결가가 0/없음.
 *
 * <p>짝짓기({@link #match}, DB를 읽지 않는다)와 가격 추정({@link #price}, 분봉을 읽는다)을 나눴다 — 칸 낙폭
 * 감시(1분 주기)는 짝짓기 결과를 재사용하고 필요한 기간의 매도만 추정한다(46_audit M-2).
 * 기간으로 자르는 일은 짝짓기에서 하지 않는다. <b>짝짓기는 항상 전 기간</b>으로 해야 기간 앞에 있던 매수가
 * 잘려 멀쩡한 거래가 "짝 없음"이 되지 않는다.
 */
final class TradePairer {

    static final String NO_SELL_PRICE = "매도가 추정 불가 — 그 시각의 분봉·일봉이 없음";
    static final String NO_MATCHING_BUY = "짝지을 매수 기록 없음";
    static final String NO_BUY_PRICE = "매수 체결가 없음";

    /**
     * 집계에서 뺀 매도 조각 한 건.
     * bucket = 짝지은 매수의 칸 — 칸별 낙폭 상한이 측정 불가 개수를 칸마다 세는 데 쓴다. 짝 없음이면 null
     */
    record Unmeasurable(String stockCode, LocalDateTime soldAt, int quantity, String reason,
                        StrategyBucket bucket, boolean crossBucket) {}

    record Result(List<EstimatedTrade> trades, List<Unmeasurable> unmeasurable) {}

    /** 매도 조각 ↔ 매수 조각 한 쌍 — 가격 추정 전. buyPrice는 매수 체결가(없을 수 있다) */
    record Match(String stockCode, LocalDateTime soldAt, int quantity, StrategyBucket bucket,
                 Double buyPrice, LocalDateTime boughtAt, boolean crossBucket) {}

    /** 짝지을 매수가 없는 매도 조각 */
    record Unmatched(String stockCode, LocalDateTime soldAt, int quantity) {}

    /** 짝짓기 결과 (가격 없음) */
    record Matching(List<Match> matches, List<Unmatched> unmatched) {

        /** 남길 조각만 고른 새 결과 — 원본은 그대로 둔다 */
        Matching where(Predicate<Match> keepMatch, Predicate<Unmatched> keepUnmatched) {
            return new Matching(matches.stream().filter(keepMatch).toList(),
                    unmatched.stream().filter(keepUnmatched).toList());
        }

        long crossBucketCount() {
            return matches.stream().filter(Match::crossBucket).count();
        }
    }

    /** 아직 안 팔린 매수 조각 (불변 — 부분 소진은 새 객체로 갈아 끼운다) */
    private record Lot(int quantity, Double price, StrategyBucket bucket, LocalDateTime at) {
        Lot minus(int q) { return new Lot(quantity - q, price, bucket, at); }
    }

    /** 매도 한 건 — owner = 그 매도 직전 마지막 매수의 칸 (앞선 매수가 없으면 null) */
    private record Sale(String stockCode, LocalDateTime soldAt, StrategyBucket owner) {}

    private TradePairer() {}

    /** 짝짓기 + 전 기간 가격 추정 — 대시보드 성적용 */
    static Result pair(List<OrderHistory> filledOrders, SellPriceEstimator.Lookup lookup) {
        return price(match(filledOrders), lookup::estimate);
    }

    /** 짝짓기만 한다 — DB를 읽지 않는다 */
    static Matching match(List<OrderHistory> filledOrders) {
        List<Match> matches = new ArrayList<>();
        List<Unmatched> unmatched = new ArrayList<>();
        Map<String, List<Lot>> openLots = new HashMap<>();
        Map<String, StrategyBucket> lastBuyBucket = new HashMap<>();

        for (OrderHistory order : chronological(filledOrders)) {
            if (order.getFilledQuantity() <= 0) continue;   // 체결분 없는 주문은 짝에도, 주인 칸 판단에도 끼지 않는다
            String code = order.getStockCode();
            List<Lot> lots = openLots.computeIfAbsent(code, k -> new ArrayList<>());
            if (order.getSide() == OrderSide.BUY) {
                StrategyBucket bucket = StrategyBucket.orDefault(order.getBucket());
                lots.add(new Lot(order.getFilledQuantity(), order.getFilledPrice(), bucket, fillTimeOf(order)));
                lastBuyBucket.put(code, bucket);
            } else {
                Sale sale = new Sale(code, fillTimeOf(order), lastBuyBucket.get(code));
                int left = take(sale, order.getFilledQuantity(), lots, true, matches);
                left = take(sale, left, lots, false, matches);
                if (left > 0) unmatched.add(new Unmatched(code, sale.soldAt(), left));
            }
        }
        return new Matching(matches, unmatched);
    }

    /**
     * 조각에서 수량을 덜어 짝을 만든다. ownerOnly면 주인 칸 조각만, 아니면 남은 아무 조각이나(칸 넘김 표시).
     * @return 채우지 못한 수량
     */
    private static int take(Sale sale, int quantity, List<Lot> lots, boolean ownerOnly, List<Match> matches) {
        int remaining = quantity;
        int i = 0;
        while (remaining > 0 && i < lots.size()) {
            Lot lot = lots.get(i);
            if (ownerOnly && lot.bucket() != sale.owner()) {
                i++;
                continue;
            }
            int q = Math.min(remaining, lot.quantity());
            boolean cross = sale.owner() != null && lot.bucket() != sale.owner();
            matches.add(new Match(sale.stockCode(), sale.soldAt(), q, lot.bucket(), lot.price(), lot.at(), cross));
            remaining -= q;
            if (lot.quantity() > q) {
                lots.set(i, lot.minus(q));
                i++;
            } else {
                lots.remove(i);
            }
        }
        return remaining;
    }

    /** 짝에 매도가 추정을 붙인다. 판정 순서는 예전과 같다 — 매도가 추정 → 매수 체결가 */
    static Result price(Matching matching,
                        BiFunction<String, LocalDateTime, SellPriceEstimator.Estimate> estimator) {
        List<EstimatedTrade> trades = new ArrayList<>();
        List<Unmeasurable> unmeasurable = new ArrayList<>();
        for (Match m : matching.matches()) {
            SellPriceEstimator.Estimate e = estimator.apply(m.stockCode(), m.soldAt());
            if (!e.isMeasurable()) {
                unmeasurable.add(excluded(m, NO_SELL_PRICE));
            } else if (m.buyPrice() == null || !(m.buyPrice() > 0)) {   // NaN도 거부 (46b N-3)
                unmeasurable.add(excluded(m, NO_BUY_PRICE));
            } else {
                trades.add(new EstimatedTrade(m.stockCode(), m.bucket(), m.boughtAt(), m.soldAt(),
                        m.quantity(), m.buyPrice(), e.price(), e.source(), m.crossBucket()));
            }
        }
        for (Unmatched u : matching.unmatched()) {
            unmeasurable.add(new Unmeasurable(u.stockCode(), u.soldAt(), u.quantity(), NO_MATCHING_BUY, null, false));
        }
        return new Result(trades, unmeasurable);
    }

    private static Unmeasurable excluded(Match m, String reason) {
        return new Unmeasurable(m.stockCode(), m.soldAt(), m.quantity(), reason, m.bucket(), m.crossBucket());
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
