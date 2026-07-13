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
 * 테마 파급(spillover) 통계 (B-4 확장, 투자 방법론 파일럿):
 * 앵커 대형주의 공시 이벤트 → 같은 테마 밸류체인 종목들의 D+N 초과수익.
 * "반도체 호황(앵커 호재) → 장비·소재주 상승"이 실제 데이터에서 관측되는지,
 * 어떤 이벤트 유형이 며칠 시차로 파급되는지를 잰다.
 *
 * 표본 단위는 **앵커 이벤트 1건** — 체인 종목별 초과수익을 이벤트당 횡단면
 * 중앙값으로 접는다. 한 이벤트가 상관된 체인 N종목 수익률로 유효 표본을
 * 부풀리는 것(교차 상관 오염)을 차단하기 위함이며, 커버리지가
 * MIN_CHAIN_COVERAGE 미만인 이벤트·호라이즌은 노이즈로 보고 제외한다.
 *
 * 테마 정의 규약: backtest.event-themes의 "&lt;테마&gt;-anchor" / "&lt;테마&gt;-chain" 키 쌍.
 * 진입 관례(공시 다음 거래일 시가)·클러스터 병합(5거래일)·시장별 벤치마크는
 * EventStatsBacktester v2와 동일. 단 파급은 초과수익이 정의 그 자체이므로
 * 지수 캔들이 없으면 원수익률 폴백 없이 통계를 내지 않는다.
 */
@Component
public class SpilloverStatsBacktester {

    private static final Logger log = LoggerFactory.getLogger(SpilloverStatsBacktester.class);

    static final String ANCHOR_KEY_SUFFIX = "-anchor";
    static final String CHAIN_KEY_SUFFIX  = "-chain";

    /** 이벤트당 최소 체인 커버리지 — 미만이면 횡단면 중앙값이 개별 종목 노이즈다 */
    static final int MIN_CHAIN_COVERAGE = 3;

    /** event_type_registry 키 접두어 — 종목 자신의 이벤트 통계와 구분 */
    public static final String REGISTRY_PREFIX = "SPILL:";

    private final DisclosureRepository disclosureRepository;
    private final CandleHistoryRepository candleHistoryRepository;
    private final BacktestDataProperties properties;

    public SpilloverStatsBacktester(DisclosureRepository disclosureRepository,
                                    CandleHistoryRepository candleHistoryRepository,
                                    BacktestDataProperties properties) {
        this.disclosureRepository = disclosureRepository;
        this.candleHistoryRepository = candleHistoryRepository;
        this.properties = properties;
    }

    public List<SpilloverStat> compute(LocalDate from, LocalDate to) {
        EventStatsBacktester.Benchmark kospi = EventStatsBacktester.Benchmark.of(
                loadDaily(properties.getKospiStorageCode(), from, to));
        EventStatsBacktester.Benchmark kosdaq = EventStatsBacktester.Benchmark.of(
                loadDaily(properties.getKosdaqStorageCode(), from, to));
        if (kospi.candles().isEmpty() && kosdaq.candles().isEmpty()) {
            log.warn("[Spillover] 지수 캔들 없음 — 파급 통계는 초과수익 기반이라 건너뜀");
            return List.of();
        }
        // 클러스터 병합의 거래일 거리 척도 — 진입은 체인 종목별이라 공통 캘린더가 필요
        List<CandleHistory> refCalendar =
                !kospi.candles().isEmpty() ? kospi.candles() : kosdaq.candles();

        List<DisclosureItem> disclosures = disclosureRepository.findAll();

        List<SpilloverStat> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : properties.getEventThemes().entrySet()) {
            if (!entry.getKey().endsWith(ANCHOR_KEY_SUFFIX)) continue;
            String theme = entry.getKey()
                    .substring(0, entry.getKey().length() - ANCHOR_KEY_SUFFIX.length());
            List<String> chain = properties.getEventThemes()
                    .getOrDefault(theme + CHAIN_KEY_SUFFIX, List.of());
            if (chain.size() < MIN_CHAIN_COVERAGE) {
                log.warn("[Spillover] 테마 {} 체인 {}종목 — 최소 커버리지 {} 미만이라 건너뜀",
                        theme, chain.size(), MIN_CHAIN_COVERAGE);
                continue;
            }
            result.addAll(computeTheme(theme, entry.getValue(), chain, disclosures,
                    from, to, kospi, kosdaq, refCalendar));
        }
        return result;
    }

    private List<SpilloverStat> computeTheme(String theme, List<String> anchors,
                                             List<String> chain, List<DisclosureItem> disclosures,
                                             LocalDate from, LocalDate to,
                                             EventStatsBacktester.Benchmark kospi,
                                             EventStatsBacktester.Benchmark kosdaq,
                                             List<CandleHistory> refCalendar) {
        Map<String, List<CandleHistory>> chainCandles = new HashMap<>();
        for (String member : chain) {
            chainCandles.put(member, loadDaily(member, from, to));
        }

        Map<String, List<DisclosureItem>> byAnchorType = new HashMap<>();
        for (DisclosureItem d : disclosures) {
            if (d.getEventType() == null || !anchors.contains(d.getStockCode())) continue;
            byAnchorType.computeIfAbsent(d.getStockCode() + "|" + d.getEventType(),
                    k -> new ArrayList<>()).add(d);
        }

        Map<String, Map<Integer, List<Double>>> byType = new TreeMap<>();
        int usable = 0, merged = 0, skipped = 0;

        for (List<DisclosureItem> group : byAnchorType.values()) {
            group.sort(Comparator.comparing(DisclosureItem::getDisclosedAt));
            int lastKeptRefIdx = -EventStatsBacktester.CLUSTER_WINDOW_TRADING_DAYS;

            for (DisclosureItem d : group) {
                int refIdx = EventStatsBacktester.firstIndexAfter(refCalendar, d.getDisclosedAt());
                if (refIdx < 0) { skipped++; continue; }
                if (refIdx - lastKeptRefIdx < EventStatsBacktester.CLUSTER_WINDOW_TRADING_DAYS) {
                    merged++;
                    continue;
                }

                // 체인 종목별 초과수익 → 호라이즌별 횡단면 버킷
                Map<Integer, List<Double>> cross = new LinkedHashMap<>();
                for (String member : chain) {
                    accumulateMemberReturns(cross, chainCandles.get(member), member,
                            d.getDisclosedAt(), kospi, kosdaq);
                }

                // 커버리지를 충족한 호라이즌만 접는다 — 전부 미달이면 유형 엔트리도 만들지 않음
                Map<Integer, Double> eventMedians = new LinkedHashMap<>();
                for (int h : EventStatsBacktester.HORIZONS) {
                    List<Double> memberReturns = cross.getOrDefault(h, List.of());
                    if (memberReturns.size() < MIN_CHAIN_COVERAGE) continue;
                    eventMedians.put(h, EventStatsBacktester.Quantiles.of(memberReturns).median());
                }
                if (!eventMedians.isEmpty()) {
                    Map<Integer, List<Double>> horizons = byType
                            .computeIfAbsent(d.getEventType(), k -> new LinkedHashMap<>());
                    eventMedians.forEach((h, median) ->
                            horizons.computeIfAbsent(h, k -> new ArrayList<>()).add(median));
                    usable++;
                    lastKeptRefIdx = refIdx;
                } else {
                    skipped++;
                }
            }
        }

        log.info("[Spillover] 테마 {}: 앵커 이벤트 사용 {}건 / 클러스터 병합 제외 {}건 / 기타 제외 {}건",
                theme, usable, merged, skipped);

        List<SpilloverStat> stats = new ArrayList<>();
        byType.forEach((type, horizons) -> {
            List<Double> d5 = horizons.getOrDefault(5, List.of());
            long wins = d5.stream().filter(r -> r > 0).count();
            Map<Integer, EventStatsBacktester.Quantiles> quantiles = new LinkedHashMap<>();
            horizons.forEach((h, rets) -> quantiles.put(h, EventStatsBacktester.Quantiles.of(rets)));
            stats.add(new SpilloverStat(theme, type, chain.size(),
                    horizons.values().stream().mapToInt(List::size).max().orElse(0),
                    d5.isEmpty() ? 0.0 : (double) wins / d5.size(), quantiles));
        });
        return stats;
    }

    /** 한 체인 종목의 D+N 초과수익을 호라이즌별 횡단면 버킷에 누적 */
    private void accumulateMemberReturns(Map<Integer, List<Double>> cross,
                                         List<CandleHistory> candles, String member,
                                         LocalDate disclosedAt,
                                         EventStatsBacktester.Benchmark kospi,
                                         EventStatsBacktester.Benchmark kosdaq) {
        if (candles == null || candles.isEmpty()) return;
        int entryIdx = EventStatsBacktester.firstIndexAfter(candles, disclosedAt);
        if (entryIdx < 0) return;
        double entryPrice = candles.get(entryIdx).getOpen();
        if (entryPrice <= 0) return;

        EventStatsBacktester.Benchmark bench =
                properties.getKosdaqSymbols().contains(member) && !kosdaq.candles().isEmpty()
                        ? kosdaq : kospi;
        Integer benchEntryIdx = bench.idxByDate().get(candles.get(entryIdx).getCandleDate());
        if (benchEntryIdx == null) return; // 초과수익만 취급 — 지수 정렬 실패 시 이 종목 제외
        double benchEntry = bench.candles().get(benchEntryIdx).getOpen();
        if (benchEntry <= 0) return;

        for (int h : EventStatsBacktester.HORIZONS) {
            int exitIdx = entryIdx + h;
            if (exitIdx >= candles.size()
                    || benchEntryIdx + h >= bench.candles().size()) continue;
            double stockRet = candles.get(exitIdx).getClose() / entryPrice - 1.0;
            double benchRet = bench.candles().get(benchEntryIdx + h).getClose() / benchEntry - 1.0;
            cross.computeIfAbsent(h, k -> new ArrayList<>()).add(stockRet - benchRet);
        }
    }

    private List<CandleHistory> loadDaily(String symbol, LocalDate from, LocalDate to) {
        return candleHistoryRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        symbol, Timeframe.DAILY, from, to.plusDays(40)); // D+20 여유
    }

    // ── 결과 타입 ─────────────────────────────────────────────────────────────

    public record SpilloverStat(String theme, String eventType, int chainSize, int samples,
                                double winRateD5,
                                Map<Integer, EventStatsBacktester.Quantiles> horizons) {
        public EventStatsBacktester.Quantiles at(int horizon) {
            return horizons.getOrDefault(horizon, EventStatsBacktester.Quantiles.EMPTY);
        }

        /** event_type_registry 키 — "SPILL:&lt;테마&gt;:&lt;유형&gt;" */
        public String registryKey() {
            return REGISTRY_PREFIX + theme + ":" + eventType;
        }
    }
}
