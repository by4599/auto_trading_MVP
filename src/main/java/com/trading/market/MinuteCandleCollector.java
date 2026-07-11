package com.trading.market;

import com.trading.universe.TradingUniverseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 당일 분봉 전방 축적 배치 (B-1).
 *
 * KIS는 과거 분봉을 소급 제공하지 않으므로, 장 마감 후 매일 그날의 분봉을 저장해
 * 미래의 정밀 백테스트(분봉 기반 돌파 시각 재현) 표본을 지금부터 쌓는다.
 * DART 공시와 같은 이유로 자동 삭제하지 않는다.
 */
@Component
@Profile("paper")
public class MinuteCandleCollector {

    private static final Logger log = LoggerFactory.getLogger(MinuteCandleCollector.class);

    private final CandleHistoryClient candleClient;
    private final CandleHistoryRepository repository;
    private final TradingUniverseService universeService;
    private final Clock clock;

    public MinuteCandleCollector(CandleHistoryClient candleClient,
                                 CandleHistoryRepository repository,
                                 TradingUniverseService universeService,
                                 Clock clock) {
        this.candleClient = candleClient;
        this.repository = repository;
        this.universeService = universeService;
        this.clock = clock;
    }

    /** 평일 15:40 KST — 타임컷(15:15) 이후 당일 장이 완결된 시점 */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectToday() {
        LocalDate today = LocalDate.now(clock);
        int savedTotal = 0;

        for (String stockCode : universeService.getActiveCodes()) {
            try {
                savedTotal += collectSymbol(stockCode, today);
            } catch (Exception e) {
                log.warn("[MinuteCollector] {} 수집 실패 — 다음 종목 계속: {}", stockCode, e.getMessage());
            }
        }
        log.info("[MinuteCollector] {} 분봉 축적 완료: {}건", today, savedTotal);
    }

    private int collectSymbol(String stockCode, LocalDate today) {
        // 당일 분이 이미 저장돼 있으면 스킵 (배치 중복 실행 방어)
        if (repository.countByStockCodeAndTimeframeAndCandleDate(
                stockCode, Timeframe.MINUTE, today) > 0) {
            log.debug("[MinuteCollector] {} 당일 분봉 기존재 — 스킵", stockCode);
            return 0;
        }

        List<MinuteCandle> candles = candleClient.fetchTodayMinuteCandles(stockCode);
        List<CandleHistory> rows = candles.stream()
                .filter(c -> today.equals(c.date()))
                .map(c -> CandleHistory.ofMinute(stockCode, c))
                .toList();
        repository.saveAll(rows);
        return rows.size();
    }
}
