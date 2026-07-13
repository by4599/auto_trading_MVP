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
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * 진입 트리거 실험 (B-4 확장 3단계, v1 일봉 근사) — 파급 이벤트에서
 * "언제 타는가"를 격리 비교한다. 파급 통계(SpilloverStatsBacktester)가
 * "무엇이(어떤 이벤트가) 오르는가"를 재면, 여기서는 같은 이벤트 표본에
 * 진입 방식 3종을 적용해 성과 차이를 잰다:
 *
 *   IMMEDIATE        — 공시 다음 거래일 시가 (파급 통계와 동일 관례, 기준선)
 *   PULLBACK_REBOUND — 눌림(전일比 종가 하락) 후 눌림일 고가 돌파 시 진입 ("상승 반등")
 *   BREAKOUT         — 이벤트일 고가 돌파 시 진입 (모멘텀 확인)
 *
 * 공정 비교를 위해 청산은 전 트리거 공통 **이벤트 D+10 종가**로 고정 —
 * 진입 타이밍만 변수로 격리한다. 트리거가 D+5까지 완성되지 않으면 미발동
 * (발동률로 별도 집계 — 기회 빈도 자체가 트리거의 속성이다).
 *
 * 일봉 근사의 한계: 돌파가는 max(시가, 기준가)로 보수 추정하고, 장중 순서를
 * 모르므로 같은 봉에서의 눌림·반등 동시 발생은 다음 봉으로 미룬다.
 * 분봉이 축적되면 (MinuteCandleCollector 전방 수집) 분봉 정밀 버전으로 재검증.
 *
 * 표본 규약은 파급 통계와 동일 (테마 anchor/chain 쌍, 5거래일 클러스터 병합,
 * 시장별 벤치마크 초과수익, 이벤트당 체인 횡단면 중앙값 접기) — 단 클러스터
 * 병합은 순수 시간 기준(커버리지 무관 첫 건 채택)이라 표본 수가 소폭 다를 수 있다.
 * 리포트 전용 — event_type_registry에는 쓰지 않는다 (실험이지 유형 통계가 아님).
 */
@Component
public class EntryTriggerBacktester {

    private static final Logger log = LoggerFactory.getLogger(EntryTriggerBacktester.class);

    /** 트리거 완성 기한 — 진입 신호가 D+이 거래일 안에 완성되지 않으면 미발동 */
    static final int FIRE_WINDOW_TRADING_DAYS = 5;

    /** 공통 청산 시점 — 이벤트 D+N 종가 (파급 통계 D+10 지표와 같은 봉) */
    static final int EXIT_HORIZON_TRADING_DAYS = 10;

    public enum Trigger { IMMEDIATE, PULLBACK_REBOUND, BREAKOUT }

    private final DisclosureRepository disclosureRepository;
    private final CandleHistoryRepository candleHistoryRepository;
    private final BacktestDataProperties properties;

    public EntryTriggerBacktester(DisclosureRepository disclosureRepository,
                                  CandleHistoryRepository candleHistoryRepository,
                                  BacktestDataProperties properties) {
        this.disclosureRepository = disclosureRepository;
        this.candleHistoryRepository = candleHistoryRepository;
        this.properties = properties;
    }

    public List<TriggerStat> compute(LocalDate from, LocalDate to) {
        EventStatsBacktester.Benchmark kospi = EventStatsBacktester.Benchmark.of(
                loadDaily(properties.getKospiStorageCode(), from, to));
        EventStatsBacktester.Benchmark kosdaq = EventStatsBacktester.Benchmark.of(
                loadDaily(properties.getKosdaqStorageCode(), from, to));
        if (kospi.candles().isEmpty() && kosdaq.candles().isEmpty()) {
            log.warn("[EntryTrigger] 지수 캔들 없음 — 트리거 실험은 초과수익 기반이라 건너뜀");
            return List.of();
        }
        List<CandleHistory> refCalendar =
                !kospi.candles().isEmpty() ? kospi.candles() : kosdaq.candles();

        List<DisclosureItem> disclosures = disclosureRepository.findAll();

        List<TriggerStat> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : properties.getEventThemes().entrySet()) {
            if (!entry.getKey().endsWith(SpilloverStatsBacktester.ANCHOR_KEY_SUFFIX)) continue;
            String theme = entry.getKey().substring(0,
                    entry.getKey().length() - SpilloverStatsBacktester.ANCHOR_KEY_SUFFIX.length());
            List<String> chain = properties.getEventThemes()
                    .getOrDefault(theme + SpilloverStatsBacktester.CHAIN_KEY_SUFFIX, List.of());
            if (chain.size() < SpilloverStatsBacktester.MIN_CHAIN_COVERAGE) continue;
            result.addAll(computeTheme(theme, entry.getValue(), chain, disclosures,
                    from, to, kospi, kosdaq, refCalendar));
        }
        return result;
    }

    private List<TriggerStat> computeTheme(String theme, List<String> anchors,
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

        // (유형|트리거) → 집계 버킷
        Map<String, Bucket> buckets = new TreeMap<>();

        for (List<DisclosureItem> group : byAnchorType.values()) {
            group.sort(Comparator.comparing(DisclosureItem::getDisclosedAt));
            int lastKeptRefIdx = -EventStatsBacktester.CLUSTER_WINDOW_TRADING_DAYS;

            for (DisclosureItem d : group) {
                int refIdx = EventStatsBacktester.firstIndexAfter(refCalendar, d.getDisclosedAt());
                if (refIdx < 0) continue;
                // 순수 시간 기준 병합 — 커버리지와 무관하게 첫 건 채택 (클래스 주석 참고)
                if (refIdx - lastKeptRefIdx < EventStatsBacktester.CLUSTER_WINDOW_TRADING_DAYS) {
                    continue;
                }
                lastKeptRefIdx = refIdx;

                for (Trigger trigger : Trigger.values()) {
                    List<Double> fired = new ArrayList<>();
                    int eligible = 0;
                    for (String member : chain) {
                        MemberOutcome outcome = simulate(chainCandles.get(member), member,
                                d.getDisclosedAt(), trigger, kospi, kosdaq);
                        if (!outcome.eligible()) continue;
                        eligible++;
                        outcome.excess().ifPresent(fired::add);
                    }
                    if (eligible == 0) continue;

                    Bucket bucket = buckets.computeIfAbsent(
                            d.getEventType() + "|" + trigger, k -> new Bucket());
                    bucket.firedMembers += fired.size();
                    bucket.eligibleMembers += eligible;
                    if (fired.size() >= SpilloverStatsBacktester.MIN_CHAIN_COVERAGE) {
                        bucket.eventMedians.add(
                                EventStatsBacktester.Quantiles.of(fired).median());
                    }
                }
            }
        }

        List<TriggerStat> stats = new ArrayList<>();
        buckets.forEach((key, bucket) -> {
            String[] parts = key.split("\\|");
            long wins = bucket.eventMedians.stream().filter(r -> r > 0).count();
            stats.add(new TriggerStat(theme, parts[0], Trigger.valueOf(parts[1]),
                    bucket.eventMedians.size(),
                    (double) bucket.firedMembers / bucket.eligibleMembers,
                    bucket.eventMedians.isEmpty() ? 0.0
                            : (double) wins / bucket.eventMedians.size(),
                    EventStatsBacktester.Quantiles.of(bucket.eventMedians)));
        });
        log.info("[EntryTrigger] 테마 {}: (유형×트리거) {}조합 집계", theme, stats.size());
        return stats;
    }

    /**
     * 한 체인 종목에 트리거 1종을 시뮬레이션한다.
     * 진입가 근사는 보수적으로: 돌파 진입은 max(당일 시가, 돌파 기준가).
     */
    private MemberOutcome simulate(List<CandleHistory> candles, String member,
                                   LocalDate disclosedAt, Trigger trigger,
                                   EventStatsBacktester.Benchmark kospi,
                                   EventStatsBacktester.Benchmark kosdaq) {
        if (candles == null || candles.isEmpty()) return MemberOutcome.INELIGIBLE;
        int entryIdx = EventStatsBacktester.firstIndexAfter(candles, disclosedAt);
        // 이벤트 당일 캔들(d0)이 필요 — BREAKOUT 기준가·눌림의 전일 종가 비교 기준
        if (entryIdx < 1) return MemberOutcome.INELIGIBLE;
        int exitIdx = entryIdx + EXIT_HORIZON_TRADING_DAYS;
        if (exitIdx >= candles.size()) return MemberOutcome.INELIGIBLE;

        int fireDeadline = entryIdx + FIRE_WINDOW_TRADING_DAYS; // exclusive
        int entryDay;
        double entryPrice;

        switch (trigger) {
            case IMMEDIATE -> {
                entryDay = entryIdx;
                entryPrice = candles.get(entryIdx).getOpen();
            }
            case BREAKOUT -> {
                double ref = candles.get(entryIdx - 1).getHigh();
                int day = -1;
                for (int i = entryIdx; i < fireDeadline; i++) {
                    if (candles.get(i).getHigh() > ref) { day = i; break; }
                }
                if (day < 0) return MemberOutcome.noFire();
                entryDay = day;
                entryPrice = Math.max(candles.get(day).getOpen(), ref);
            }
            case PULLBACK_REBOUND -> {
                int pullback = -1;
                for (int i = entryIdx; i < fireDeadline; i++) {
                    if (candles.get(i).getClose() < candles.get(i - 1).getClose()) {
                        pullback = i;
                        break;
                    }
                }
                if (pullback < 0) return MemberOutcome.noFire();
                double ref = candles.get(pullback).getHigh();
                int day = -1;
                for (int j = pullback + 1; j < fireDeadline; j++) {
                    if (candles.get(j).getHigh() > ref) { day = j; break; }
                }
                if (day < 0) return MemberOutcome.noFire();
                entryDay = day;
                entryPrice = Math.max(candles.get(day).getOpen(), ref);
            }
            default -> throw new IllegalStateException("미지원 트리거: " + trigger);
        }
        if (entryPrice <= 0) return MemberOutcome.INELIGIBLE;

        EventStatsBacktester.Benchmark bench =
                properties.getKosdaqSymbols().contains(member) && !kosdaq.candles().isEmpty()
                        ? kosdaq : kospi;
        Integer benchEntryIdx = bench.idxByDate().get(candles.get(entryDay).getCandleDate());
        Integer benchExitIdx = bench.idxByDate().get(candles.get(exitIdx).getCandleDate());
        if (benchEntryIdx == null || benchExitIdx == null) return MemberOutcome.INELIGIBLE;
        double benchEntry = bench.candles().get(benchEntryIdx).getOpen();
        if (benchEntry <= 0) return MemberOutcome.INELIGIBLE;

        double stockRet = candles.get(exitIdx).getClose() / entryPrice - 1.0;
        double benchRet = bench.candles().get(benchExitIdx).getClose() / benchEntry - 1.0;
        return MemberOutcome.fired(stockRet - benchRet);
    }

    private List<CandleHistory> loadDaily(String symbol, LocalDate from, LocalDate to) {
        return candleHistoryRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        symbol, Timeframe.DAILY, from, to.plusDays(40)); // D+10 + 여유
    }

    // ── 내부 타입 ─────────────────────────────────────────────────────────────

    /** 체인 종목 1개의 시뮬 결과 — 부적격(데이터 없음) / 미발동 / 발동(초과수익) */
    private record MemberOutcome(boolean eligible, OptionalDouble excess) {
        static final MemberOutcome INELIGIBLE = new MemberOutcome(false, OptionalDouble.empty());
        static MemberOutcome noFire() { return new MemberOutcome(true, OptionalDouble.empty()); }
        static MemberOutcome fired(double v) { return new MemberOutcome(true, OptionalDouble.of(v)); }
    }

    /** (유형|트리거) 집계 버킷 — 이벤트 중앙값 목록 + 발동/적격 멤버 수 */
    private static final class Bucket {
        final List<Double> eventMedians = new ArrayList<>();
        long firedMembers;
        long eligibleMembers;
    }

    // ── 결과 타입 ─────────────────────────────────────────────────────────────

    /**
     * @param samples  커버리지를 충족한 이벤트 수 (표본 단위 = 이벤트, 파급 통계와 동일)
     * @param fireRate 적격 체인 멤버 중 트리거가 완성된 비율 (기회 빈도)
     */
    public record TriggerStat(String theme, String eventType, Trigger trigger,
                              int samples, double fireRate, double winRate,
                              EventStatsBacktester.Quantiles quantiles) {}
}
