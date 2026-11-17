package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * B-4 이벤트 백테스트 파이프라인 (--backtest.mode=events):
 *   ① DART 공시 3년 소급 백필 ({@link DisclosureBackfiller})
 *      — 뉴스와 달리 공시는 과거 조회가 가능해 표본을 즉시 확보한다
 *   ② 유형별 D+1/5/10/20 반응 통계 (EventStatsBacktester)
 *      + 시가총액 세분화 재집계 ({@link MarketCapGroupStats})
 *      + 테마 파급 통계 (SpilloverStatsBacktester — 앵커 공시 → 밸류체인 초과수익)
 *      + 진입 트리거 실험 (EntryTriggerBacktester — 즉시/눌림반등/돌파 격리 비교)
 *   ③ event_type_registry 갱신 ({@link EventRegistryUpdater}) — 통계 조건 충족 유형은
 *      CANDIDATE 표기, PROMOTED 승격은 사람만 (게이트 G2 — 여기서는 절대 하지 않는다)
 *   ④ 리포트 ({@link EventReportWriter} — logs/backtest/EVENT-REPORT-*.md)
 */
@Component
@Profile("backtest")
public class EventBacktestPipeline {

    private static final Logger log = LoggerFactory.getLogger(EventBacktestPipeline.class);

    private final DisclosureBackfiller disclosureBackfiller;
    private final EventStatsBacktester statsBacktester;
    private final MarketCapGroupStats marketCapGroupStats;
    private final SpilloverStatsBacktester spilloverBacktester;
    private final EntryTriggerBacktester entryTriggerBacktester;
    private final EventRegistryUpdater registryUpdater;
    private final EventReportWriter reportWriter;
    private final CandleBackfillService candleBackfill;
    private final BacktestDataProperties properties;
    private final Clock clock;

    public EventBacktestPipeline(DisclosureBackfiller disclosureBackfiller,
                                 EventStatsBacktester statsBacktester,
                                 MarketCapGroupStats marketCapGroupStats,
                                 SpilloverStatsBacktester spilloverBacktester,
                                 EntryTriggerBacktester entryTriggerBacktester,
                                 EventRegistryUpdater registryUpdater,
                                 EventReportWriter reportWriter,
                                 CandleBackfillService candleBackfill,
                                 BacktestDataProperties properties,
                                 Clock clock) {
        this.disclosureBackfiller = disclosureBackfiller;
        this.statsBacktester = statsBacktester;
        this.marketCapGroupStats = marketCapGroupStats;
        this.spilloverBacktester = spilloverBacktester;
        this.entryTriggerBacktester = entryTriggerBacktester;
        this.registryUpdater = registryUpdater;
        this.reportWriter = reportWriter;
        this.candleBackfill = candleBackfill;
        this.properties = properties;
        this.clock = clock;
    }

    public void run() {
        List<String> symbols = prepareSampleSymbols();

        LocalDate to = candleBackfill.rangeTo();
        LocalDate from = LocalDate.now(clock).minusYears(properties.getYears());

        int backfilled = disclosureBackfiller.backfillDisclosures(symbols, from, to);
        log.info("[EventBacktest] 공시 백필 완료: 신규 {}건", backfilled);

        int enriched = disclosureBackfiller.enrichContractSizes();
        log.info("[EventBacktest] 공급계약 크기 보강: {}건", enriched);

        List<EventStatsBacktester.EventStat> stats = statsBacktester.compute(symbols, from, to);
        if (stats.isEmpty()) {
            log.warn("[EventBacktest] 이벤트 표본 없음 — DART 키/공시 백필 확인");
            return;
        }

        // 시가총액 세분화 재검증 (2026-07-20) — 대형주는 공시에 둔감하다는 §8 통설을 정량 확인
        List<EventStatsBacktester.EventStat> groupedStats =
                marketCapGroupStats.compute(symbols, from, to);

        // 테마 파급 통계 — 앵커 공시 → 밸류체인 D+N 초과수익 (방법론 파일럿)
        List<SpilloverStatsBacktester.SpilloverStat> spillover =
                spilloverBacktester.compute(from, to);

        // 진입 트리거 실험 — 같은 이벤트 표본에서 진입 방식 3종 격리 비교 (리포트 전용)
        List<EntryTriggerBacktester.TriggerStat> triggers =
                entryTriggerBacktester.compute(from, to);

        registryUpdater.updateRegistry(stats);
        registryUpdater.updateRegistry(groupedStats);
        registryUpdater.updateSpilloverRegistry(spillover);
        reportWriter.write(new EventReportData(
                symbols, from, to, stats, groupedStats, spillover, triggers));
    }

    /** 표본 = 전략 백테스트 대상 ∪ 이벤트 통계 전용 추가 종목 (B-3 유니버스에는 무영향) */
    private List<String> prepareSampleSymbols() {
        List<String> symbols = new java.util.ArrayList<>(candleBackfill.targetSymbols());
        List<String> extras = properties.getEventSymbols().stream()
                .filter(s -> !symbols.contains(s)).toList();
        symbols.addAll(extras);

        if (!extras.isEmpty()) {
            int extraCandles = candleBackfill.backfillExtra(extras);
            log.info("[EventBacktest] 추가 표본 {}종목 캔들 백필: 신규 {}건", extras.size(), extraCandles);
        }
        return symbols;
    }
}
