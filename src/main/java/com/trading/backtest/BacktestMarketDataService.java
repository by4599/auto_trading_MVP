package com.trading.backtest;

import com.trading.market.Candle;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.MarketDataService;
import com.trading.market.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * candle_history 재생 MarketDataService (B-2, 설계 문서 §2.1).
 *
 * 선견 편향 차단이 유일한 존재 이유다:
 *   - getRecentCandles: [전일 완결 일봉, 시뮬 당일봉] — 당일봉의 close는 러너가
 *     설정한 "시뮬 현재가"이고, high/low도 시가~시뮬가 범위로 잘라 미래를 감춘다.
 *   - getDailyCandles: 시뮬 일자 **이전**의 완결 일봉만 반환 (ATR 등이 당일을 못 본다).
 */
@Service
@Profile("backtest")
public class BacktestMarketDataService implements MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(BacktestMarketDataService.class);

    /**
     * 재생 시작일 이전 완결 봉 여유 — ATR(14)은 며칠이면 충분하지만, 방식2
     * MA돌파(MovingAverageBreakoutStrategy)의 120일 이동평균 워밍업이 지배적이다.
     * MA120 ≈ 거래일 120일 ≈ 달력일 170일 안팎이라, 여유 없이 45일만 두면
     * Walk-Forward 검증 구간(3개월)이 시작 전부터 끝까지 MA120을 한 번도 채우지
     * 못해 방식2 신호가 전혀 나지 않는 결함이 있었다(BACKTEST-DESIGN §13). 260일로
     * 넉넉히 잡아 거래일 180일 안팎을 확보한다. CandleBackfillService.rangeFrom()도
     * 이 상수를 그대로 참조하므로 백필 범위도 함께 늘어난다.
     */
    static final int WARMUP_CALENDAR_DAYS = 260;

    private final CandleHistoryRepository repository;

    private final Map<String, NavigableMap<LocalDate, Candle>> series = new ConcurrentHashMap<>();
    private final Map<String, Double> simPrices = new ConcurrentHashMap<>();
    private volatile LocalDate simDate;

    public BacktestMarketDataService(CandleHistoryRepository repository) {
        this.repository = repository;
    }

    // ── 재생 준비 ─────────────────────────────────────────────────────────────

    /** 종목별 일봉 시리즈를 메모리에 적재한다 (워밍업 여유 포함). */
    public void loadSeries(List<String> stockCodes, LocalDate from, LocalDate to) {
        series.clear();
        for (String code : stockCodes) {
            NavigableMap<LocalDate, Candle> map = new TreeMap<>();
            repository.findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                            code, Timeframe.DAILY, from.minusDays(WARMUP_CALENDAR_DAYS), to)
                    .forEach(h -> map.put(h.getCandleDate(), h.toCandle()));
            series.put(code, map);
            log.info("[BacktestMarket] {} 일봉 적재: {}건", code, map.size());
        }
    }

    /** 적재된 전 종목의 거래일 합집합 (재생 루프의 축) */
    public List<LocalDate> tradingDates(LocalDate from, LocalDate to) {
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (NavigableMap<LocalDate, Candle> map : series.values()) {
            dates.addAll(map.subMap(from, true, to, true).keySet());
        }
        return List.copyOf(dates);
    }

    // ── 시뮬 상태 (러너/시뮬레이터가 조작) ────────────────────────────────────

    public void setSimDate(LocalDate date) {
        this.simDate = date;
    }

    public void setSimPrice(String stockCode, double price) {
        simPrices.put(stockCode, price);
    }

    public void clearSimPrices() {
        simPrices.clear();
    }

    /** 해당 종목·일자의 (미래 포함) 원본 일봉 — 시뮬레이터 전용. 전략에 노출 금지. */
    public Candle dayBar(String stockCode, LocalDate date) {
        NavigableMap<LocalDate, Candle> map = series.get(stockCode);
        return map == null ? null : map.get(date);
    }

    /** 전일(시뮬 일자 직전 거래일) 완결 일봉 */
    public Candle previousCandle(String stockCode, LocalDate date) {
        NavigableMap<LocalDate, Candle> map = series.get(stockCode);
        if (map == null) return null;
        Map.Entry<LocalDate, Candle> e = map.lowerEntry(date);
        return e == null ? null : e.getValue();
    }

    /** 평가용 현재가: 시뮬가 우선, 없으면 시뮬 일자 이전·당일의 마지막 종가 */
    public double currentPrice(String stockCode) {
        Double sim = simPrices.get(stockCode);
        if (sim != null) return sim;
        NavigableMap<LocalDate, Candle> map = series.get(stockCode);
        if (map == null || simDate == null) return 0.0;
        Map.Entry<LocalDate, Candle> e = map.floorEntry(simDate);
        return e == null ? 0.0 : e.getValue().getClose();
    }

    // ── MarketDataService 구현 ────────────────────────────────────────────────

    @Override
    public List<Candle> getRecentCandles(String stockCode) {
        Candle yesterday = previousCandle(stockCode, requireSimDate());
        Candle todayBar  = dayBar(stockCode, simDate);
        Double simPrice  = simPrices.get(stockCode);
        if (yesterday == null || todayBar == null || simPrice == null) {
            throw new IllegalStateException(String.format(
                    "시뮬 캔들 불가: code=%s simDate=%s (전일=%s 당일=%s 시뮬가=%s)",
                    stockCode, simDate, yesterday, todayBar, simPrice));
        }
        // 당일봉은 "시뮬 시점까지 알 수 있는 것"만 담는다 — close=시뮬 현재가,
        // high/low는 시가~시뮬가 범위로 절단 (실제 고저는 미래 정보)
        Candle simToday = new Candle(simDate,
                todayBar.getOpen(),
                Math.max(todayBar.getOpen(), simPrice),
                Math.min(todayBar.getOpen(), simPrice),
                simPrice,
                todayBar.volume());
        return List.of(yesterday, simToday);
    }

    @Override
    public List<Candle> getDailyCandles(String stockCode, int days) {
        NavigableMap<LocalDate, Candle> map = series.get(stockCode);
        if (map == null) {
            throw new IllegalStateException("시리즈 미적재: " + stockCode);
        }
        List<Candle> before = new ArrayList<>(
                map.headMap(requireSimDate(), false).values());
        if (before.size() <= days) return List.copyOf(before);
        return List.copyOf(before.subList(before.size() - days, before.size()));
    }

    private LocalDate requireSimDate() {
        if (simDate == null) throw new IllegalStateException("simDate 미설정");
        return simDate;
    }
}
