package com.trading.dashboard;

import com.trading.market.KisProperties;
import com.trading.order.OrderHistory;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderStatus;
import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResult;
import com.trading.position.TradeResultRepository;
import com.trading.risk.TradingStatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 대시보드용 읽기 전용 API.
 * 모든 쓰기는 OrderEngine을 통하며, 이 컨트롤러는 조회만 담당한다.
 * KIS 현재가 조회는 QuoteCacheService(2초 캐시 공유)를 경유한다.
 */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private static final int MAX_ORDERS = 20;

    private final QuoteCacheService      quoteCache;
    private final KisProperties          kisProperties;
    private final PositionRepository     positionRepository;
    private final OrderHistoryRepository orderHistoryRepository;
    private final TradingStatusManager   tradingStatusManager;
    private final PositionManager        positionManager;
    private final TradeResultRepository  tradeResultRepository;

    public DashboardController(QuoteCacheService      quoteCache,
                                KisProperties          kisProperties,
                                PositionRepository     positionRepository,
                                OrderHistoryRepository orderHistoryRepository,
                                TradingStatusManager   tradingStatusManager,
                                PositionManager        positionManager,
                                TradeResultRepository  tradeResultRepository) {
        this.quoteCache             = quoteCache;
        this.kisProperties          = kisProperties;
        this.positionRepository     = positionRepository;
        this.orderHistoryRepository = orderHistoryRepository;
        this.tradingStatusManager   = tradingStatusManager;
        this.positionManager        = positionManager;
        this.tradeResultRepository  = tradeResultRepository;
    }

    // ── 1. 현재가 ─────────────────────────────────────────────────────────────

    @GetMapping("/market/quote/{stockCode}")
    public Map<String, Object> getQuote(@PathVariable String stockCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stockCode",  stockCode);
        result.put("configured", kisProperties.isConfigured());
        if (!kisProperties.isConfigured()) return result;

        try {
            QuoteCacheService.Quote q = quoteCache.fetch(stockCode);
            result.put("stockName",    q.stockName());
            result.put("currentPrice", q.currentPrice());
            result.put("changeAmount", q.changeAmount());
            result.put("changeRate",   q.changeRate());
            result.put("volume",       q.volume());
        } catch (Exception e) {
            log.warn("현재가 조회 실패: {} — {}", stockCode, e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ── 2. 보유 포지션 ────────────────────────────────────────────────────────

    @GetMapping("/position")
    public List<Map<String, Object>> getPositions() {
        List<Position> positions = positionRepository.findAll().stream()
                .filter(p -> p.getQuantity() > 0)
                .toList();

        if (positions.isEmpty()) return Collections.emptyList();

        Map<String, QuoteCacheService.Quote> quotes = new HashMap<>();
        if (kisProperties.isConfigured()) {
            for (Position p : positions) {
                try {
                    quotes.put(p.getStockCode(), quoteCache.fetch(p.getStockCode()));
                } catch (Exception e) {
                    log.warn("포지션 현재가 실패: {}", p.getStockCode());
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Position p : positions) {
            Map<String, Object> item = new LinkedHashMap<>();
            QuoteCacheService.Quote q = quotes.get(p.getStockCode());

            item.put("stockCode",    p.getStockCode());
            item.put("stockName",    q != null ? q.stockName() : null);
            item.put("quantity",     p.getQuantity());
            item.put("averagePrice", Math.round(p.getAveragePrice()));

            Long cp = q != null ? q.currentPrice() : null;
            item.put("currentPrice", cp);
            item.put("changeAmount", q != null ? q.changeAmount() : null);
            item.put("changeRate",   q != null ? q.changeRate() : null);

            if (cp != null) {
                long unrealizedPnl  = (cp - Math.round(p.getAveragePrice())) * (long) p.getQuantity();
                double unrealizedPnlRate = p.getAveragePrice() > 0
                        ? (cp - p.getAveragePrice()) / p.getAveragePrice() * 100.0
                        : 0.0;
                item.put("unrealizedPnl",     unrealizedPnl);
                item.put("unrealizedPnlRate", Math.round(unrealizedPnlRate * 100.0) / 100.0);
            } else {
                item.put("unrealizedPnl",     null);
                item.put("unrealizedPnlRate", null);
            }

            // ATR 손절선 — 미장착(부분 체결 매수 등)이면 null
            item.put("stopPrice", p.getStopPrice() != null ? Math.round(p.getStopPrice()) : null);
            if (p.getStopPrice() != null && cp != null && cp > 0) {
                double stopDistancePct = (cp - p.getStopPrice()) / cp * 100.0;
                item.put("stopDistancePct", Math.round(stopDistancePct * 100.0) / 100.0);
            } else {
                item.put("stopDistancePct", null);
            }
            result.add(item);
        }
        return result;
    }

    // ── 3. 당일 손익 ──────────────────────────────────────────────────────────

    @GetMapping("/pnl/daily")
    public Map<String, Object> getDailyPnl() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", kisProperties.isConfigured());

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long tradeCount = orderHistoryRepository
                .findByStatusIn(List.of(OrderStatus.FILLED, OrderStatus.PARTIAL_FILLED))
                .stream()
                .filter(o -> o.getFilledAt() != null && o.getFilledAt().isAfter(todayStart))
                .count();
        result.put("tradeCount", tradeCount);

        if (kisProperties.isConfigured()) {
            long totalUnrealized = 0;
            try {
                for (Position p : positionRepository.findAll()) {
                    if (p.getQuantity() <= 0) continue;
                    long cp = quoteCache.fetch(p.getStockCode()).currentPrice();
                    totalUnrealized += (cp - Math.round(p.getAveragePrice())) * (long) p.getQuantity();
                }
            } catch (Exception e) {
                log.warn("미실현 손익 계산 실패 — {}", e.getMessage());
            }
            result.put("unrealizedPnl", totalUnrealized);
        } else {
            result.put("unrealizedPnl", null);
        }

        long realized = Math.round(tradeResultRepository.findByTradeDate(LocalDate.now()).stream()
                .mapToDouble(TradeResult::getRealizedPnl).sum());
        result.put("realizedPnl", realized);
        return result;
    }

    // ── 4. 최근 체결 내역 ─────────────────────────────────────────────────────

    @GetMapping("/orders/filled")
    public List<Map<String, Object>> getFilledOrders() {
        List<OrderStatus> targetStatuses = List.of(
                OrderStatus.FILLED,   OrderStatus.PARTIAL_FILLED,
                OrderStatus.CANCELLED, OrderStatus.FAILED, OrderStatus.CANCEL_FAILED
        );
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM/dd HH:mm");

        return orderHistoryRepository.findByStatusIn(targetStatuses).stream()
                .sorted(Comparator.comparing(OrderHistory::getRequestedAt).reversed())
                .limit(MAX_ORDERS)
                .map(o -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id",          o.getId());
                    item.put("stockCode",   o.getStockCode());
                    item.put("side",        o.getSide().name());
                    item.put("quantity",    o.getQuantity());
                    item.put("filledQty",   o.getFilledQuantity());
                    item.put("filledPrice", o.getFilledPrice() != null ? Math.round(o.getFilledPrice()) : null);
                    item.put("status",      o.getStatus().name());
                    item.put("requestedAt", o.getRequestedAt().format(fmt));
                    return item;
                })
                .toList();
    }

    // ── 5. 리스크 상태 ────────────────────────────────────────────────────────

    @GetMapping("/risk/status")
    public Map<String, Object> getRiskStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tradingMode",          tradingStatusManager.getCurrentMode().name());
        result.put("configured",           kisProperties.isConfigured());

        // Gate 1·3 실값 — 잔고 스냅샷(3초 캐시) 재사용, 실패 시 0 폴백
        double dailyPnl = 0.0;
        int consecutiveLosses = 0;
        if (kisProperties.isConfigured()) {
            try {
                Account account = positionManager.snapshotAccount();
                dailyPnl = account.getDailyPnlPercent();
                consecutiveLosses = account.getConsecutiveLossCount();
            } catch (Exception e) {
                log.warn("리스크 상태 스냅샷 실패 — 0 폴백: {}", e.getMessage());
            }
        }
        result.put("dailyPnlPercent",      dailyPnl);
        result.put("consecutiveLossCount", consecutiveLosses);
        return result;
    }
}
