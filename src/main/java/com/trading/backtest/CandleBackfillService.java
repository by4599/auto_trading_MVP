package com.trading.backtest;

import com.trading.market.Candle;
import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryClient;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.Timeframe;
import com.trading.universe.TradingUniverseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 백테스트 기동 시 candle_history 커버리지를 검사하고 부족분만 KIS에서 수취한다 (B-1).
 *
 * 커버리지 판정은 저장된 최소/최대 일자 기준의 증분 방식:
 *   - 저장 없음        → [from, to] 전체 수취
 *   - 최신 일자 < to   → [최신+1, to] 수취
 *   - 최초 일자 > from → [from, 최초-1] 수취
 * 유니크 인덱스(idx_candle_lookup)가 최후의 중복 방어선이지만, 증분 범위 계산으로
 * 애초에 겹치지 않게 요청한다.
 */
@Service
@Profile("backtest")
public class CandleBackfillService {

    private static final Logger log = LoggerFactory.getLogger(CandleBackfillService.class);

    private final CandleHistoryClient candleClient;
    private final CandleHistoryRepository repository;
    private final TradingUniverseService universeService;
    private final BacktestDataProperties properties;
    private final Clock clock;

    public CandleBackfillService(CandleHistoryClient candleClient,
                                 CandleHistoryRepository repository,
                                 TradingUniverseService universeService,
                                 BacktestDataProperties properties,
                                 Clock clock) {
        this.candleClient = candleClient;
        this.repository = repository;
        this.universeService = universeService;
        this.properties = properties;
        this.clock = clock;
    }

    /** 백테스트 대상 종목 = 유니버스 활성 종목 ∪ 설정 symbols (입력 순서 유지) */
    public List<String> targetSymbols() {
        Set<String> merged = new LinkedHashSet<>(universeService.getActiveCodes());
        merged.addAll(properties.getSymbols());
        return List.copyOf(merged);
    }

    /**
     * 재생 시작일 이전 워밍업(전일봉·ATR 14) 여유를 포함해 적재한다.
     *
     * <p>{@code backtest.backfill-from}이 설정돼 있으면 그 날짜를 저장 하한으로 쓴다 —
     * 판정 창(전역 from/to)은 여전히 now-years라서 기존 모드 결과는 바뀌지 않고,
     * "얼마나 오래된 것까지 저장하느냐"만 달라진다.
     */
    public LocalDate rangeFrom() {
        LocalDate override = properties.getBackfillFrom();
        if (override != null) {
            return override;
        }
        return LocalDate.now(clock).minusYears(properties.getYears())
                .minusDays(BacktestMarketDataService.WARMUP_CALENDAR_DAYS);
    }

    public LocalDate rangeTo() {
        return LocalDate.now(clock).minusDays(1); // 당일 미완성 봉 제외
    }

    /** 전 종목(+KOSPI) 백필. 수취·저장한 총 캔들 수를 반환한다. */
    public int backfillAll() {
        LocalDate from = rangeFrom();
        LocalDate to   = rangeTo();
        int saved = 0;

        for (String stockCode : targetSymbols()) {
            saved += backfillSymbol(stockCode, from, to, null);
        }
        if (properties.isIncludeKospi()) {
            saved += backfillSymbol(properties.getKospiStorageCode(), from, to, properties.getKospiCode());
            saved += backfillSymbol(properties.getKosdaqStorageCode(), from, to, properties.getKosdaqCode());
        }
        log.info("[Backfill] 완료: 기간 {}~{}, 신규 저장 {}건", from, to, saved);
        return saved;
    }

    /** 추가 표본 종목 백필 (B-4 이벤트 통계 등 — targetSymbols 밖의 종목) */
    public int backfillExtra(List<String> symbols) {
        LocalDate from = rangeFrom();
        LocalDate to   = rangeTo();
        int saved = 0;
        for (String stockCode : symbols) {
            try {
                saved += backfillSymbol(stockCode, from, to, null);
            } catch (Exception e) {
                // 잘못된 코드·상장폐지 등 한 종목의 실패가 전체를 멈추지 않는다
                log.warn("[Backfill] 추가 표본 실패 — 건너뜀: {} — {}", stockCode, e.getMessage());
            }
        }
        return saved;
    }

    /** @param indexCode null이면 개별 종목, 값이 있으면 해당 KIS 업종 코드의 지수 */
    private int backfillSymbol(String storageCode, LocalDate from, LocalDate to, String indexCode) {
        List<DateRange> gaps = missingRanges(storageCode, from, to);
        if (gaps.isEmpty()) {
            log.info("[Backfill] {} 커버리지 충족 — 스킵", storageCode);
            return 0;
        }

        int saved = 0;
        for (DateRange gap : gaps) {
            List<Candle> fetched = indexCode != null
                    ? candleClient.fetchIndexDailyCandles(indexCode, gap.from(), gap.to())
                    : candleClient.fetchDailyCandles(storageCode, gap.from(), gap.to());
            List<CandleHistory> rows = new ArrayList<>(fetched.size());
            for (Candle c : fetched) {
                rows.add(CandleHistory.ofDaily(storageCode, c));
            }
            repository.saveAll(rows);
            saved += rows.size();
            log.info("[Backfill] {} {}~{} → {}건 저장", storageCode, gap.from(), gap.to(), rows.size());
            warnIfIncomplete(storageCode, gap, fetched.size());
        }
        return saved;
    }

    // ── 불완전 수취 감지 ──────────────────────────────────────────────────────
    //
    // KIS 일봉 TR은 호출당 상한이 있고(실측: 종목 100행·지수 50행) 클라이언트가 날짜 커서로
    // 페이지네이션하지만, 페이지 상한(MAX_PAGES)이나 일시 오류로 일부만 받고도 "성공"으로
    // 보일 수 있다. 소급 백필처럼 긴 구간을 한 번에 요청할 때 이 구멍이 가장 위험하므로
    // 기대 거래일 수 대비 현저히 적으면 경고를 남긴다.

    /** 기대 평일 수 대비 이 비율 미만이면 불완전 수취로 본다 (공휴일 여유 포함) */
    static final double COVERAGE_WARN_RATIO = 0.80;

    /** 이보다 짧은 gap은 검사하지 않는다 — 연휴 낀 증분 수취의 헛경보 방지 */
    static final int COVERAGE_MIN_WEEKDAYS = 20;

    private void warnIfIncomplete(String storageCode, DateRange gap, int received) {
        long weekdays = weekdaysBetween(gap.from(), gap.to());
        if (!isSuspiciouslyIncomplete(weekdays, received)) return;
        log.warn("[Backfill] ⚠ {} {}~{} 수취 {}건 — 기대 거래일(평일 {}일)에 크게 못 미친다. "
                        + "상장 전 구간·장기 거래정지면 정상이지만, 아니라면 API 페이지 상한/일시 오류로 "
                        + "데이터에 구멍이 난 것이니 재실행해 확인할 것",
                storageCode, gap.from(), gap.to(), received, weekdays);
    }

    /** [from, to] 양끝 포함 평일 수 (한국 공휴일은 세지 않는다 — 근사 상한) */
    static long weekdaysBetween(LocalDate from, LocalDate to) {
        long count = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) count++;
        }
        return count;
    }

    /** 수취 건수가 기대 거래일 수에 크게 못 미치는가 (짧은 구간은 판정하지 않음) */
    static boolean isSuspiciouslyIncomplete(long expectedWeekdays, int received) {
        if (expectedWeekdays < COVERAGE_MIN_WEEKDAYS) return false;
        return received < expectedWeekdays * COVERAGE_WARN_RATIO;
    }

    record DateRange(LocalDate from, LocalDate to) {}

    /** 저장 범위와 요청 범위를 비교해 수취가 필요한 구간 목록을 돌려준다. */
    List<DateRange> missingRanges(String storageCode, LocalDate from, LocalDate to) {
        var earliest = repository.findFirstByStockCodeAndTimeframeOrderByCandleDateAsc(
                storageCode, Timeframe.DAILY);
        var latest = repository.findFirstByStockCodeAndTimeframeOrderByCandleDateDesc(
                storageCode, Timeframe.DAILY);

        if (earliest.isEmpty() || latest.isEmpty()) {
            return List.of(new DateRange(from, to));
        }

        List<DateRange> gaps = new ArrayList<>();
        LocalDate storedFrom = earliest.get().getCandleDate();
        LocalDate storedTo   = latest.get().getCandleDate();

        // 앞쪽 공백 — 휴장일 여유 7일: 저장 최초일이 from+7 이내면 이미 커버로 간주
        if (storedFrom.isAfter(from.plusDays(7))) {
            gaps.add(new DateRange(from, storedFrom.minusDays(1)));
        }
        // 뒤쪽 공백 — 최신 저장일 이후 7일 넘게 비면 증분 수취
        if (storedTo.isBefore(to.minusDays(7))) {
            gaps.add(new DateRange(storedTo.plusDays(1), to));
        }
        return gaps;
    }
}
