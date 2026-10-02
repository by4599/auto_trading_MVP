package com.trading.risk;

import com.trading.NotificationService;
import com.trading.backtest.MutableClock;
import com.trading.market.Candle;
import com.trading.market.CandleHistoryClient;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.strategy.FilterProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 지수 데이터원 감사 수정 (2026-10-01).
 * <ul>
 *   <li>M-1: 중간 페이지가 빈 채 끝난 <b>부분 응답</b>(MA 표본 부족)이 성공으로 저장돼, 어제의 정상 판정이
 *       지워지고 "오늘 받았다"가 되어 재시도 없이 관문이 종일 조용히 매수를 막았다.</li>
 *   <li>M-2: 08:30 장전 조회 실패가 "눈먼 시간" 시작점을 장전에 남겨, 09:00 첫 호출 2건만 실패해도
 *       즉시 SAFE_MODE로 갔다(872afce가 없앤 "짧은 삐끗에 정지"의 재현).</li>
 * </ul>
 */
@DisplayName("KisIndexRegimeSource — 부분 응답은 실패 · 조회는 개장 1분 뒤부터")
class KisIndexRegimeSourceRefreshGuardTest {

    private static final LocalDate THU = LocalDate.of(2026, 10, 1);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 2);

    private MutableClock clock;
    private CandleHistoryClient kis;
    private NotificationService notifier;
    private MarketCalendarProperties calendarProps;
    private KisIndexRegimeSource sut;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.EPOCH);
        kis = mock(CandleHistoryClient.class);           // 인터페이스만 목
        notifier = mock(NotificationService.class);
        FilterProperties filters = new FilterProperties();
        filters.getIndexTrend().setEnabled(true);
        filters.getIndexTrend().setMaPeriod(120);
        calendarProps = new MarketCalendarProperties();
        sut = new KisIndexRegimeSource(kis, new MarketCalendarService(calendarProps, clock), filters, notifier, clock);
    }

    /** lastDate까지 평일 count개 — 앞은 base, 마지막 봉만 lastClose (MA120 대비 위치를 정한다) */
    private static List<Candle> kospi(LocalDate lastDate, int count, double base, double lastClose) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = lastDate; dates.size() < count; d = d.minusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) dates.add(0, d);
        }
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            double close = i == dates.size() - 1 ? lastClose : base;
            out.add(new Candle(dates.get(i), close, close, close, close, 0));
        }
        return out;
    }

    private void respond(List<Candle> candles) {
        when(kis.fetchIndexDailyCandles(anyString(), any(), any())).thenReturn(candles);
    }

    private void verifyCalls(int n) {
        verify(kis, times(n)).fetchIndexDailyCandles(anyString(), any(), any());
    }

    // ── M-1 부분 응답 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("부분 응답(MA120 표본 부족)은 실패 — 어제 판정 보존 · 장중 알림 · 30분 뒤 다시 받는다")
    void partial_response_keeps_last_verdict_and_retries() {
        clock.setTo(THU, LocalTime.of(9, 10));
        respond(kospi(THU.minusDays(1), 200, 7_063, 6_894));     // 정상 응답 → 하락 추세
        assertThat(sut.refreshIfDue()).isTrue();

        clock.setTo(FRI, LocalTime.of(9, 10));
        respond(kospi(THU, 100, 7_063, 7_200));                   // 첫 페이지만 — 중간 페이지가 빈 채 끝났다
        assertThat(sut.refreshIfDue()).isFalse();
        assertThat(sut.isBelowTrend(120)).contains(true);         // 어제 판정 유지 (예전: 판정 불가로 덮였다)
        verify(notifier, times(1)).sendCritical(anyString());     // WARN 로그만 남고 조용하지 않다

        clock.setTo(FRI, LocalTime.of(9, 25));
        assertThat(sut.isRefreshDue()).isFalse();                 // 재시도 간격 30분
        clock.setTo(FRI, LocalTime.of(9, 40));
        assertThat(sut.isRefreshDue()).isTrue();                  // "오늘 받았다"로 오인하지 않는다
        respond(kospi(THU, 200, 7_063, 7_200));
        assertThat(sut.refreshIfDue()).isTrue();
        assertThat(sut.isBelowTrend(120)).contains(false);
        verifyCalls(3);
    }

    @Test
    @DisplayName("첫 조회가 부분 응답이어도 실패 — 판정 불가(fail-closed)로 두고 30분 뒤 재시도한다")
    void partial_first_response_is_retried() {
        clock.setTo(THU, LocalTime.of(9, 10));
        respond(kospi(THU.minusDays(1), 100, 7_063, 6_894));
        assertThat(sut.refreshIfDue()).isFalse();
        assertThat(sut.isBelowTrend(120)).isEmpty();

        clock.setTo(THU, LocalTime.of(9, 40));
        respond(kospi(THU.minusDays(1), 200, 7_063, 6_894));
        assertThat(sut.refreshIfDue()).isTrue();
        assertThat(sut.isBelowTrend(120)).contains(true);
    }

    // ── M-2 조회 창 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("지수 조회는 09:00 이전엔 안 나간다 — 개장 1분 뒤(09:01)부터")
    void never_fetches_before_open() {
        respond(kospi(THU.minusDays(1), 200, 7_063, 6_894));
        for (LocalTime t : List.of(LocalTime.of(8, 30), LocalTime.of(8, 45), LocalTime.of(8, 59, 59),
                LocalTime.of(9, 0), LocalTime.of(9, 0, 59))) {
            clock.setTo(THU, t);
            assertThat(sut.refreshIfDue()).as("시각 %s", t).isFalse();
        }
        verify(kis, never()).fetchIndexDailyCandles(anyString(), any(), any());

        clock.setTo(THU, LocalTime.of(9, 1));
        assertThat(sut.refreshIfDue()).isTrue();
        verifyCalls(1);
    }

    @Test
    @DisplayName("개장이 늦는 날(수능일 등)은 그날 개장 1분 뒤부터 — 고정 09:01이 아니다")
    void follows_late_open_days() {
        MarketCalendarProperties.EarlyOpenDay lateOpen = new MarketCalendarProperties.EarlyOpenDay();
        lateOpen.setDate(THU);
        lateOpen.setOpenTime(LocalTime.of(10, 0));
        lateOpen.setCloseTime(LocalTime.of(16, 30));
        calendarProps.setEarlyOpenDays(List.of(lateOpen));
        respond(kospi(THU.minusDays(1), 200, 7_063, 6_894));

        clock.setTo(THU, LocalTime.of(9, 30));
        assertThat(sut.refreshIfDue()).isFalse();
        clock.setTo(THU, LocalTime.of(10, 1));
        assertThat(sut.refreshIfDue()).isTrue();
    }
}
