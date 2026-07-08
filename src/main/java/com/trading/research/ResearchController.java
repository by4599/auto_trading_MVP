package com.trading.research;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.market.KisApiClient;
import com.trading.market.KisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 종목 탐색 / 뉴스 조회 API.
 * 이 컨트롤러는 읽기/관심 종목 관리만 담당하며, 자동 매매 파이프라인에 영향을 주지 않는다.
 */
@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private static final Logger log = LoggerFactory.getLogger(ResearchController.class);
    private static final String TR_CURRENT_PRICE = "FHKST01010100";
    private static final String MARKET_CODE      = "J";
    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final WatchlistRepository    watchlistRepository;
    private final NewsRepository         newsRepository;
    private final KisApiClient           kisApiClient;
    private final KisProperties          kisProperties;
    private final RecommendationService  recommendationService;

    public ResearchController(WatchlistRepository   watchlistRepository,
                               NewsRepository        newsRepository,
                               KisApiClient          kisApiClient,
                               KisProperties         kisProperties,
                               RecommendationService recommendationService) {
        this.watchlistRepository   = watchlistRepository;
        this.newsRepository        = newsRepository;
        this.kisApiClient          = kisApiClient;
        this.kisProperties         = kisProperties;
        this.recommendationService = recommendationService;
    }

    // ── 관심 종목 ─────────────────────────────────────────────────────────────

    /** 관심 종목 전체 조회 */
    @GetMapping("/watchlist")
    public List<Map<String, Object>> getWatchlist() {
        List<WatchlistItem> items = watchlistRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (WatchlistItem w : items) {
            Map<String, Object> row = buildWatchlistRow(w, null);
            if (kisProperties.isConfigured()) {
                try {
                    QuoteOutput q = fetchQuote(w.getStockCode());
                    row.put("currentPrice",  parseLong(q.current()));
                    row.put("changeRate",    parseDouble(q.changeRate()));
                    row.put("changeAmount",  parseLong(q.changeAmount()));
                } catch (Exception e) {
                    log.debug("관심 종목 현재가 조회 실패: {} — {}", w.getStockCode(), e.getMessage());
                }
            }
            result.add(row);
        }
        return result;
    }

    /** 관심 종목 추가 */
    @PostMapping("/watchlist")
    @Transactional
    public ResponseEntity<Map<String, Object>> addToWatchlist(@RequestBody AddRequest req) {
        if (req.stockCode() == null || req.stockCode().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "stockCode는 필수입니다"));
        }
        if (watchlistRepository.existsByStockCode(req.stockCode())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", req.stockCode() + "은(는) 이미 관심 목록에 있습니다"));
        }

        String resolvedName = req.stockName();
        if ((resolvedName == null || resolvedName.isBlank()) && kisProperties.isConfigured()) {
            try {
                resolvedName = fetchQuote(req.stockCode()).stockName();
            } catch (Exception e) {
                log.debug("종목명 조회 실패: {}", req.stockCode());
            }
        }

        WatchlistItem saved = watchlistRepository.save(
                WatchlistItem.of(req.stockCode(), resolvedName, req.notes()));

        return ResponseEntity.ok(buildWatchlistRow(saved, null));
    }

    /** 관심 종목 삭제 */
    @DeleteMapping("/watchlist/{stockCode}")
    @Transactional
    public ResponseEntity<Map<String, Object>> removeFromWatchlist(@PathVariable String stockCode) {
        if (!watchlistRepository.existsByStockCode(stockCode)) {
            return ResponseEntity.notFound().build();
        }
        watchlistRepository.deleteByStockCode(stockCode);
        return ResponseEntity.ok(Map.of("deleted", stockCode));
    }

    // ── 뉴스 ─────────────────────────────────────────────────────────────────

    /** 특정 종목 뉴스 */
    @GetMapping("/news")
    public List<Map<String, Object>> getNewsByStock(@RequestParam String stockCode) {
        return newsRepository.findByStockCodeOrderByPublishedAtDesc(stockCode)
                .stream()
                .map(this::toNewsRow)
                .toList();
    }

    /** 관심 종목 전체 최신 뉴스 (최대 50건) */
    @GetMapping("/news/latest")
    public List<Map<String, Object>> getLatestNews() {
        List<String> codes = watchlistRepository.findAll().stream()
                .map(WatchlistItem::getStockCode)
                .toList();
        if (codes.isEmpty()) return Collections.emptyList();
        return newsRepository.findTop50ByStockCodeInOrderByPublishedAtDesc(codes)
                .stream()
                .map(this::toNewsRow)
                .toList();
    }

    // ── 투자 추천 ─────────────────────────────────────────────────────────────

    /**
     * 뉴스 감성 기반 종목 추천 (표시 전용 — 자동 매매와 미연동).
     * days는 1~30으로 제한한다 (시스템 경계 입력 검증).
     */
    @GetMapping("/recommendations")
    public List<RecommendationService.Recommendation> getRecommendations(
            @RequestParam(defaultValue = "3") int days) {
        int bounded = Math.max(1, Math.min(days, 30));
        return recommendationService.recommend(bounded);
    }

    // ── 종목 검증 ─────────────────────────────────────────────────────────────

    /**
     * KIS API로 종목 코드 유효성 검증 + 종목명 조회.
     * KIS 미설정 시에도 호출 가능하나 configured=false 반환.
     */
    @GetMapping("/stock/verify/{stockCode}")
    public Map<String, Object> verifyStock(@PathVariable String stockCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stockCode",  stockCode);
        result.put("configured", kisProperties.isConfigured());

        if (!kisProperties.isConfigured()) return result;

        try {
            QuoteOutput q = fetchQuote(stockCode);
            result.put("valid",        true);
            result.put("stockName",    q.stockName());
            result.put("currentPrice", parseLong(q.current()));
            result.put("changeRate",   parseDouble(q.changeRate()));
        } catch (Exception e) {
            result.put("valid", false);
            result.put("error", "종목을 찾을 수 없습니다: " + stockCode);
        }
        return result;
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private QuoteOutput fetchQuote(String stockCode) {
        QuoteResponse resp = kisApiClient.getClient().get()
                .uri(b -> b.path("/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", MARKET_CODE)
                        .queryParam("FID_INPUT_ISCD",         stockCode)
                        .build())
                .header("tr_id",    TR_CURRENT_PRICE)
                .header("custtype", "P")
                .retrieve()
                .body(QuoteResponse.class);

        if (resp == null || resp.output() == null) {
            throw new IllegalStateException("현재가 응답 없음: stockCode=" + stockCode);
        }
        return resp.output();
    }

    private Map<String, Object> buildWatchlistRow(WatchlistItem w, Object unused) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id",        w.getId());
        row.put("stockCode", w.getStockCode());
        row.put("stockName", w.getStockName());
        row.put("notes",     w.getNotes());
        row.put("addedAt",   w.getAddedAt().format(DT_FMT));
        return row;
    }

    private Map<String, Object> toNewsRow(NewsItem n) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id",          n.getId());
        row.put("stockCode",   n.getStockCode());
        row.put("title",       n.getTitle());
        row.put("url",         n.getUrl());
        row.put("source",      n.getSource());
        row.put("sentiment",   n.getSentiment());
        row.put("publishedAt", n.getPublishedAt() != null ? n.getPublishedAt().format(DT_FMT) : null);
        return row;
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

    // ── 요청/응답 DTO ─────────────────────────────────────────────────────────

    private record AddRequest(String stockCode, String stockName, String notes) {}

    private record QuoteResponse(@JsonProperty("output") QuoteOutput output) {}

    private record QuoteOutput(
            @JsonProperty("stck_prpr")      String current,
            @JsonProperty("stck_prdy_vrss") String changeAmount,
            @JsonProperty("prdy_ctrt")      String changeRate,
            @JsonProperty("hts_kor_isnm")   String stockName
    ) {}
}
