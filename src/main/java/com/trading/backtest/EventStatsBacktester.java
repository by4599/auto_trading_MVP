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
 * 이벤트 유형별 과거 주가 반응 통계 (B-4 v2, 설계 S3 / 백테스트 §3.2, §8).
 *
 * v2 통계 교정 (1차 실행의 3중 오염 대응):
 *   ① 벤치마크 조정 — 수익률은 KOSPI 대비 **초과수익**으로 계산 (시장 베타 제거).
 *      KOSPI 캔들이 없으면 원수익률 폴백 + 경고.
 *   ② 클러스터 병합 — 같은 종목·유형의 이벤트가 5거래일 내에 잇달아 나오면
 *      첫 건만 표본으로 채택 (윈도우 중첩으로 인한 유효 표본 부풀림 제거).
 *
 * 선견 편향 방지: 공시 시각이 불명(rcept_dt는 날짜만)이므로 진입은 항상
 * **공시일 다음 거래일 시가** — 장 마감 후 공시를 당일 진입으로 계산하는 오류를 차단.
 * 기대수익 대표값은 평균이 아니라 **중앙값과 하위 25%** (평균은 소수 대박이 왜곡).
 */
@Component
public class EventStatsBacktester {

    private static final Logger log = LoggerFactory.getLogger(EventStatsBacktester.class);

    public static final List<Integer> HORIZONS = List.of(1, 5, 10, 20);

    /** 같은 종목·유형 이벤트의 최소 간격 (거래일) — D+5 주력 윈도우와 일치 */
    static final int CLUSTER_WINDOW_TRADING_DAYS = 5;

    /** 크기 조건 버킷: 공급계약 금액이 최근 매출액의 이 % 이상이면 "대형" (§8 크기 조건화) */
    static final double BIG_CONTRACT_MIN_SALES_RATIO_PCT = 10.0;
    static final String BIG_CONTRACT_TYPE = "SUPPLY_CONTRACT_BIG10";

    private final DisclosureRepository disclosureRepository;
    private final CandleHistoryRepository candleHistoryRepository;
    private final BacktestDataProperties properties;

    public EventStatsBacktester(DisclosureRepository disclosureRepository,
                                CandleHistoryRepository candleHistoryRepository,
                                BacktestDataProperties properties) {
        this.disclosureRepository = disclosureRepository;
        this.candleHistoryRepository = candleHistoryRepository;
        this.properties = properties;
    }

    /** @param symbols candle_history가 적재된 종목만 (수집 대상과 정합 필수) */
    public List<EventStat> compute(List<String> symbols, LocalDate from, LocalDate to) {
        Map<String, List<CandleHistory>> candlesBySymbol = new HashMap<>();
        for (String symbol : symbols) {
            candlesBySymbol.put(symbol, loadDaily(symbol, from, to));
        }

        // 시장별 벤치마크 — KOSDAQ 종목은 KOSDAQ 지수로 (시장 불일치 편향 제거, §8)
        Benchmark kospi  = Benchmark.of(loadDaily(properties.getKospiStorageCode(), from, to));
        Benchmark kosdaq = Benchmark.of(loadDaily(properties.getKosdaqStorageCode(), from, to));
        if (kospi.candles().isEmpty()) {
            log.warn("[EventStats] KOSPI 캔들 없음 — 벤치마크 조정 없이 원수익률로 폴백");
        }
        if (kosdaq.candles().isEmpty() && !properties.getKosdaqSymbols().isEmpty()) {
            log.warn("[EventStats] KOSDAQ 캔들 없음 — KOSDAQ 종목은 KOSPI로 폴백");
        }

        // ② 클러스터 병합: (종목, 유형)별 시간순 정렬 후 5거래일 내 후속 이벤트 제외
        Map<String, List<DisclosureItem>> byStockType = new HashMap<>();
        for (DisclosureItem d : disclosureRepository.findAll()) {
            if (d.getEventType() == null) continue;
            byStockType.computeIfAbsent(d.getStockCode() + "|" + d.getEventType(),
                    k -> new ArrayList<>()).add(d);
        }

        Map<String, Map<Integer, List<Double>>> returnsByType = new TreeMap<>();
        int usable = 0, merged = 0, skipped = 0, rawFallback = 0;

        for (List<DisclosureItem> group : byStockType.values()) {
            group.sort(Comparator.comparing(DisclosureItem::getDisclosedAt));
            // 첫 이벤트가 항상 통과하도록 −윈도우로 초기화 (MIN_VALUE는 뺄셈 오버플로)
            int lastKeptEntryIdx = -CLUSTER_WINDOW_TRADING_DAYS;

            for (DisclosureItem d : group) {
                List<CandleHistory> candles = candlesBySymbol.get(d.getStockCode());
                if (candles == null || candles.isEmpty()) { skipped++; continue; }

                int entryIdx = firstIndexAfter(candles, d.getDisclosedAt());
                if (entryIdx < 0) { skipped++; continue; }
                if (entryIdx - lastKeptEntryIdx < CLUSTER_WINDOW_TRADING_DAYS) { merged++; continue; }

                double entryPrice = candles.get(entryIdx).getOpen();
                if (entryPrice <= 0) { skipped++; continue; }

                // ① 시장별 벤치마크 정렬 — 종목 진입일과 같은 날짜의 지수 캔들
                Benchmark bench = properties.getKosdaqSymbols().contains(d.getStockCode())
                        && !kosdaq.candles().isEmpty() ? kosdaq : kospi;
                Integer benchEntryIdx = bench.idxByDate().get(candles.get(entryIdx).getCandleDate());
                double benchEntry = benchEntryIdx != null
                        ? bench.candles().get(benchEntryIdx).getOpen() : 0;

                // 크기 조건 버킷 — 대형 공급계약은 별도 유형으로도 집계
                boolean bigContract = "SUPPLY_CONTRACT".equals(d.getEventType())
                        && d.getSizeRatio() != null
                        && d.getSizeRatio() >= BIG_CONTRACT_MIN_SALES_RATIO_PCT;

                Map<Integer, List<Double>> horizons = returnsByType
                        .computeIfAbsent(d.getEventType(), k -> new LinkedHashMap<>());
                Map<Integer, List<Double>> bigHorizons = bigContract
                        ? returnsByType.computeIfAbsent(BIG_CONTRACT_TYPE, k -> new LinkedHashMap<>())
                        : null;
                boolean counted = false;
                for (int h : HORIZONS) {
                    int exitIdx = entryIdx + h;
                    if (exitIdx >= candles.size()) continue;
                    double stockRet = candles.get(exitIdx).getClose() / entryPrice - 1.0;

                    double excess = stockRet;
                    if (benchEntryIdx != null && benchEntryIdx + h < bench.candles().size()
                            && benchEntry > 0) {
                        excess = stockRet
                                - (bench.candles().get(benchEntryIdx + h).getClose() / benchEntry - 1.0);
                    } else {
                        rawFallback++;
                    }
                    horizons.computeIfAbsent(h, k -> new ArrayList<>()).add(excess);
                    if (bigHorizons != null) {
                        bigHorizons.computeIfAbsent(h, k -> new ArrayList<>()).add(excess);
                    }
                    counted = true;
                }
                if (counted) {
                    usable++;
                    lastKeptEntryIdx = entryIdx;
                } else {
                    skipped++;
                }
            }
        }

        log.info("[EventStats] 표본: 사용 {}건 / 클러스터 병합 제외 {}건 / 기타 제외 {}건, 원수익률 폴백 {}회",
                usable, merged, skipped, rawFallback);

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

    private List<CandleHistory> loadDaily(String symbol, LocalDate from, LocalDate to) {
        return candleHistoryRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        symbol, Timeframe.DAILY, from, to.plusDays(40)); // D+20 여유
    }

    /** 공시일 이후 첫 거래일 캔들 인덱스 (다음 거래일 시가 진입 — 선견 편향 차단) */
    static int firstIndexAfter(List<CandleHistory> candles, LocalDate disclosedAt) {
        for (int i = 0; i < candles.size(); i++) {
            if (candles.get(i).getCandleDate().isAfter(disclosedAt)) return i;
        }
        return -1;
    }

    /** 지수 캔들 시계열 + 날짜→인덱스 맵 (SpilloverStatsBacktester와 공유) */
    record Benchmark(List<CandleHistory> candles, Map<LocalDate, Integer> idxByDate) {
        static Benchmark of(List<CandleHistory> candles) {
            Map<LocalDate, Integer> idx = new HashMap<>();
            for (int i = 0; i < candles.size(); i++) {
                idx.put(candles.get(i).getCandleDate(), i);
            }
            return new Benchmark(candles, idx);
        }
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
