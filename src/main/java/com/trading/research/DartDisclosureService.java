package com.trading.research;

import com.trading.universe.TradingUniverseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DART 공시 수집 (Phase 3a의 수집부, RECOMMENDATION-TO-TRADE-DESIGN S0).
 *
 * 대상: 매매 유니버스 ∪ 뉴스 워치리스트 종목. 30분 주기.
 * 공시는 이벤트 백테스트(B-4)의 표본이므로 삭제하지 않고 축적한다 —
 * RSS 뉴스와 달리 지금부터 쌓는 데이터가 그대로 통계 검증 자산이 된다.
 *
 * 분류: 보고서명 키워드 1차 분류 (표시 전용 — 매매 미연동).
 * 공시 유형별 오버라이드가 우선하고, 나머지는 NewsSentimentAnalyzer로 폴백.
 */
@Service
public class DartDisclosureService {

    private static final Logger log = LoggerFactory.getLogger(DartDisclosureService.class);

    private static final int LOOKBACK_DAYS = 7;
    private static final long CORP_MAP_TTL_MILLIS = 7L * 24 * 3600 * 1000; // 7일

    /** 공시 유형별 감성 오버라이드 — 보고서명에 키워드 포함 시 우선 적용 (순서 의미 있음) */
    private static final List<Map.Entry<String, String>> REPORT_OVERRIDES = List.of(
            Map.entry("단일판매ㆍ공급계약",  "POSITIVE"),
            Map.entry("공급계약",            "POSITIVE"),
            Map.entry("무상증자",            "POSITIVE"),
            Map.entry("자기주식취득",        "POSITIVE"),
            Map.entry("자기주식 취득",       "POSITIVE"),
            Map.entry("현금ㆍ현물배당",      "POSITIVE"),
            Map.entry("유상증자",            "NEGATIVE"),
            Map.entry("전환사채",            "NEGATIVE"),
            Map.entry("신주인수권부사채",    "NEGATIVE"),
            Map.entry("자기주식처분",        "NEGATIVE"),
            Map.entry("소송",                "NEGATIVE"),
            Map.entry("거래정지",            "NEGATIVE"),
            Map.entry("불성실공시",          "NEGATIVE")
    );

    private final DartApiClient dartApiClient;
    private final DisclosureRepository disclosureRepository;
    private final WatchlistRepository watchlistRepository;
    private final TradingUniverseService universeService;
    private final NewsSentimentAnalyzer sentimentAnalyzer;

    private volatile Map<String, DartApiClient.CorpInfo> corpMap = Map.of();
    private volatile long corpMapLoadedAt = 0L;
    private volatile boolean warnedNotConfigured = false;

    public DartDisclosureService(DartApiClient dartApiClient,
                                 DisclosureRepository disclosureRepository,
                                 WatchlistRepository watchlistRepository,
                                 TradingUniverseService universeService,
                                 NewsSentimentAnalyzer sentimentAnalyzer) {
        this.dartApiClient = dartApiClient;
        this.disclosureRepository = disclosureRepository;
        this.watchlistRepository = watchlistRepository;
        this.universeService = universeService;
        this.sentimentAnalyzer = sentimentAnalyzer;
    }

    /** 기동 2분 후 첫 수집, 이후 30분 주기 */
    @Scheduled(fixedRate = 1_800_000, initialDelay = 120_000)
    public void scheduledAggregate() {
        aggregate();
    }

    int aggregate() {
        if (!dartApiClient.isConfigured()) {
            if (!warnedNotConfigured) {
                log.warn("[DART] 인증키 미설정 — 공시 수집 비활성 (설정 화면에서 DART_API_KEY 입력)");
                warnedNotConfigured = true;
            }
            return 0;
        }

        Set<String> targets = collectTargetCodes();
        if (targets.isEmpty()) return 0;

        Map<String, DartApiClient.CorpInfo> corps = corpMapFresh();
        if (corps.isEmpty()) {
            log.warn("[DART] 상장사 코드 매핑 없음 — 이번 주기 건너뜀");
            return 0;
        }

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(LOOKBACK_DAYS);
        int saved = 0;

        for (String stockCode : targets) {
            DartApiClient.CorpInfo corp = corps.get(stockCode);
            if (corp == null) {
                log.debug("[DART] corp_code 매핑 없음 (비상장/코드 오류?): {}", stockCode);
                continue;
            }
            try {
                for (DartApiClient.DartDisclosure d
                        : dartApiClient.fetchRecentDisclosures(corp.corpCode(), from, to)) {
                    if (disclosureRepository.existsByReceiptNo(d.receiptNo())) continue;
                    disclosureRepository.save(DisclosureItem.of(
                            stockCode, d.corpName(), d.receiptNo(), d.reportName(),
                            d.disclosedAt(), classify(d.reportName())));
                    saved++;
                }
            } catch (Exception e) {
                // 한 종목 실패가 나머지 수집을 멈추지 않는다
                log.warn("[DART] 수집 실패 — 계속 진행: stockCode={} — {}", stockCode, e.getMessage());
            }
        }
        if (saved > 0) log.info("[DART] 공시 {}건 저장 완료 (대상 {}종목)", saved, targets.size());
        return saved;
    }

    /** 매매 유니버스 ∪ 뉴스 워치리스트 (중복 제거, 순서 보존) */
    private Set<String> collectTargetCodes() {
        Set<String> codes = new LinkedHashSet<>(universeService.getActiveCodes());
        watchlistRepository.findAll().forEach(w -> codes.add(w.getStockCode()));
        return codes;
    }

    private Map<String, DartApiClient.CorpInfo> corpMapFresh() {
        long now = Instant.now().toEpochMilli();
        if (!corpMap.isEmpty() && now - corpMapLoadedAt < CORP_MAP_TTL_MILLIS) {
            return corpMap;
        }
        Map<String, DartApiClient.CorpInfo> loaded = dartApiClient.fetchCorpCodeMap();
        if (!loaded.isEmpty()) {
            corpMap = loaded;
            corpMapLoadedAt = now;
        }
        return corpMap;
    }

    String classify(String reportName) {
        if (reportName == null) return "NEUTRAL";
        for (Map.Entry<String, String> override : REPORT_OVERRIDES) {
            if (reportName.contains(override.getKey())) return override.getValue();
        }
        return sentimentAnalyzer.analyze(reportName);
    }
}
