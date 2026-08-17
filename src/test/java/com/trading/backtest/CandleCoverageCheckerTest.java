package com.trading.backtest;

import com.trading.market.Candle;
import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.market.Timeframe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 채점 전 캔들 커버리지 검사 — 창 끝까지 데이터가 없는데 조용히 채점되던 실패 모드
 * (2026-07 §14.1 기준선 드리프트)를 구조적으로 막는지 본다.
 *
 * <p>Java 25 인라인 목 제약 — 리포지토리(인터페이스)만 목이고 캘린더는 실객체다.
 */
class CandleCoverageCheckerTest {

    private static final LocalDate WINDOW_END = LocalDate.of(2026, 7, 21);   // 화요일
    private static final List<String> SYMBOLS = List.of("005930", "000660");

    private CandleHistoryRepository repository;
    private MarketCalendarProperties calendarProps;
    private CandleCoverageChecker sut;

    @BeforeEach
    void setUp() {
        repository = mock(CandleHistoryRepository.class);
        calendarProps = new MarketCalendarProperties();
        sut = new CandleCoverageChecker(repository,
                new MarketCalendarService(calendarProps, Clock.systemUTC()));
        stubCandlesUpTo(null);   // 기본: 데이터 없음
    }

    @Test
    @DisplayName("창 끝까지 캔들이 있으면 충족 — 기준선 실행도 통과시킨다")
    void verify_coveredWindowPasses() {
        stubCandlesUpTo(WINDOW_END);

        CandleCoverage coverage = sut.verify("risk-lab", SYMBOLS, WINDOW_END, true);

        assertThat(coverage.sufficient()).isTrue();
        assertThat(coverage.coveredThrough()).isEqualTo(WINDOW_END);
        assertThat(coverage.missingTradingDays()).isEmpty();
    }

    @Test
    @DisplayName("창 끝 2거래일이 비면 결측으로 잡는다 (07-17 휴장 반영 — 앵커 드리프트 재현)")
    void verify_missingTailIsDetected() {
        calendarProps.setHolidays(List.of(LocalDate.of(2026, 7, 17)));
        stubCandlesUpTo(LocalDate.of(2026, 7, 16));

        CandleCoverage coverage = sut.verify("risk-lab", SYMBOLS, WINDOW_END, false);

        assertThat(coverage.sufficient()).isFalse();
        assertThat(coverage.coveredThrough()).isEqualTo(LocalDate.of(2026, 7, 16));
        assertThat(coverage.missingTradingDays()).containsExactly(
                LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 21));
    }

    @Test
    @DisplayName("기준선을 쓰는 실행(write-baseline=true)은 커버리지 미달이면 중단된다")
    void verify_strictRunAbortsOnGap() {
        stubCandlesUpTo(LocalDate.of(2026, 7, 16));

        assertThatThrownBy(() -> sut.verify("risk-lab", SYMBOLS, WINDOW_END, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("판정 창 끝")
                .hasMessageContaining("write-baseline");
    }

    @Test
    @DisplayName("대조·점검 실행은 미달이어도 진행하되 결과를 남긴다 (리포트가 읽어 간다)")
    void verify_nonStrictRunRecordsAndContinues() {
        stubCandlesUpTo(LocalDate.of(2026, 7, 16));

        sut.verify("regime-lab", SYMBOLS, WINDOW_END, false);

        assertThat(sut.lastReport()).isPresent();
        assertThat(sut.lastReport().orElseThrow().sufficient()).isFalse();
        assertThat(sut.lastReport().orElseThrow().markdownLine()).contains("데이터 커버리지");
    }

    @Test
    @DisplayName("창 끝이 휴장일이면 직전 거래일까지만 있어도 충족이다 (헛경보 방지)")
    void verify_nonTradingWindowEndIsNotMissing() {
        LocalDate saturday = LocalDate.of(2026, 7, 25);
        stubCandlesUpTo(LocalDate.of(2026, 7, 24));

        assertThat(sut.check(SYMBOLS, saturday).sufficient()).isTrue();
    }

    @Test
    @DisplayName("조회 구간에 캔들이 하나도 없으면 마지막 캔들 없음 + 전 구간 결측으로 본다")
    void check_noCandlesAtAll() {
        CandleCoverage coverage = sut.check(SYMBOLS, WINDOW_END);

        assertThat(coverage.coveredThrough()).isNull();
        assertThat(coverage.sufficient()).isFalse();
        assertThat(coverage.missingTradingDays()).isNotEmpty();
        assertThat(coverage.summary()).contains("판정 창 끝");
    }

    /** 전 종목이 lookback 시작일부터 storedTo까지 평일 캔들을 갖고 있다고 스텁 (null이면 없음) */
    private void stubCandlesUpTo(LocalDate storedTo) {
        List<CandleHistory> rows = new ArrayList<>();
        if (storedTo != null) {
            LocalDate scanFrom = WINDOW_END.minusDays(CandleCoverageChecker.LOOKBACK_DAYS);
            for (LocalDate d = scanFrom; !d.isAfter(storedTo); d = d.plusDays(1)) {
                rows.add(CandleHistory.ofDaily("005930", new Candle(d, 1, 1, 1, 1, 1)));
            }
        }
        when(repository.findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                any(), eq(Timeframe.DAILY), any(), any())).thenReturn(rows);
    }
}
