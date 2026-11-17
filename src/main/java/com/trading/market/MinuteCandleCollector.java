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
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 당일 분봉 전방 축적 배치 (B-1).
 *
 * KIS는 과거 분봉을 소급 제공하지 않으므로, 장 마감 후 매일 그날의 분봉을 저장해
 * 미래의 정밀 백테스트(분봉 기반 돌파 시각 재현) 표본을 지금부터 쌓는다.
 * DART 공시와 같은 이유로 자동 삭제하지 않는다.
 *
 * 수집 대상은 매매 유니버스 ∪ 백테스트 표본(symbols·eventSymbols·candidateSymbols) —
 * 분봉은 소급 불가라 표본 폭이 곧 축적 속도다. 1종목이면 시간창·거래량 필터 검증에
 * 필요한 표본(트레이드 100건+)까지 수년이 걸리지만, 종목 수만큼 단축된다.
 * candidateSymbols(§14.1 후보 54종목)는 진입 교체 트랙(§15)의 분봉 정밀 재검증 표본이다.
 * 매매 대상 확대(G1)와 무관한 수집 전용 확장이다.
 *
 * ⚠ 저장 전 (일자,시각) 중복 제거 필수 — KIS 페이지 경계에서 같은 분이 두 번 오는 날이
 * 있고(2026-07-20 전 종목 11:35 중복 실측), 중복 1건이면 유니크 제약으로 그 종목의
 * 저장 전체가 롤백돼 그날 축적이 통째로 0건이 된다.
 *
 * ⚠ 15:40 정시 배치를 놓친 날은 <b>영구 손실</b>이다(소급 조회 불가). 그래서 장 마감 후
 * 매시 캐치업을 돌려 앱이 꺼져 있었거나 수집이 중단된 날을 그날 안에 구제한다.
 * 종목별로 이미 저장된 당일 분봉은 건너뛰므로 반복 실행이 안전하다.
 */
@Component
@Profile("paper")
public class MinuteCandleCollector {

    private static final Logger log = LoggerFactory.getLogger(MinuteCandleCollector.class);

    private final CandleHistoryClient candleClient;
    private final CandleHistoryRepository repository;
    private final TradingUniverseService universeService;
    private final BacktestDataProperties backtestProperties;
    private final MarketCalendarService marketCalendar;
    private final Clock clock;

    /** 정시 배치와 캐치업이 겹쳐 같은 종목을 동시에 수취하지 않게 하는 문지기 */
    private final AtomicBoolean collecting = new AtomicBoolean(false);

    public MinuteCandleCollector(CandleHistoryClient candleClient,
                                 CandleHistoryRepository repository,
                                 TradingUniverseService universeService,
                                 BacktestDataProperties backtestProperties,
                                 MarketCalendarService marketCalendar,
                                 Clock clock) {
        this.candleClient = candleClient;
        this.repository = repository;
        this.universeService = universeService;
        this.backtestProperties = backtestProperties;
        this.marketCalendar = marketCalendar;
        this.clock = clock;
    }

    /** 평일 15:40 KST — 타임컷(15:15) 이후 당일 장이 완결된 시점 */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectToday() {
        collect("정시");
    }

    /**
     * 장 마감 후 매시 45분 캐치업 — 15:40에 앱이 꺼져 있었거나 수집이 중단된 날을 구제한다.
     * 당일 분봉은 소급 조회가 불가해 자정을 넘기면 영구 손실이므로, 그날 안에 여러 번 시도한다.
     */
    @Scheduled(cron = "0 45 16-22 * * MON-FRI", zone = "Asia/Seoul")
    public void catchUpToday() {
        collect("캐치업");
    }

    private void collect(String trigger) {
        LocalDate today = LocalDate.now(clock);
        if (!marketCalendar.isTradingDay(today)) {
            log.debug("[MinuteCollector] {} 휴장일 — {} 스킵", today, trigger);
            return;
        }
        if (!collecting.compareAndSet(false, true)) {
            log.info("[MinuteCollector] 수집이 이미 진행 중 — {} 트리거 스킵", trigger);
            return;
        }
        try {
            int savedTotal = 0;
            int skipped = 0;
            for (String stockCode : collectionTargets()) {
                try {
                    int saved = collectSymbol(stockCode, today);
                    savedTotal += saved;
                    if (saved == 0) skipped++;
                } catch (Exception e) {
                    log.warn("[MinuteCollector] {} 수집 실패 — 다음 종목 계속: {}", stockCode, e.getMessage());
                }
            }
            log.info("[MinuteCollector] {} 분봉 축적 완료({}): {}건 저장, {}종목 기존재·스킵",
                    today, trigger, savedTotal, skipped);
        } finally {
            collecting.set(false);
        }
    }

    /** 매매 유니버스 ∪ 백테스트 대형주 표본 ∪ 이벤트 통계 KOSDAQ 표본 ∪ §14.1 후보 (순서 보존 중복 제거) */
    Set<String> collectionTargets() {
        Set<String> targets = new LinkedHashSet<>(universeService.getActiveCodes());
        targets.addAll(backtestProperties.getSymbols());
        targets.addAll(backtestProperties.getEventSymbols());
        targets.addAll(backtestProperties.getCandidateSymbols());
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
        // (일자,시각) 중복 제거 — 페이지 경계 중복 1건이 저장 전체를 롤백시키는 사고 방지 (클래스 주석)
        Map<LocalTime, MinuteCandle> unique = new LinkedHashMap<>();
        for (MinuteCandle c : candles) {
            if (!today.equals(c.date())) continue;
            unique.putIfAbsent(c.time(), c);
        }
        int duplicates = (int) candles.stream().filter(c -> today.equals(c.date())).count()
                - unique.size();
        if (duplicates > 0) {
            log.warn("[MinuteCollector] {} 수취분에 중복 분봉 {}건 — 첫 값만 저장", stockCode, duplicates);
        }

        List<CandleHistory> rows = unique.values().stream()
                .map(c -> CandleHistory.ofMinute(stockCode, c))
                .toList();
        repository.saveAll(rows);
        return rows.size();
    }
}
