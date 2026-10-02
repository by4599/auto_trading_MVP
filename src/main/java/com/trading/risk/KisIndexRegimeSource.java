package com.trading.risk;

import com.trading.NotificationService;
import com.trading.market.Candle;
import com.trading.market.CandleHistoryClient;
import com.trading.market.MarketCalendarService;
import com.trading.strategy.FilterProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 모의투자 지수 추세 데이터원 (2026-10-01) — KOSPI 일봉을 <b>하루 한 번</b> KIS에서 받아 캐시하고
 * "전일 종가 &lt; 전일까지의 N일 이동평균"(BACKTEST-DESIGN §14.4)을 판정한다.
 *
 * <p>전에는 {@code NoOpIndexRegimeSource}가 항상 판정 불가(empty)를 돌려줬고 {@link IndexTrendRule}은
 * empty면 통과시키므로, 필터를 켜도 아무것도 막지 못했다 — B-3에서 KOSPI 미적재로 갭다운 필터가
 * 몇 달간 무발동한 사고(§14.4 부수 발견)와 같은 모양이다. 데이터는 백테스트 백필과 같은 TR
 * ({@link CandleHistoryClient#fetchIndexDailyCandles}, KisApiClient 레이트리밋 경유)에서 온다.
 *
 * <p>호출 규율:
 * <ul>
 *   <li>지수 일봉은 하루에 한 번만 바뀐다 — 그날 성공하면 다시 부르지 않는다(틱마다 부르지 않는다).</li>
 *   <li>실패하면 <b>마지막으로 성공한 판정</b>을 그대로 쓴다. 한 번도 성공하지 못했을 때만 판정 불가 →
 *       {@link IndexTrendDataGateRule}이 매수를 막는다(fail-closed). 판정이 서지 않는 응답(MA 표본 부족 —
 *       중간 페이지가 빈 채 끝난 부분 응답)도 실패다(감사 M-1): 성공으로 저장하면 어제의 정상 판정이 지워지고
 *       "오늘 받았다"가 되어 재시도도 없이 관문이 종일 조용히 매수를 막는다.</li>
 *   <li>거래일 <b>개장 1분 뒤</b>~마감 사이에만 부른다(평일 09:01~, 감사 M-2). 장외 실패는 뒤따르는 성공 호출이
 *       없어 KisApiClient 연속 실패 카운터에 쌓이고, 장전(예전 08:30) 실패는 "눈먼 시간" 시작점을 장전에 남겨
 *       09:00 첫 호출 2건만 실패해도 즉시 SAFE_MODE로 보낸다. 개장 후 메인 루프의 성공 호출 사이에 끼워 넣는다.
 *       대가: 재기동한 날은 첫 판정 전(약 1분) A동 매수가 보류된다. 실패 후 재시도는 30분 간격.</li>
 *   <li>조회는 전용 스레드에서 한다. 스케줄러 풀이 1개라 그 스레드에서 KIS를 기다리면
 *       RiskMonitor·StopLossMonitor 감시가 함께 멈춘다(BackgroundAlertSender와 같은 이유).</li>
 * </ul>
 */
@Component
@Profile("paper")
public class KisIndexRegimeSource implements IndexRegimeSource {

    private static final Logger log = LoggerFactory.getLogger(KisIndexRegimeSource.class);

    static final String KOSPI_INDEX_CODE = "0001";
    /** MA200까지 담는 여유(약 270거래일) — 설정이 기본값(200)으로 되돌아가도 판정 불가가 되지 않게 */
    static final int LOOKBACK_CALENDAR_DAYS = 400;
    /** 개장 후 이만큼 지나서 부른다 — 메인 루프의 성공 호출이 먼저 나가게 (감사 M-2) */
    static final Duration REFRESH_DELAY_AFTER_OPEN = Duration.ofMinutes(1);
    static final Duration RETRY_INTERVAL = Duration.ofMinutes(30);

    private final CandleHistoryClient candleClient;
    private final MarketCalendarService calendar;
    private final FilterProperties filters;
    private final NotificationService notifier;
    private final Clock clock;

    private final ExecutorService refresher = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "index-trend-refresh");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    /** 마지막으로 성공한 조회 — null이면 기동 후 한 번도 성공하지 못한 것(= 판정 불가) */
    private volatile IndexCloses lastSuccess;
    private volatile Instant lastAttemptAt;
    private volatile LocalDate lastAlertDate;

    public KisIndexRegimeSource(CandleHistoryClient candleClient,
                                MarketCalendarService calendar,
                                FilterProperties filters,
                                NotificationService notifier,
                                Clock clock) {
        this.candleClient = candleClient;
        this.calendar = calendar;
        this.filters = filters;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** 갭다운(하루짜리) 판정은 모의투자에서 구현하지 않았다 — 필터 기본 OFF, 검증된 설정에도 없다 */
    @Override
    public Optional<Boolean> isBearishRegime() {
        return Optional.empty();
    }

    @Override
    public Optional<Boolean> isBelowTrend(int maPeriod) {
        IndexCloses snapshot = lastSuccess;
        if (snapshot == null) return Optional.empty();
        return IndexTrendCalculator.belowTrend(snapshot.closes(), LocalDate.now(clock), maPeriod);
    }

    /** 1분마다 "받을 때가 됐나"만 본다 — 실제 조회는 전용 스레드로 넘겨 스케줄 스레드를 막지 않는다 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void scheduleRefresh() {
        if (!isRefreshDue() || !inFlight.compareAndSet(false, true)) return;
        try {
            refresher.execute(() -> {
                try {
                    refreshIfDue();
                } finally {
                    inFlight.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.set(false);
            log.warn("[IndexTrend] 조회 작업을 넘기지 못했다 — 다음 틱에 다시 본다: {}", e.getMessage());
        }
    }

    /** 조건이 맞을 때만 KIS를 1회(페이지 포함) 부른다. 성공하면 true — 테스트는 이것을 직접 부른다 */
    boolean refreshIfDue() {
        if (!isRefreshDue()) return false;
        lastAttemptAt = clock.instant();
        LocalDate today = LocalDate.now(clock);
        try {
            NavigableMap<LocalDate, Double> closes = fetchCloses(today);
            IndexTrendCalculator.Reading reading = readOrFail(closes, today);
            lastSuccess = new IndexCloses(today, closes);
            logVerdict(closes.size(), reading);
            return true;
        } catch (RuntimeException e) {
            onFailure(today, e);
            return false;
        }
    }

    boolean isRefreshDue() {
        if (!filters.getIndexTrend().isEnabled()) return false;   // 필터가 꺼져 있으면 부를 이유가 없다
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        if (!calendar.isTradingDay(today)) return false;
        LocalTime time = now.toLocalTime();
        LocalTime windowStart = calendar.openTime(today).plus(REFRESH_DELAY_AFTER_OPEN);
        if (time.isBefore(windowStart) || time.isAfter(calendar.closeTime(today))) return false;

        IndexCloses snapshot = lastSuccess;
        if (snapshot != null && snapshot.fetchedOn().equals(today)) return false;   // 오늘 이미 받았다
        Instant attempted = lastAttemptAt;
        return attempted == null || !clock.instant().isBefore(attempted.plus(RETRY_INTERVAL));
    }

    /** 오늘(미완성) 봉과 종가 0 이하 행은 버린다 — 판정은 전일까지의 완성 봉만 쓴다 */
    private NavigableMap<LocalDate, Double> fetchCloses(LocalDate today) {
        List<Candle> candles = candleClient.fetchIndexDailyCandles(
                KOSPI_INDEX_CODE, today.minusDays(LOOKBACK_CALENDAR_DAYS), today.minusDays(1));
        TreeMap<LocalDate, Double> closes = new TreeMap<>();
        for (Candle c : candles) {
            if (c.date() == null || !c.date().isBefore(today) || c.getClose() <= 0) continue;
            closes.put(c.date(), c.getClose());
        }
        return Collections.unmodifiableNavigableMap(closes);
    }

    /** 판정이 서는 응답만 성공이다 — 비었거나 MA 표본이 모자라면(부분 응답) 실패로 던진다 (감사 M-1) */
    private IndexTrendCalculator.Reading readOrFail(NavigableMap<LocalDate, Double> closes, LocalDate today) {
        if (closes.isEmpty()) {
            throw new IllegalStateException("KOSPI 일봉 응답이 비어 있음(오류 본문 가능)");
        }
        int period = filters.getIndexTrend().getMaPeriod();
        return IndexTrendCalculator.read(closes, today, period).orElseThrow(() -> new IllegalStateException(
                String.format("KOSPI 일봉 %d건(%s~%s)뿐 — MA%d 표본 부족(부분 응답 의심)",
                        closes.size(), closes.firstKey(), closes.lastKey(), period)));
    }

    private void logVerdict(int count, IndexTrendCalculator.Reading r) {
        int period = filters.getIndexTrend().getMaPeriod();
        log.info("[IndexTrend] KOSPI 일봉 {}건 수취 — {} 종가 {} · MA{} {} ({}%) → {}",
                count, r.previousDate(), String.format("%.2f", r.previousClose()), period,
                String.format("%.2f", r.movingAverage()), String.format("%+.2f", r.gapPercent()),
                r.below() ? "하락 추세: 신규 매수 금지" : "추세 위: 신규 매수 허용");
    }

    private void onFailure(LocalDate today, RuntimeException e) {
        IndexCloses snapshot = lastSuccess;
        String held = snapshot == null
                ? "한 번도 받지 못해 판정 불가 — 신규 매수 보류 중(fail-closed)"
                : String.format("마지막 성공 판정 유지(%s 수취, 최신 종가일 %s)",
                        snapshot.fetchedOn(), snapshot.closes().lastKey());
        log.warn("[IndexTrend] KOSPI 일봉 갱신 실패 — {} · {}분 뒤 재시도: {}",
                held, RETRY_INTERVAL.toMinutes(), e.toString());
        // 텔레그램은 장중에만 나간다 — 장전 실패로 하루 1번 몫을 써 버리면 정작 장중 실패가 조용해진다
        if (!today.equals(lastAlertDate) && calendar.isDuringMarketHoursNow()) {
            lastAlertDate = today;
            notifier.sendCritical("⚠️ [지수 추세 필터] KOSPI 일봉 조회 실패 — " + held);
        }
    }

    @PreDestroy
    void shutdown() {
        refresher.shutdownNow();
    }

    private record IndexCloses(LocalDate fetchedOn, NavigableMap<LocalDate, Double> closes) {}
}
