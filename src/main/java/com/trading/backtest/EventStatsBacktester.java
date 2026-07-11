package com.trading.backtest;

import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.Timeframe;
import com.trading.research.DisclosureItem;
import com.trading.research.DisclosureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 이벤트 유형별 과거 주가 반응 통계 (B-4, 설계 S3 / 백테스트 §3.2).
 *
 * 선견 편향 방지: 공시 시각이 불명(rcept_dt는 날짜만)이므로 진입은 항상
 * **공시일 다음 거래일 시가**로 잡는다 — 장 마감 후 공시를 당일 진입으로
 * 계산하는 오류를 구조적으로 차단하는 보수적 관례.
 *
 * 기대수익 대표값은 평균이 아니라 **중앙값과 하위 25%** (§3.2 — 평균은 소수 대박이 왜곡).
 */
@Component
public class EventStatsBacktester {

    private static final Logger log = LoggerFactory.getLogger(EventStatsBacktester.class);

    public static final List<Integer> HORIZONS = List.of(1, 5, 10, 20);

    private final DisclosureRepository disclosureRepository;
    private final CandleHistoryRepository candleHistoryRepository;

    public EventStatsBacktester(DisclosureRepository disclosureRepository,
                                CandleHistoryRepository candleHistoryRepository) {
        this.disclosureRepository = disclosureRepository;
        this.candleHistoryRepository = candleHistoryRepository;
    }

    /** @param symbols candle_history가 적재된 종목만 (수집 대상과 정합 필수) */
    public List<EventStat> compute(List<String> symbols, LocalDate from, LocalDate to) {
        // 종목별 일봉 시계열 캐시 (날짜 오름차순)
        Map<String, List<CandleHistory>> candlesBySymbol = new HashMap<>();
        for (String symbol : symbols) {
            candlesBySymbol.put(symbol, candleHistoryRepository
                    .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                            symbol, Timeframe.DAILY, from, to.plusDays(40))); // D+20 여유
        }

        // 유형별 horizon별 수익률 수집
        Map<String, Map<Integer, List<Double>>> returnsByType = new TreeMap<>();
        int usable = 0, skipped = 0;

        for (DisclosureItem d : disclosureRepository.findAll()) {
            if (d.getEventType() == null) continue;
            List<CandleHistory> candles = candlesBySymbol.get(d.getStockCode());
            if (candles == null || candles.isEmpty()) { skipped++; continue; }

            int entryIdx = firstIndexAfter(candles, d.getDisclosedAt());
            if (entryIdx < 0) { skipped++; continue; }
            double entryPrice = candles.get(entryIdx).getOpen();
            if (entryPrice <= 0) { skipped++; continue; }

            Map<Integer, List<Double>> horizons = returnsByType
                    .computeIfAbsent(d.getEventType(), k -> new LinkedHashMap<>());
            boolean counted = false;
            for (int h : HORIZONS) {
                int exitIdx = entryIdx + h;
                if (exitIdx >= candles.size()) continue;  // 표본 기간 끝 — 해당 horizon만 제외
                double ret = candles.get(exitIdx).getClose() / entryPrice - 1.0;
                horizons.computeIfAbsent(h, k -> new ArrayList<>()).add(ret);
                counted = true;
            }
            if (counted) usable++; else skipped++;
        }

        log.info("[EventStats] 이벤트 표본: 사용 {}건 / 제외 {}건 (캔들 없음·기간 밖)", usable, skipped);

        List<EventStat> stats = new ArrayList<>();
        returnsByType.forEach((type, horizons) -> {
            List<Double> d5 = horizons.getOrDefault(5, List.of());
            long wins = d5.stream().filter(r -> r > 0).count();
            Map<Integer, Quantiles> quantiles = new LinkedHashMap<>();
            horizons.forEach((h, rets) -> quantiles.put(h, Quantiles.of(rets)));
            stats.add(new EventStat(type,
                    horizons.values().stream().mapToInt(List::size).max().orElse(0),
                    d5.isEmpty() ? 0.0 : (double) wins / d5.size(),
                    quantiles));
        });
        return stats;
    }

    /** 공시일 이후 첫 거래일 캔들 인덱스 (다음 거래일 시가 진입 — 선견 편향 차단) */
    private static int firstIndexAfter(List<CandleHistory> candles, LocalDate disclosedAt) {
        for (int i = 0; i < candles.size(); i++) {
            if (candles.get(i).getCandleDate().isAfter(disclosedAt)) return i;
        }
        return -1;
    }

    // ── 결과 타입 ─────────────────────────────────────────────────────────────

    public record EventStat(String eventType, int samples, double winRateD5,
                            Map<Integer, Quantiles> horizons) {
        public Quantiles at(int horizon) {
            return horizons.getOrDefault(horizon, Quantiles.EMPTY);
        }
    }

    public record Quantiles(double median, double p25, double mean, int n) {
        static final Quantiles EMPTY = new Quantiles(0, 0, 0, 0);

        static Quantiles of(List<Double> values) {
            if (values.isEmpty()) return EMPTY;
            List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            return new Quantiles(percentile(sorted, 0.50), percentile(sorted, 0.25), mean, sorted.size());
        }

        /** 최근접 순위(nearest-rank) 백분위 */
        private static double percentile(List<Double> sorted, double p) {
            int idx = (int) Math.ceil(p * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
        }
    }
}
