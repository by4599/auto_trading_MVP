package com.trading.market;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
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

    /** 일봉 이어 받기 상한 — KIS 일봉 차트는 호출당 약 100행이라 5페이지면 약 500봉(폭주 방어) */
    private static final int MAX_DAILY_PAGES = 5;

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
        String today = LocalDate.now().format(DATE_FMT);
        DailyChartResponse resp = fetchDailyChart(stockCode, LocalDate.now().minusDays(10));

        if (resp == null || resp.output2() == null || resp.output2().size() < 2) {
            throw new IllegalStateException("전일 일봉 없음: stockCode=" + stockCode);
        }

        // output2[0]이 오늘(장 중 미완성)이면 건너뛴다
        int idx = today.equals(resp.output2().get(0).date()) ? 1 : 0;
        return toCandle(resp.output2().get(idx));
    }

    /**
     * 최근 완성 일봉 N개 (과거→최신). 당일 미완성 봉 제외.
     * 조회 범위는 휴장일 여유를 두고 N×2+10일을 잡는다.
     *
     * <p>KIS 일봉 차트는 호출당 약 100행이 상한이다(KisCandleHistoryClient와 같은 TR). 첫 응답으로
     * N개가 차지 않으면 가장 오래된 행의 전날을 새 끝 날짜로 삼아 이어 받는다. 이게 없던 동안
     * 125봉을 요구하는 전략(이평 정배열·돈치안의 MA120)은 100봉 미만을 받아 paper에서 한 번도
     * 신호를 내지 못했다(2026-07-04~ 90일간 이평돌파 칸 매매 0건 — VB 28건·스캘핑 72건과 대조).
     * 첫 페이지로 충분한 요청(ATR 15봉 등)은 예전과 똑같이 1회만 호출한다.
     */
    @Override
    public List<Candle> getDailyCandles(String stockCode, int days) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays((long) days * 2 + 10);
        DailyChartResponse first = fetchDailyChart(stockCode, from, today);

        if (first == null || first.output2() == null || first.output2().isEmpty()) {
            throw new IllegalStateException("일봉 조회 실패: stockCode=" + stockCode);
        }

        // KIS 응답은 최신→과거 순 — 당일을 건너뛰고 N개 수집 후 과거→최신으로 뒤집는다
        List<Candle> newestFirst = new ArrayList<>();
        LocalDate oldest = collectCompleted(first.output2(), today, days, newestFirst);
        int pages = 1;
        while (newestFirst.size() < days && oldest != null && oldest.isAfter(from)
                && pages < MAX_DAILY_PAGES) {
            DailyChartResponse next = fetchDailyChart(stockCode, from, oldest.minusDays(1));
            pages++;
            if (next == null || next.output2() == null || next.output2().isEmpty()) break;
            LocalDate nextOldest = collectCompleted(next.output2(), today, days, newestFirst);
            if (nextOldest == null || !nextOldest.isBefore(oldest)) break; // 진전 없음 — 같은 창 반복 방어
            oldest = nextOldest;
        }
        if (pages > 1) {
            log.info("[MarketData] {} 일봉 {}봉 수취 (요청 {}봉, 페이지 {}회)",
                    stockCode, newestFirst.size(), days, pages);
        }
        Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    /**
     * 한 페이지(최신→과거)에서 완성 봉을 이어 담는다 — 오늘(미완성) 봉과 이미 담은 날짜 이후는 건너뛴다.
     *
     * @return 이 페이지의 가장 오래된 날짜(다음 커서 기준). 날짜 있는 행이 없으면 null
     */
    private static LocalDate collectCompleted(List<DailyData> rows, LocalDate today, int days,
                                              List<Candle> newestFirst) {
        LocalDate oldestInPage = null;
        for (DailyData d : rows) {
            if (d.date() == null || d.date().isBlank()) continue;
            LocalDate date = LocalDate.parse(d.date(), DATE_FMT);
            oldestInPage = date;
            if (!date.isBefore(today) || newestFirst.size() >= days) continue;
            LocalDate lastAdded = newestFirst.isEmpty() ? null : newestFirst.get(newestFirst.size() - 1).date();
            if (lastAdded != null && !date.isBefore(lastAdded)) continue; // 페이지 경계 중복 방어
            newestFirst.add(toCandle(d));
        }
        return oldestInPage;
    }

    private DailyChartResponse fetchDailyChart(String stockCode, LocalDate from) {
        return fetchDailyChart(stockCode, from, LocalDate.now());
    }

    private DailyChartResponse fetchDailyChart(String stockCode, LocalDate from, LocalDate to) {
        String today    = to.format(DATE_FMT);
        String fromDate = from.format(DATE_FMT);

        return kisApiClient.getClient().get()
                .uri(b -> b.path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", MARKET_CODE)
                        .queryParam("FID_INPUT_ISCD",         stockCode)
                        .queryParam("FID_INPUT_DATE_1",       fromDate)
                        .queryParam("FID_INPUT_DATE_2",       today)
                        .queryParam("FID_PERIOD_DIV_CODE",    "D")
                        .queryParam("FID_ORG_ADJ_PRC",        "1")
                        .build())
                .header("tr_id",    TR_DAILY_CHART)
                .header("custtype", "P")
                .retrieve()
                .body(DailyChartResponse.class);
    }

    private static Candle toCandle(DailyData d) {
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
