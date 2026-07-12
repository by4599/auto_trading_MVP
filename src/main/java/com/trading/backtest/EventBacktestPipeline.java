package com.trading.backtest;

import com.trading.research.DartApiClient;
import com.trading.research.DisclosureEventClassifier;
import com.trading.research.DisclosureItem;
import com.trading.research.DisclosureRepository;
import com.trading.research.EventTypeStat;
import com.trading.research.EventTypeStatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * B-4 이벤트 백테스트 파이프라인 (--backtest.mode=events):
 *   ① DART 공시 3년 소급 백필 (뉴스와 달리 공시는 과거 조회가 가능 — 표본 즉시 확보)
 *   ② 유형별 D+1/5/10/20 반응 통계 (EventStatsBacktester)
 *   ③ event_type_registry 갱신 — 통계 조건 충족 유형은 CANDIDATE 표기,
 *      PROMOTED 승격은 사람만 (게이트 G2 — 여기서는 절대 하지 않는다)
 *   ④ 리포트 (logs/backtest/EVENT-REPORT-*.md)
 *
 * CANDIDATE 조건 (설계 S3): 표본 ≥ 30건 AND D+5 하위 25% 수익률 > 왕복 비용.
 */
@Component
@Profile("backtest")
public class EventBacktestPipeline {

    private static final Logger log = LoggerFactory.getLogger(EventBacktestPipeline.class);

    private static final int MIN_SAMPLES_FOR_CANDIDATE = 30;

    private final DartApiClient dartApiClient;
    private final DisclosureEventClassifier eventClassifier;
    private final DisclosureRepository disclosureRepository;
    private final EventTypeStatRepository registryRepository;
    private final EventStatsBacktester statsBacktester;
    private final CandleBackfillService candleBackfill;
    private final BacktestDataProperties properties;
    private final Clock clock;

    public EventBacktestPipeline(DartApiClient dartApiClient,
                                 DisclosureEventClassifier eventClassifier,
                                 DisclosureRepository disclosureRepository,
                                 EventTypeStatRepository registryRepository,
                                 EventStatsBacktester statsBacktester,
                                 CandleBackfillService candleBackfill,
                                 BacktestDataProperties properties,
                                 Clock clock) {
        this.dartApiClient = dartApiClient;
        this.eventClassifier = eventClassifier;
        this.disclosureRepository = disclosureRepository;
        this.registryRepository = registryRepository;
        this.statsBacktester = statsBacktester;
        this.candleBackfill = candleBackfill;
        this.properties = properties;
        this.clock = clock;
    }

    public void run() {
        // 표본 = 전략 백테스트 대상 ∪ 이벤트 통계 전용 추가 종목 (B-3 유니버스에는 무영향)
        List<String> symbols = new java.util.ArrayList<>(candleBackfill.targetSymbols());
        List<String> extras = properties.getEventSymbols().stream()
                .filter(s -> !symbols.contains(s)).toList();
        symbols.addAll(extras);

        if (!extras.isEmpty()) {
            int extraCandles = candleBackfill.backfillExtra(extras);
            log.info("[EventBacktest] 추가 표본 {}종목 캔들 백필: 신규 {}건", extras.size(), extraCandles);
        }

        LocalDate to = candleBackfill.rangeTo();
        LocalDate from = LocalDate.now(clock).minusYears(properties.getYears());

        int backfilled = backfillDisclosures(symbols, from, to);
        log.info("[EventBacktest] 공시 백필 완료: 신규 {}건", backfilled);

        List<EventStatsBacktester.EventStat> stats = statsBacktester.compute(symbols, from, to);
        if (stats.isEmpty()) {
            log.warn("[EventBacktest] 이벤트 표본 없음 — DART 키/공시 백필 확인");
            return;
        }

        updateRegistry(stats);
        writeReport(symbols, from, to, stats);
    }

    // ── ① 공시 소급 백필 ─────────────────────────────────────────────────────

    private int backfillDisclosures(List<String> symbols, LocalDate from, LocalDate to) {
        if (!dartApiClient.isConfigured()) {
            log.warn("[EventBacktest] DART 키 미설정 — 백필 건너뜀 (기존 수집분으로만 통계)");
            return 0;
        }
        Map<String, DartApiClient.CorpInfo> corps = dartApiClient.fetchCorpCodeMap();
        int saved = 0;
        for (String stockCode : symbols) {
            DartApiClient.CorpInfo corp = corps.get(stockCode);
            if (corp == null) {
                log.warn("[EventBacktest] corp_code 매핑 없음 — 건너뜀: {}", stockCode);
                continue;
            }
            try {
                for (DartApiClient.DartDisclosure d
                        : dartApiClient.fetchAllDisclosures(corp.corpCode(), from, to)) {
                    if (disclosureRepository.existsByReceiptNo(d.receiptNo())) continue;
                    DisclosureEventClassifier.EventClass ec = eventClassifier.classify(d.reportName());
                    disclosureRepository.save(DisclosureItem.of(
                            stockCode, d.corpName(), d.receiptNo(), d.reportName(),
                            d.disclosedAt(), ec.sentiment(), ec.type()));
                    saved++;
                }
            } catch (Exception e) {
                log.warn("[EventBacktest] 백필 실패 — 계속 진행: {} — {}", stockCode, e.getMessage());
            }
        }
        return saved;
    }

    // ── ③ 레지스트리 갱신 (status 보존, 승격은 사람만) ───────────────────────

    private void updateRegistry(List<EventStatsBacktester.EventStat> stats) {
        for (EventStatsBacktester.EventStat s : stats) {
            EventTypeStat row = registryRepository.findById(s.eventType())
                    .orElseGet(() -> EventTypeStat.recorded(s.eventType()));
            row.updateStats(s.samples(), s.winRateD5(),
                    s.at(1).median(), s.at(5).median(), s.at(10).median(), s.at(20).median(),
                    s.at(5).p25(), s.at(20).p25());
            if (meetsCandidateBar(s)) {
                row.markCandidate();
            }
            registryRepository.save(row);
        }
        log.info("[EventBacktest] event_type_registry {}개 유형 갱신", stats.size());
    }

    private static boolean meetsCandidateBar(EventStatsBacktester.EventStat s) {
        return s.samples() >= MIN_SAMPLES_FOR_CANDIDATE
                && s.at(5).p25() > BacktestCosts.ROUND_TRIP_COST;
    }

    // ── ④ 리포트 ─────────────────────────────────────────────────────────────

    private void writeReport(List<String> symbols, LocalDate from, LocalDate to,
                             List<EventStatsBacktester.EventStat> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 이벤트 백테스트 리포트 (B-4) — 공시 유형별 주가 반응 통계\n\n");
        sb.append("- 실행: ").append(LocalDateTime.now(clock)).append('\n');
        sb.append("- 기간: ").append(from).append(" ~ ").append(to).append('\n');
        sb.append("- 종목: ").append(String.join(", ", symbols)).append('\n');
        sb.append("- 진입 관례: 공시일 **다음 거래일 시가** (공시 시각 불명 — 선견 편향 차단)\n");
        sb.append("- v2 통계 교정: 수익률은 **KOSPI 대비 초과수익**, 같은 종목·유형 ")
          .append(EventStatsBacktester.CLUSTER_WINDOW_TRADING_DAYS)
          .append("거래일 내 클러스터는 첫 건만 채택\n");
        sb.append("- CANDIDATE 조건: 표본 ≥ ").append(MIN_SAMPLES_FOR_CANDIDATE)
          .append("건 AND D+5 p25 > 왕복 비용 ")
          .append(String.format("%.2f%%", BacktestCosts.ROUND_TRIP_COST * 100)).append('\n');
        sb.append("- ⚠ 매매 신호 승격(PROMOTED)은 사람이 판단한다 — 이 리포트는 CANDIDATE 표기까지만\n\n");
        sb.append("| 유형 | 표본 | D+5 승률 | D+1 중앙값 | D+5 중앙값 | D+5 p25 | D+10 중앙값 | D+20 중앙값 | D+20 p25 | 판정 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (EventStatsBacktester.EventStat s : stats) {
            sb.append(String.format("| %s | %d | %.1f%% | %s | %s | %s | %s | %s | %s | %s |%n",
                    s.eventType(), s.samples(), s.winRateD5() * 100,
                    pct(s.at(1).median()), pct(s.at(5).median()), pct(s.at(5).p25()),
                    pct(s.at(10).median()), pct(s.at(20).median()), pct(s.at(20).p25()),
                    meetsCandidateBar(s) ? "🟡 CANDIDATE" : "RECORDED"));
        }
        sb.append("\n※ 표본이 작은 유형(<30)은 통계가 노이즈다 — 수집이 쌓일수록 신뢰도가 오른다.\n");

        try {
            Path dir = Path.of("logs", "backtest");
            Files.createDirectories(dir);
            Path file = dir.resolve("EVENT-REPORT-" + LocalDateTime.now(clock)
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
            Files.writeString(file, sb.toString());
            log.info("[EventBacktest] 리포트 저장: {}", file.toAbsolutePath());
        } catch (IOException e) {
            log.error("[EventBacktest] 리포트 저장 실패", e);
        }
    }

    private static String pct(double v) {
        return String.format("%+.2f%%", v * 100);
    }
}
