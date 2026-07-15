package com.trading.position;

import com.trading.order.OrderHistory;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 과거 order_history 체결을 시간순 재생해 실현손익(trade_result)을 소급 생성한다.
 *
 * trade_result 영속화(FillStateUpdater) 이전의 체결 기록에서 실적을 복원하는
 * 1회성 도구다. 멱등: 기존 BACKFILL 레코드를 전부 지우고 다시 만든다.
 * LIVE 레코드가 이미 시작된 시각 이후 구간은 생성하지 않는다 (중복 방지).
 *
 * 재생 규칙: BUY 체결은 이동 평균단가 갱신, SELL 체결은 (체결가-평단)×수량.
 * 재생 도중 잔량보다 큰 매도가 나오는 종목(기록 유실 등)은 해당 매도를 스킵하고
 * warning으로 보고한다.
 */
@Component
public class PerformanceBackfillService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceBackfillService.class);

    private final OrderHistoryRepository orderHistoryRepository;
    private final TradeResultRepository tradeResultRepository;

    public PerformanceBackfillService(OrderHistoryRepository orderHistoryRepository,
                                      TradeResultRepository tradeResultRepository) {
        this.orderHistoryRepository = orderHistoryRepository;
        this.tradeResultRepository = tradeResultRepository;
    }

    @Transactional
    public BackfillResult backfill() {
        tradeResultRepository.deleteBySource(TradeResult.Source.BACKFILL);

        LocalDateTime liveCutoff = tradeResultRepository
                .findFirstBySourceOrderBySoldAtAsc(TradeResult.Source.LIVE)
                .map(TradeResult::getSoldAt)
                .orElse(null);

        List<OrderHistory> fills = orderHistoryRepository.findByFilledQuantityGreaterThan(0).stream()
                .sorted(Comparator.comparing(PerformanceBackfillService::fillTime))
                .toList();

        Map<String, Running> state = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        int created = 0;

        for (OrderHistory o : fills) {
            if (o.getFilledPrice() == null) continue;
            String code = o.getStockCode();
            Running r = state.getOrDefault(code, new Running(0, 0.0));

            if (o.getSide() == OrderSide.BUY) {
                int newQty = r.quantity() + o.getFilledQuantity();
                double newAvg = (r.averagePrice() * r.quantity()
                        + o.getFilledPrice() * o.getFilledQuantity()) / newQty;
                state.put(code, new Running(newQty, newAvg));
                continue;
            }

            // SELL
            if (r.quantity() < o.getFilledQuantity()) {
                warnings.add(String.format("%s: 매도 %d주 > 재생 잔량 %d주 — 이 매도는 스킵 (기록 불일치)",
                        code, o.getFilledQuantity(), r.quantity()));
                continue;
            }
            LocalDateTime soldAt = fillTime(o);
            if (liveCutoff == null || soldAt.isBefore(liveCutoff)) {
                tradeResultRepository.save(TradeResult.backfill(
                        code, o.getFilledQuantity(), r.averagePrice(), o.getFilledPrice(), soldAt));
                created++;
            }
            state.put(code, new Running(r.quantity() - o.getFilledQuantity(), r.averagePrice()));
        }

        log.info("[Backfill] 실현손익 소급 생성 {}건 (warning {}건)", created, warnings.size());
        return new BackfillResult(created, warnings);
    }

    private static LocalDateTime fillTime(OrderHistory o) {
        return o.getFilledAt() != null ? o.getFilledAt() : o.getRequestedAt();
    }

    private record Running(int quantity, double averagePrice) {}

    public record BackfillResult(int created, List<String> warnings) {}
}
