package com.trading.backtest;

import com.trading.research.DartApiClient;
import com.trading.research.DisclosureEventClassifier;
import com.trading.research.DisclosureItem;
import com.trading.research.DisclosureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * B-4 ① 공시 소급 백필 + ①-b 공급계약 크기 보강.
 *
 * <p>뉴스와 달리 공시는 과거 조회가 가능해 표본을 즉시 확보할 수 있다. 계약 크기는
 * "발생"이 아니라 "크기"가 신호인지 검증하려고 원문을 파싱해 매출 대비 비율을 채운다.
 */
@Component
@Profile("backtest")
public class DisclosureBackfiller {

    private static final Logger log = LoggerFactory.getLogger(DisclosureBackfiller.class);

    private static final int ENRICH_CAP_PER_RUN = 300;
    private static final double PARSE_FAILED_SENTINEL = -1.0; // 재다운로드 방지 마커

    private final DartApiClient dartApiClient;
    private final DisclosureEventClassifier eventClassifier;
    private final DisclosureRepository disclosureRepository;

    public DisclosureBackfiller(DartApiClient dartApiClient,
                                DisclosureEventClassifier eventClassifier,
                                DisclosureRepository disclosureRepository) {
        this.dartApiClient = dartApiClient;
        this.eventClassifier = eventClassifier;
        this.disclosureRepository = disclosureRepository;
    }

    public int backfillDisclosures(List<String> symbols, LocalDate from, LocalDate to) {
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
            saved += backfillOne(stockCode, corp, from, to);
        }
        return saved;
    }

    private int backfillOne(String stockCode, DartApiClient.CorpInfo corp,
                            LocalDate from, LocalDate to) {
        int saved = 0;
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
        return saved;
    }

    public int enrichContractSizes() {
        if (!dartApiClient.isConfigured()) return 0;
        List<DisclosureItem> targets =
                disclosureRepository.findByEventTypeAndSizeRatioIsNull("SUPPLY_CONTRACT");
        int enriched = 0, processed = 0;
        for (DisclosureItem d : targets) {
            if (processed >= ENRICH_CAP_PER_RUN) break;
            processed++;
            java.util.OptionalDouble ratio = dartApiClient.fetchContractSalesRatio(d.getReceiptNo());
            if (ratio.isPresent()) {
                d.assignSizeRatio(ratio.getAsDouble());
                enriched++;
            } else {
                d.assignSizeRatio(PARSE_FAILED_SENTINEL); // 파싱 실패 — 크기 표본에서 제외
            }
            disclosureRepository.save(d);
        }
        return enriched;
    }
}
