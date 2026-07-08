package com.trading.market;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 변동성 돌파 전략용 MarketDataService 구현체.
 *
 * getRecentCandles()는 정확히 2개의 캔들을 반환한다:
 *   [0] 전일 일봉 (FHKST03010100) — 전략이 range = high - low 계산에 사용
 *   [1] 당일 라이브 캔들 (FHKST01010100) — 전략이 open / close(현재가) 비교에 사용
 *
 * VolatilityBreakoutStrategy는 candles.get(size-2) / candles.get(size-1)을
 * yesterday / today로 다루므로 이 2-element 리스트와 정확히 맞는다.
 */
@Service
@Profile("paper")
public class KisMarketDataService implements MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(KisMarketDataService.class);

    private static final String TR_DAILY_CHART = "FHKST03010100";  // 일봉 차트
    private static final String TR_CURRENT_PRICE = "FHKST01010100"; // 현재가
    private static final String MARKET_CODE = "J";                   // 주식

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final KisApiClient kisApiClient;

    public KisMarketDataService(KisApiClient kisApiClient) {
        this.kisApiClient = kisApiClient;
    }

    @Override
    public List<Candle> getRecentCandles(String stockCode) {
        Candle yesterday = fetchYesterdayCandle(stockCode);
        Candle today     = fetchTodayLiveCandle(stockCode);
        log.debug("캔들 조회 완료: {} / yesterday.high={} yesterday.low={} today.open={} today.close={}",
                stockCode, yesterday.getHigh(), yesterday.getLow(), today.getOpen(), today.getClose());
        return List.of(yesterday, today);
    }

    // ── 전일 일봉 ─────────────────────────────────────────────────────────────

    /**
     * 일봉 차트 API로 직전 완성 거래일 캔들을 반환한다.
     *
     * FID_INPUT_DATE_2 = 오늘로 설정 → 장 중에는 output2[0]이 오늘(미완성)일 수 있음.
     * 따라서 output2[0]의 날짜가 오늘이면 건너뛰고 output2[1]을 "전일 캔들"로 사용한다.
     * 월요일 · 공휴일처럼 어제가 휴장일인 경우에도 API가 직전 거래일을 채워주므로
     * "오늘인지 아닌지" 검사만으로 안전하게 최근 완성 거래일을 얻을 수 있다.
     */
    private Candle fetchYesterdayCandle(String stockCode) {
        String today      = LocalDate.now().format(DATE_FMT);
        String tenDaysAgo = LocalDate.now().minusDays(10).format(DATE_FMT);

        DailyChartResponse resp = kisApiClient.getClient().get()
                .uri(b -> b.path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", MARKET_CODE)
                        .queryParam("FID_INPUT_ISCD",         stockCode)
                        .queryParam("FID_INPUT_DATE_1",       tenDaysAgo)
                        .queryParam("FID_INPUT_DATE_2",       today)
                        .queryParam("FID_PERIOD_DIV_CODE",    "D")
                        .queryParam("FID_ORG_ADJ_PRC",        "1")
                        .build())
                .header("tr_id",    TR_DAILY_CHART)
                .header("custtype", "P")
                .retrieve()
                .body(DailyChartResponse.class);

        if (resp == null || resp.output2() == null || resp.output2().size() < 2) {
            throw new IllegalStateException("전일 일봉 없음: stockCode=" + stockCode);
        }

        // output2[0]이 오늘(장 중 미완성)이면 건너뛴다
        int idx = today.equals(resp.output2().get(0).date()) ? 1 : 0;
        DailyData d = resp.output2().get(idx);
        return new Candle(
                LocalDate.parse(d.date(), DATE_FMT),
                parseDouble(d.open()),
                parseDouble(d.high()),
                parseDouble(d.low()),
                parseDouble(d.close()),
                parseLong(d.volume())
        );
    }

    // ── 당일 라이브 캔들 ──────────────────────────────────────────────────────

    /**
     * 현재가 API로 오늘의 시가·고가·저가·현재가를 가져와 Candle을 구성한다.
     * close 자리에 현재가(stck_prpr)를 넣으므로 전략의 breakout 비교가 실시간으로 동작한다.
     */
    private Candle fetchTodayLiveCandle(String stockCode) {
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
            throw new IllegalStateException("당일 현재가 없음: stockCode=" + stockCode);
        }

        PriceOutput o = resp.output();
        return new Candle(
                LocalDate.now(),
                parseDouble(o.open()),
                parseDouble(o.high()),
                parseDouble(o.low()),
                parseDouble(o.current()),  // 현재가 → close
                parseLong(o.volume())
        );
    }

    // ── 파싱 헬퍼 ─────────────────────────────────────────────────────────────

    private static double parseDouble(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            log.warn("숫자 파싱 실패 (0.0 반환): '{}'", s);
            return 0.0;
        }
    }

    private static long parseLong(String s) {
        if (s == null || s.isBlank()) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            log.warn("정수 파싱 실패 (0 반환): '{}'", s);
            return 0L;
        }
    }

    // ── KIS 응답 DTO ──────────────────────────────────────────────────────────

    private record DailyChartResponse(
            @JsonProperty("output2") List<DailyData> output2
    ) {}

    private record DailyData(
            @JsonProperty("stck_bsop_date") String date,
            @JsonProperty("stck_oprc")      String open,
            @JsonProperty("stck_hgpr")      String high,
            @JsonProperty("stck_lwpr")      String low,
            @JsonProperty("stck_clpr")      String close,
            @JsonProperty("acml_vol")       String volume
    ) {}

    private record PriceResponse(
            @JsonProperty("output") PriceOutput output
    ) {}

    private record PriceOutput(
            @JsonProperty("stck_oprc") String open,
            @JsonProperty("stck_hgpr") String high,
            @JsonProperty("stck_lwpr") String low,
            @JsonProperty("stck_prpr") String current,  // 현재가
            @JsonProperty("acml_vol")  String volume
    ) {}
}
