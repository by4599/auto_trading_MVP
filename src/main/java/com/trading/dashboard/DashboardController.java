package com.trading.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.market.KisApiClient;
import com.trading.market.KisProperties;
import com.trading.order.OrderHistory;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderStatus;
import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.risk.TradingStatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 대시보드용 읽기 전용 API.
 * 모든 쓰기는 OrderEngine을 통하며, 이 컨트롤러는 조회만 담당한다.
 */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private static final String TR_CURRENT_PRICE   = "FHKST01010100";
    private static final String MARKET_CODE        = "J";
    private static final int    MAX_ORDERS         = 20;
    // KIS rate limit 대응: 2초 이내 동일 종목 요청은 캐시된 값 반환
    private static final long   QUOTE_CACHE_TTL_MS = 2_000L;

    private final KisApiClient           kisApiClient;
    private final KisProperties          kisProperties;
    private final PositionRepository     positionRepository;
    private final OrderHistoryRepository orderHistoryRepository;
    private final TradingStatusManager   tradingStatusManager;
    private final PositionManager        positionManager;
    private final ConcurrentHashMap<String, CachedQuote> quoteCache = new ConcurrentHashMap<>();

    public DashboardController(KisApiClient           kisApiClient,
                                KisProperties          kisProperties,
                                PositionRepository     positionRepository,
                                OrderHistoryRepository orderHistoryRepository,
                                TradingStatusManager   tradingStatusManager,
                                PositionManager        positionManager) {
        this.kisApiClient           = kisApiClient;
        this.kisProperties          = kisProperties;
        this.positionRepository     = positionRepository;
        this.orderHistoryRepository = orderHistoryRepository;
        this.tradingStatusManager   = tradingStatusManager;
        this.positionManager        = positionManager;
    }

    // ── 1. 현재가 ─────────────────────────────────────────────────────────────

    @GetMapping("/market/quote/{stockCode}")
    public Map<String, Object> getQuote(@PathVariable String stockCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stockCode",  stockCode);
        result.put("configured", kisProperties.isConfigured());
        if (!kisProperties.isConfigured()) return result;

        try {
            PriceOutput o = fetchPrice(stockCode);
            result.put("stockName",    o.stockName());
            result.put("currentPrice", parseLong(o.current()));
            result.put("changeAmount", parseLong(o.changeAmount()));
            result.put("changeRate",   parseDouble(o.changeRate()));
            result.put("volume",       parseLong(o.volume()));
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

        Map<String, Long> currentPrices = new HashMap<>();
        if (kisProperties.isConfigured()) {
            for (Position p : positions) {
                try {
                    currentPrices.put(p.getStockCode(), parseLong(fetchPrice(p.getStockCode()).current()));
                } catch (Exception e) {
                    log.warn("포지션 현재가 실패: {}", p.getStockCode());
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Position p : positions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stockCode",    p.getStockCode());
            item.put("quantity",     p.getQuantity());
            item.put("averagePrice", Math.round(p.getAveragePrice()));

            Long cp = currentPrices.get(p.getStockCode());
            item.put("currentPrice", cp);

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
                    long cp = parseLong(fetchPrice(p.getStockCode()).current());
                    totalUnrealized += (cp - Math.round(p.getAveragePrice())) * (long) p.getQuantity();
                }
            } catch (Exception e) {
                log.warn("미실현 손익 계산 실패 — {}", e.getMessage());
            }
            result.put("unrealizedPnl", totalUnrealized);
        } else {
            result.put("unrealizedPnl", null);
        }

        result.put("realizedPnl", null);
        result.put("note", "실현손익은 Sprint 3 예정");
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

    // ── KIS API 공통 ─────────────────────────────────────────────────────────

    private PriceOutput fetchPrice(String stockCode) {
        CachedQuote cached = quoteCache.get(stockCode);
        if (cached != null && !cached.isStale()) return cached.output();

        PriceResponse resp = kisApiClient.getClient().get()
                .uri(b -> b.path("/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", MARKET_CODE)
                        .queryParam("FID_INPUT_ISCD",         stockCode)
                        .build())
                .header("tr_id",    TR_CURRENT_PRICE)
                .header("custtype", "P")
                .retrieve()
                .body(PriceResponse.class);

        if (resp == null || resp.output() == null) {
            throw new IllegalStateException("현재가 응답 없음: stockCode=" + stockCode);
        }
        quoteCache.put(stockCode, new CachedQuote(resp.output(), System.currentTimeMillis()));
        return resp.output();
    }

    private record CachedQuote(PriceOutput output, long timestamp) {
        boolean isStale() { return System.currentTimeMillis() - timestamp > QUOTE_CACHE_TTL_MS; }
    }

    private static long parseLong(String s) {
        if (s == null || s.isBlank()) return 0L;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return 0L; }
    }

    private static double parseDouble(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    // ── KIS 응답 DTO ─────────────────────────────────────────────────────────

    private record PriceResponse(
            @JsonProperty("output") PriceOutput output
    ) {}

    private record PriceOutput(
            @JsonProperty("stck_prpr")      String current,       // 현재가
            @JsonProperty("stck_prdy_vrss") String changeAmount,  // 전일대비
            @JsonProperty("prdy_ctrt")      String changeRate,     // 등락률 %
            @JsonProperty("acml_vol")       String volume,         // 누적 거래량
            @JsonProperty("hts_kor_isnm")   String stockName       // 종목명
    ) {}
}
