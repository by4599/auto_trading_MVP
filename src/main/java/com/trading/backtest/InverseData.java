package com.trading.backtest;

import com.trading.market.Candle;
import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryClient;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 63_quant 데이터 — 인버스(114800) 일봉·KOSPI 종가 읽기, 합성 −1배 만들기, 114800 한 종목만 수취.
 *
 * <p>수취({@link #fetchInverse})는 {@code bear-inverse-fetch} 모드에서만 부른다. 장 시간(평일 08:30~15:40 KST)에는 거부한다 —
 * 모의 앱과 KIS 계좌 한도(초당 1건)를 나눠 쓰기 때문이다. 시뮬 시계(MutableClock)가 아니라 실제 KST 시각으로 판단한다.
 */
@Component
@Profile("backtest")
public class InverseData {

    private static final Logger log = LoggerFactory.getLogger(InverseData.class);
    private static final LocalDate LOAD_FROM = LocalDate.of(1990, 1, 1);
    private static final LocalDate LOAD_TO = LocalDate.of(2999, 12, 31);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final LocalTime BLOCK_FROM = LocalTime.of(8, 30);
    static final LocalTime BLOCK_TO = LocalTime.of(15, 40);
    /** 합성 −1배의 연 보수 (KODEX 인버스 총보수 근사) */
    static final double SYNTH_ANNUAL_FEE = 0.0064;

    private final CandleHistoryRepository repository;
    private final CandleHistoryClient candleClient;
    private final BacktestDataProperties properties;

    public InverseData(CandleHistoryRepository repository, CandleHistoryClient candleClient,
                       BacktestDataProperties properties) {
        this.repository = repository;
        this.candleClient = candleClient;
        this.properties = properties;
    }

    Map<LocalDate, InverseOverlay.Bar> realBars() {
        Map<LocalDate, InverseOverlay.Bar> bars = new TreeMap<>();
        load(InverseOverlay.CODE).forEach(h -> bars.put(h.getCandleDate(),
                new InverseOverlay.Bar(h.getCandleDate(), h.getOpen(), h.getHigh(), h.getLow(), h.getClose())));
        return bars;
    }

    Map<LocalDate, InverseOverlay.Bar> syntheticBars() {
        List<InverseOverlay.Bar> kospi = load(properties.getKospiStorageCode()).stream()
                .map(h -> new InverseOverlay.Bar(h.getCandleDate(), h.getOpen(), h.getHigh(), h.getLow(), h.getClose()))
                .toList();
        Map<LocalDate, InverseOverlay.Bar> bars = new TreeMap<>();
        synthetic(kospi, 10_000).forEach(b -> bars.put(b.date(), b));
        return bars;
    }

    NavigableMap<LocalDate, Double> kospiCloses() {
        TreeMap<LocalDate, Double> closes = new TreeMap<>();
        load(properties.getKospiStorageCode()).forEach(h -> closes.put(h.getCandleDate(), h.getClose()));
        return closes;
    }

    /**
     * KOSPI 일봉 → −1배 일봉. 전일 합성 종가를 기준으로 지수의 전일 종가 대비 수익률을 뒤집는다(고가↔저가도 뒤집힘).
     * 보수는 종가에만 일할(÷252) 차감한다. 괴리·복리 외 오차는 무시(한계).
     */
    static List<InverseOverlay.Bar> synthetic(List<InverseOverlay.Bar> index, double start) {
        if (index.isEmpty()) return List.of();
        double dailyFee = SYNTH_ANNUAL_FEE / 252;
        List<InverseOverlay.Bar> out = new java.util.ArrayList<>();
        out.add(new InverseOverlay.Bar(index.get(0).date(), start, start, start, start));
        double prevSynth = start;
        for (int i = 1; i < index.size(); i++) {
            double k = index.get(i - 1).close();
            InverseOverlay.Bar b = index.get(i);
            double close = prevSynth * (1 - (b.close() / k - 1)) * (1 - dailyFee);
            out.add(new InverseOverlay.Bar(b.date(), prevSynth * (1 - (b.open() / k - 1)),
                    prevSynth * (1 - (b.low() / k - 1)), prevSynth * (1 - (b.high() / k - 1)), close));
            prevSynth = close;
        }
        return List.copyOf(out);
    }

    /** 114800 일봉 [from, to] 중 저장 안 된 날만 수취·저장. 장 시간이면 예외 */
    int fetchInverse(LocalDate from, LocalDate to) {
        ZonedDateTime now = ZonedDateTime.now(KST);
        if (isMarketHours(now)) {
            throw new IllegalStateException("장 시간(" + now + ")에는 KIS를 부르지 않는다 — 15:40 이후에 다시 실행");
        }
        Set<LocalDate> have = load(InverseOverlay.CODE).stream().map(CandleHistory::getCandleDate).collect(Collectors.toSet());
        List<Candle> fetched = candleClient.fetchDailyCandles(InverseOverlay.CODE, from, to);
        List<CandleHistory> rows = fetched.stream().filter(c -> !have.contains(c.date()))
                .map(c -> CandleHistory.ofDaily(InverseOverlay.CODE, c)).toList();
        repository.saveAll(rows);
        log.info("[BearInv] 114800 수취 {}건 · 신규 저장 {}건 ({}~{})", fetched.size(), rows.size(), from, to);
        return rows.size();
    }

    static boolean isMarketHours(ZonedDateTime kst) {
        DayOfWeek d = kst.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return false;
        LocalTime t = kst.toLocalTime();
        return !t.isBefore(BLOCK_FROM) && t.isBefore(BLOCK_TO);
    }

    private List<CandleHistory> load(String code) {
        return repository.findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                code, Timeframe.DAILY, LOAD_FROM, LOAD_TO);
    }
}
