package com.trading.market;

import com.trading.backtest.BacktestDataProperties;
import com.trading.universe.TradingUniverseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 당일 분봉 전방 축적 배치 (B-1).
 *
 * KIS는 과거 분봉을 소급 제공하지 않으므로, 장 마감 후 매일 그날의 분봉을 저장해
 * 미래의 정밀 백테스트(분봉 기반 돌파 시각 재현) 표본을 지금부터 쌓는다.
 * DART 공시와 같은 이유로 자동 삭제하지 않는다.
 *
 * 수집 대상은 매매 유니버스 ∪ 백테스트 표본(symbols·eventSymbols) — 분봉은
 * 소급 불가라 표본 폭이 곧 축적 속도다. 1종목이면 시간창·거래량 필터 검증에
 * 필요한 표본(트레이드 100건+)까지 수년이 걸리지만, 14종목이면 그만큼 단축된다.
 * 매매 대상 확대(G1)와 무관한 수집 전용 확장이다.
 */
@Component
@Profile("paper")
public class MinuteCandleCollector {

    private static final Logger log = LoggerFactory.getLogger(MinuteCandleCollector.class);

    private final CandleHistoryClient candleClient;
    private final CandleHistoryRepository repository;
    private final TradingUniverseService universeService;
    private final BacktestDataProperties backtestProperties;
    private final Clock clock;

    public MinuteCandleCollector(CandleHistoryClient candleClient,
                                 CandleHistoryRepository repository,
                                 TradingUniverseService universeService,
                                 BacktestDataProperties backtestProperties,
                                 Clock clock) {
        this.candleClient = candleClient;
        this.repository = repository;
        this.universeService = universeService;
        this.backtestProperties = backtestProperties;
        this.clock = clock;
    }

    /** 평일 15:40 KST — 타임컷(15:15) 이후 당일 장이 완결된 시점 */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectToday() {
        LocalDate today = LocalDate.now(clock);
        int savedTotal = 0;

        for (String stockCode : collectionTargets()) {
            try {
                savedTotal += collectSymbol(stockCode, today);
            } catch (Exception e) {
                log.warn("[MinuteCollector] {} 수집 실패 — 다음 종목 계속: {}", stockCode, e.getMessage());
            }
        }
        log.info("[MinuteCollector] {} 분봉 축적 완료: {}건", today, savedTotal);
    }

    /** 매매 유니버스 ∪ 백테스트 대형주 표본 ∪ 이벤트 통계 KOSDAQ 표본 (순서 보존 중복 제거) */
    Set<String> collectionTargets() {
        Set<String> targets = new LinkedHashSet<>(universeService.getActiveCodes());
        targets.addAll(backtestProperties.getSymbols());
        targets.addAll(backtestProperties.getEventSymbols());
        return targets;
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
