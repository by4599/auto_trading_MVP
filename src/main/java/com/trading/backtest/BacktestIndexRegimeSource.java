package com.trading.backtest;

import com.trading.market.Candle;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.Timeframe;
import com.trading.risk.IndexRegimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 백테스트 지수 레짐 데이터원 — candle_history의 KOSPI 일봉으로
 * "당일 시가 &lt; 전일 종가"(갭다운)를 판정한다. 시가는 개장 직후 확정되는
 * 정보이므로 일봉 재생에서도 선견 편향이 없다.
 *
 * <p>여기에 §14.4 실험용 <b>장기 추세</b> 판정({@link #isBelowTrend(int)})이 추가됐다.
 * 갭다운(하루)과 추세(수개월)는 다른 질문이며 서로 간섭하지 않는다.
 */
@Component
@Profile("backtest")
public class BacktestIndexRegimeSource implements IndexRegimeSource {

    private static final Logger log = LoggerFactory.getLogger(BacktestIndexRegimeSource.class);

    /** 지수 종가 캐시 적재 범위 — candle_history에 있는 지수 일봉 전부를 받는 넉넉한 경계 */
    private static final LocalDate INDEX_LOAD_FROM = LocalDate.of(1990, 1, 1);
    private static final LocalDate INDEX_LOAD_TO   = LocalDate.of(2999, 12, 31);

    private final BacktestMarketDataService market;
    private final BacktestDataProperties properties;
    private final CandleHistoryRepository candleHistoryRepository;
    private final Clock clock;

    /** 지수 종가 시계열 캐시 (일자 → 종가). 런마다 재조회하면 수백만 쿼리가 된다 */
    private volatile NavigableMap<LocalDate, Double> indexCloses;

    public BacktestIndexRegimeSource(BacktestMarketDataService market,
                                     BacktestDataProperties properties,
                                     CandleHistoryRepository candleHistoryRepository,
                                     Clock clock) {
        this.market = market;
        this.properties = properties;
        this.candleHistoryRepository = candleHistoryRepository;
        this.clock = clock;
    }

    @Override
    public Optional<Boolean> isBearishRegime() {
        LocalDate simDate = LocalDate.now(clock);
        String kospi = properties.getKospiStorageCode();
        Candle today = market.dayBar(kospi, simDate);
        Candle prev  = market.previousCandle(kospi, simDate);
        if (today == null || prev == null) return Optional.empty();
        return Optional.of(today.getOpen() < prev.getClose());
    }

    /**
     * 지수가 하락 추세인가 — <b>전일 종가</b>가 <b>전일까지의</b> N거래일 이동평균 아래면 true.
     *
     * <p>재생 중인 시뮬 일자(당일)의 봉은 절대 쓰지 않는다. 진입 판단은 장중(10:00 근사)에
     * 일어나므로 당일 종가와 당일 종가를 포함한 이동평균은 미래 정보이기 때문이다.
     * 표본이 maPeriod 미만이면 empty(= 판단 불가 → 차단하지 않음).
     */
    @Override
    public Optional<Boolean> isBelowTrend(int maPeriod) {
        return belowTrend(indexCloses(), LocalDate.now(clock), maPeriod);
    }

    /**
     * 순수 판정 로직 (테스트 대상) — {@code closes}에서 <b>simDate 미만</b>의 종가만 잘라
     * 마지막 maPeriod개로 이동평균을 내고, 그 구간의 마지막 종가(= 전일 종가)와 비교한다.
     *
     * <p>경계: 전일 종가 == 이동평균이면 "아래"가 아니다(false).
     */
    static Optional<Boolean> belowTrend(NavigableMap<LocalDate, Double> closes,
                                        LocalDate simDate, int maPeriod) {
        if (closes == null || simDate == null || maPeriod <= 0) return Optional.empty();
        // headMap(simDate, false) — 당일을 포함하지 않는다. 이 한 줄이 선견편향 차단선이다.
        List<Double> past = new ArrayList<>(closes.headMap(simDate, false).values());
        if (past.size() < maPeriod) return Optional.empty();

        List<Double> window = past.subList(past.size() - maPeriod, past.size());
        double sum = 0;
        for (double close : window) sum += close;
        double movingAverage = sum / maPeriod;
        double previousClose = window.get(window.size() - 1);
        return Optional.of(previousClose < movingAverage);
    }

    /**
     * 지수 종가 시계열을 candle_history에서 한 번만 읽어 캐시한다.
     *
     * <p>{@code BacktestMarketDataService.loadSeries()}는 <b>매매 대상 종목만</b> 메모리에
     * 올리며 지수(KOSPI)는 거기에 포함되지 않는다 — 지수를 넣으면 러너가 지수를 종목처럼
     * 매매하려 든다. 그래서 지수 시계열은 다른 백테스터(EventStats·Spillover·LowVolCrash)와
     * 같이 리포지토리에서 직접 읽는다. 백필은 오케스트레이터가 랩 실행 전에 끝내므로
     * 이 캐시는 실행 중 변하지 않는다(결정성 유지).
     */
    private NavigableMap<LocalDate, Double> indexCloses() {
        NavigableMap<LocalDate, Double> cached = indexCloses;
        if (cached != null) return cached;

        String kospi = properties.getKospiStorageCode();
        TreeMap<LocalDate, Double> loaded = new TreeMap<>();
        candleHistoryRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        kospi, Timeframe.DAILY, INDEX_LOAD_FROM, INDEX_LOAD_TO)
                .forEach(h -> loaded.put(h.getCandleDate(), h.toCandle().getClose()));
        log.info("[IndexTrend] 지수 추세 판정용 {} 일봉 캐시 적재: {}건{}", kospi, loaded.size(),
                loaded.isEmpty() ? " — 데이터 없음(추세 판정 불가 → 필터가 아무것도 막지 않는다)"
                        : String.format(" (%s ~ %s)", loaded.firstKey(), loaded.lastKey()));
        cached = Collections.unmodifiableNavigableMap(loaded);
        indexCloses = cached;
        return cached;
    }
}
