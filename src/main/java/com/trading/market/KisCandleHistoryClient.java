package com.trading.market;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;
import java.util.List;

/**
 * KIS 과거 캔들 수취 구현체 (B-1).
 *
 * KIS 차트 TR은 호출당 약 100행이 상한이므로 날짜 커서를 과거로 옮기며 페이지네이션한다.
 * KIS 모의 레이트리밋(초당 2건, TradingScheduler 참고)을 넘지 않도록 호출 간
 * {@link #THROTTLE_MS} 만큼 쉰다 — 백필은 야간/1회성 배치라 지연은 문제가 아니다.
 */
@Component
public class KisCandleHistoryClient implements CandleHistoryClient {

    private static final Logger log = LoggerFactory.getLogger(KisCandleHistoryClient.class);

    private static final String TR_DAILY_CHART  = "FHKST03010100"; // 종목 기간 일봉
    private static final String TR_INDEX_CHART  = "FHKUP03500100"; // 업종/지수 기간 일봉
    private static final String TR_MINUTE_CHART = "FHKST03010200"; // 당일 분봉

    private static final String MARKET_CODE_STOCK = "J";
    private static final String MARKET_CODE_INDEX = "U";

    private static final long THROTTLE_MS = 600;
    private static final int  MAX_PAGES   = 60; // 3년 일봉 ≈ 8페이지 — 폭주 방어 상한

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmmss");

    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);

    private final KisApiClient kisApiClient;

    public KisCandleHistoryClient(KisApiClient kisApiClient) {
        this.kisApiClient = kisApiClient;
    }

    // ── 종목 일봉 ─────────────────────────────────────────────────────────────

    @Override
    public List<Candle> fetchDailyCandles(String stockCode, LocalDate from, LocalDate to) {
        return fetchDailyPaged(stockCode, from, to, false);
    }

    @Override
    public List<Candle> fetchIndexDailyCandles(String indexCode, LocalDate from, LocalDate to) {
        return fetchDailyPaged(indexCode, from, to, true);
    }

    /**
     * 커서를 to에서 과거로 옮기며 [from, to] 전체를 수집한다.
     * 응답은 최신→과거 순이므로 마지막 행 날짜 - 1일을 다음 커서로 삼는다.
     */
    private List<Candle> fetchDailyPaged(String code, LocalDate from, LocalDate to, boolean index) {
        List<Candle> collected = new ArrayList<>();
        LocalDate cursor = to;

        for (int page = 0; page < MAX_PAGES && !cursor.isBefore(from); page++) {
            List<Candle> chunk = index
                    ? fetchIndexChartWindow(code, from, cursor)
                    : fetchStockChartWindow(code, from, cursor);
            if (chunk.isEmpty()) break;

            LocalDate oldest = chunk.get(chunk.size() - 1).date(); // 최신→과거 순
            for (Candle c : chunk) {
                if (!c.date().isBefore(from) && !c.date().isAfter(to)) {
                    collected.add(c);
                }
            }
            if (!oldest.isAfter(from)) break; // from까지 도달
            cursor = oldest.minusDays(1);
            throttle();
        }

        Collections.reverse(collected); // 과거→최신
        log.info("[CandleHistory] 일봉 수취: code={} {}~{} → {}건{}",
                code, from, to, collected.size(), index ? " (지수)" : "");
        return List.copyOf(collected);
    }

    private List<Candle> fetchStockChartWindow(String stockCode, LocalDate from, LocalDate toCursor) {
        ChartResponse resp = kisApiClient.getClient().get()
                .uri(b -> b.path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", MARKET_CODE_STOCK)
                        .queryParam("FID_INPUT_ISCD",         stockCode)
                        .queryParam("FID_INPUT_DATE_1",       from.format(DATE_FMT))
                        .queryParam("FID_INPUT_DATE_2",       toCursor.format(DATE_FMT))
                        .queryParam("FID_PERIOD_DIV_CODE",    "D")
                        .queryParam("FID_ORG_ADJ_PRC",        "1")
                        .build())
                .header("tr_id",    TR_DAILY_CHART)
                .header("custtype", "P")
                .retrieve()
                .body(ChartResponse.class);

        if (resp == null || resp.output2() == null) return List.of();
        return resp.output2().stream()
                .filter(d -> d.date() != null && !d.date().isBlank())
                .map(d -> new Candle(LocalDate.parse(d.date(), DATE_FMT),
                        parseDouble(d.open()), parseDouble(d.high()),
                        parseDouble(d.low()), parseDouble(d.close()),
                        parseLong(d.volume())))
                .toList();
    }

    private List<Candle> fetchIndexChartWindow(String indexCode, LocalDate from, LocalDate toCursor) {
        IndexChartResponse resp = kisApiClient.getClient().get()
                .uri(b -> b.path("/uapi/domestic-stock/v1/quotations/inquire-daily-indexchartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", MARKET_CODE_INDEX)
                        .queryParam("FID_INPUT_ISCD",         indexCode)
                        .queryParam("FID_INPUT_DATE_1",       from.format(DATE_FMT))
                        .queryParam("FID_INPUT_DATE_2",       toCursor.format(DATE_FMT))
                        .queryParam("FID_PERIOD_DIV_CODE",    "D")
                        .build())
                .header("tr_id",    TR_INDEX_CHART)
                .header("custtype", "P")
                .retrieve()
                .body(IndexChartResponse.class);

        if (resp == null || resp.output2() == null) return List.of();
        return resp.output2().stream()
                .filter(d -> d.date() != null && !d.date().isBlank())
                .map(d -> new Candle(LocalDate.parse(d.date(), DATE_FMT),
                        parseDouble(d.open()), parseDouble(d.high()),
                        parseDouble(d.low()), parseDouble(d.close()),
                        parseLong(d.volume())))
                .toList();
    }

    // ── 당일 분봉 ─────────────────────────────────────────────────────────────

    /**
     * 당일 분봉을 시간 커서 역순 페이지네이션(호출당 ~30행)으로 전부 수집한다.
     *
     * 종료 조건은 세 가지다: 빈 응답 / 09:00 도달 / <b>새 분이 하나도 없는 페이지</b>.
     * 셋째가 핵심인데, 커서가 장 시작 아래로 내려가면 KIS가 같은 창을 계속 되돌려주기
     * 때문이다(2026-08-04 실측: 전 종목이 상한 60페이지를 소진해 고유 391분을 1800행으로 받았다).
     */
    @Override
    public List<MinuteCandle> fetchTodayMinuteCandles(String stockCode) {
        Map<LocalTime, MinuteCandle> byTime = new TreeMap<>(); // 과거→최신 정렬 + 분 단위 중복 제거
        LocalTime cursor = LocalTime.of(15, 30);
        int pagesUsed = 0;
        boolean exhaustedPages = true;

        for (int page = 0; page < MAX_PAGES; page++) {
            pagesUsed = page + 1;
            List<MinuteData> chunk = fetchMinuteWindow(stockCode, cursor);
            if (chunk.isEmpty()) { exhaustedPages = false; break; }

            if (!accumulatePage(chunk, MARKET_OPEN, byTime)) {
                exhaustedPages = false; // 새 분이 없다 = 커서를 더 내려도 같은 창만 돌아온다
                break;
            }
            LocalTime oldest = LocalTime.parse(chunk.get(chunk.size() - 1).time(), TIME_FMT);
            if (!oldest.isAfter(MARKET_OPEN)) { exhaustedPages = false; break; }
            cursor = oldest.minusMinutes(1);
            throttle();
        }

        List<MinuteCandle> collected = List.copyOf(byTime.values());
        log.info("[CandleHistory] 당일 분봉 수취: code={} → {}분 (페이지 {}회)",
                stockCode, collected.size(), pagesUsed);
        if (exhaustedPages) {
            log.warn("[CandleHistory] ⚠ {} 분봉이 페이지 상한({})까지 소진됐다 — 종료 조건 미동작 의심, "
                    + "당일 일부만 수취했을 수 있음", stockCode, MAX_PAGES);
        }
        return collected;
    }

    /**
     * 한 페이지 수취분을 분 단위로 누적한다 (장 시작 이전 봉은 버린다).
     *
     * @return 새로 담긴 분이 하나라도 있으면 true. false면 KIS가 이미 받은 창을 다시 준 것이라
     *         커서를 더 내려도 소용이 없다 — 2026-08-04 실측에서 이 종료 조건이 없어 전 종목이
     *         페이지 상한(60)까지 같은 하루를 약 4.6회 반복 수취했다(1800행 = 고유 391분).
     */
    static boolean accumulatePage(List<MinuteData> chunk, LocalTime marketOpen,
                                  Map<LocalTime, MinuteCandle> into) {
        boolean added = false;
        for (MinuteData d : chunk) {
            LocalTime time = LocalTime.parse(d.time(), TIME_FMT);
            if (time.isBefore(marketOpen)) continue;
            MinuteCandle candle = new MinuteCandle(
                    LocalDate.parse(d.date(), DATE_FMT), time,
                    parseDouble(d.open()), parseDouble(d.high()),
                    parseDouble(d.low()), parseDouble(d.close()),
                    parseLong(d.volume()));
            if (into.putIfAbsent(time, candle) == null) {
                added = true;
            }
        }
        return added;
    }

    private List<MinuteData> fetchMinuteWindow(String stockCode, LocalTime cursor) {
        MinuteChartResponse resp = kisApiClient.getClient().get()
                .uri(b -> b.path("/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice")
                        .queryParam("FID_ETC_CLS_CODE",       "")
                        .queryParam("FID_COND_MRKT_DIV_CODE", MARKET_CODE_STOCK)
                        .queryParam("FID_INPUT_ISCD",         stockCode)
                        .queryParam("FID_INPUT_HOUR_1",       cursor.format(TIME_FMT))
                        .queryParam("FID_PW_DATA_INCU_YN",    "Y")
                        .build())
                .header("tr_id",    TR_MINUTE_CHART)
                .header("custtype", "P")
                .retrieve()
                .body(MinuteChartResponse.class);

        if (resp == null || resp.output2() == null) return List.of();
        return resp.output2().stream()
                .filter(d -> d.time() != null && !d.time().isBlank())
                .toList();
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private static void throttle() {
        try {
            Thread.sleep(THROTTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("캔들 수취 스로틀 중 인터럽트", e);
        }
    }

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

    private record ChartResponse(@JsonProperty("output2") List<DailyData> output2) {}

    private record DailyData(
            @JsonProperty("stck_bsop_date") String date,
            @JsonProperty("stck_oprc")      String open,
            @JsonProperty("stck_hgpr")      String high,
            @JsonProperty("stck_lwpr")      String low,
            @JsonProperty("stck_clpr")      String close,
            @JsonProperty("acml_vol")       String volume
    ) {}

    private record IndexChartResponse(@JsonProperty("output2") List<IndexDailyData> output2) {}

    private record IndexDailyData(
            @JsonProperty("stck_bsop_date")  String date,
            @JsonProperty("bstp_nmix_oprc")  String open,
            @JsonProperty("bstp_nmix_hgpr")  String high,
            @JsonProperty("bstp_nmix_lwpr")  String low,
            @JsonProperty("bstp_nmix_prpr")  String close,
            @JsonProperty("acml_vol")        String volume
    ) {}

    private record MinuteChartResponse(@JsonProperty("output2") List<MinuteData> output2) {}

    record MinuteData(
            @JsonProperty("stck_bsop_date") String date,
            @JsonProperty("stck_cntg_hour") String time,
            @JsonProperty("stck_oprc")      String open,
            @JsonProperty("stck_hgpr")      String high,
            @JsonProperty("stck_lwpr")      String low,
            @JsonProperty("stck_prpr")      String close,
            @JsonProperty("cntg_vol")       String volume
    ) {}
}
