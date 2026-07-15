package com.trading.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.market.KisApiClient;
import com.trading.market.KisProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * KIS 현재가 조회 + 2초 TTL 캐시 (DashboardController에서 추출한 공용 컴포넌트).
 *
 * 대시보드·검토종목 등 여러 조회 경로가 같은 캐시를 공유해 KIS 모의계좌
 * 레이트리밋(초당 2건)을 보호한다. 이 컴포넌트는 조회 전용이다.
 */
@Component
public class QuoteCacheService {

    private static final String TR_CURRENT_PRICE   = "FHKST01010100";
    private static final String MARKET_CODE        = "J";
    private static final long   QUOTE_CACHE_TTL_MS = 2_000L;

    private final KisApiClient  kisApiClient;
    private final KisProperties kisProperties;
    private final ConcurrentHashMap<String, CachedQuote> cache = new ConcurrentHashMap<>();

    public QuoteCacheService(KisApiClient kisApiClient, KisProperties kisProperties) {
        this.kisApiClient  = kisApiClient;
        this.kisProperties = kisProperties;
    }

    public boolean isConfigured() {
        return kisProperties.isConfigured();
    }

    /** 현재가 스냅샷 (2초 캐시). 미설정/응답 없음이면 예외. */
    public Quote fetch(String stockCode) {
        CachedQuote cached = cache.get(stockCode);
        if (cached != null && !cached.isStale()) return cached.quote();

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
        PriceOutput o = resp.output();
        Quote quote = new Quote(
                o.stockName(),
                parseLong(o.current()),
                parseLong(o.changeAmount()),
                parseDouble(o.changeRate()),
                parseLong(o.volume()));
        cache.put(stockCode, new CachedQuote(quote, System.currentTimeMillis()));
        return quote;
    }

    public record Quote(String stockName, long currentPrice, long changeAmount,
                        double changeRate, long volume) {}

    private record CachedQuote(Quote quote, long timestamp) {
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
