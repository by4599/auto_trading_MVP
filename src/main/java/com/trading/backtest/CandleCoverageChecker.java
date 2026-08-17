package com.trading.backtest;

import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.MarketCalendarService;
import com.trading.market.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 채점 전 캔들 커버리지 검사 — "이 창의 끝까지 캔들이 있는가"를 강제한다.
 *
 * <p>배경: 2026-07 §14.1 앵커는 판정 창 끝 2거래일이 빈 상태로 채점돼 그대로 기준선이 됐다
 * (`_workspace/7_quant_baseline-drift.md`). 백필 쪽 원인은 고쳤지만, <b>조용히 틀린 채
 * 기준선이 굳는</b> 실패 모드를 다시 만들지 않으려면 채점 직전에 한 번 더 확인해야 한다.
 *
 * <p>판정 기준은 <b>꼬리 지연</b>이다: 창 끝 이전 마지막 캔들 이후 창 끝까지 거래일이 하나라도
 * 남아 있으면 부족으로 본다. 구간 <i>안쪽</i>의 구멍은 보지 않는다 — 거래일 판정은
 * {@link MarketCalendarService}(market-calendar.yml)에 의존하는데 그 목록이 완전하지 않아
 * (예: 전 종목 캔들이 0건인 2026-07-17) 안쪽까지 검사하면 헛경보가 난다. 꼬리만 보면
 * 창 끝에 데이터가 도달했는지를 헛경보 없이 답할 수 있다.
 *
 * <p>결측이면 write-baseline 실행은 <b>중단</b>한다(오염된 기준선이 굳는 게 최악이다).
 * 그 외 실행은 경고 + 리포트 기재로 남기고 진행한다.
 */
@Component
@Profile("backtest")
public class CandleCoverageChecker {

    private static final Logger log = LoggerFactory.getLogger(CandleCoverageChecker.class);

    /** 창 끝에서 소급해 훑는 달력일수 — 백필 슬랙(7일)보다 넉넉하게 */
    static final int LOOKBACK_DAYS = 14;

    private final CandleHistoryRepository repository;
    private final MarketCalendarService calendar;

    /** 마지막 검사 결과 — 리포트가 읽어 간다 (백테스트는 단일 스레드 배치 실행) */
    private CandleCoverage lastReport;

    public CandleCoverageChecker(CandleHistoryRepository repository, MarketCalendarService calendar) {
        this.repository = repository;
        this.calendar = calendar;
    }

    /**
     * 검사하고 결과를 기록한다.
     *
     * @param strict true면(=기준선을 쓰는 실행) 부족 시 예외로 중단
     * @throws IllegalStateException strict 실행에서 커버리지 미달
     */
    public CandleCoverage verify(String mode, List<String> symbols, LocalDate windowEnd, boolean strict) {
        CandleCoverage coverage = check(symbols, windowEnd);
        this.lastReport = coverage;

        if (coverage.sufficient()) {
            log.info("[Coverage] {} — {}", mode, coverage.summary());
            return coverage;
        }
        String message = String.format(
                "[Coverage] %s — %s. 이 상태로 채점하면 창 끝자락이 데이터 없이 매겨진다"
                        + " (2026-07 §14.1 기준선 드리프트와 같은 결함)", mode, coverage.summary());
        if (strict) {
            throw new IllegalStateException(message
                    + " — write-baseline=true 실행이므로 중단한다. 백필로 채운 뒤 재실행할 것.");
        }
        log.warn("[Coverage] ⚠ {}", message);
        return coverage;
    }

    public Optional<CandleCoverage> lastReport() {
        return Optional.ofNullable(lastReport);
    }

    CandleCoverage check(List<String> symbols, LocalDate windowEnd) {
        LocalDate scanFrom = windowEnd.minusDays(LOOKBACK_DAYS);
        LocalDate frontier = latestCandleDate(symbols, scanFrom, windowEnd);
        LocalDate after = frontier != null ? frontier : scanFrom.minusDays(1);
        return new CandleCoverage(windowEnd, frontier,
                tradingDaysBetweenExclusive(after, windowEnd), LOOKBACK_DAYS);
    }

    /** 전 종목을 통틀어 [from, to] 안에서 가장 늦은 캔들 일자 (없으면 null) */
    private LocalDate latestCandleDate(List<String> symbols, LocalDate from, LocalDate to) {
        LocalDate latest = null;
        for (String stockCode : symbols) {
            List<CandleHistory> rows = repository
                    .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                            stockCode, Timeframe.DAILY, from, to);
            if (rows.isEmpty()) continue;
            LocalDate last = rows.get(rows.size() - 1).getCandleDate();
            if (latest == null || last.isAfter(latest)) latest = last;
        }
        return latest;
    }

    /** (after, to] 구간의 거래일 목록 */
    private List<LocalDate> tradingDaysBetweenExclusive(LocalDate after, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = after.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (calendar.isTradingDay(d)) days.add(d);
        }
        return List.copyOf(days);
    }
}
