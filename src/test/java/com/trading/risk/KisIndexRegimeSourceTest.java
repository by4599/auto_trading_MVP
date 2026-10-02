package com.trading.risk;

import com.trading.NotificationService;
import com.trading.backtest.MutableClock;
import com.trading.market.Candle;
import com.trading.market.CandleHistoryClient;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.market.MinuteCandle;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 모의투자 지수 추세 데이터원 — "하루 1회 받아 캐시, 실패하면 마지막 성공 판정 유지,
 * 한 번도 성공 못 했을 때만 판정 불가".
 *
 * <p>2026-10-01 이전에는 paper 데이터원이 항상 판정 불가(empty)라, 지수 추세 필터를 켜도
 * 아무것도 막지 못했다(B-3 갭다운 필터 무발동 사고와 같은 모양).
 */
@DisplayName("KisIndexRegimeSource — KOSPI 일봉 하루 1회 캐시 · 실패 시 마지막 판정 유지")
class KisIndexRegimeSourceTest {

    private static final LocalDate THU = LocalDate.of(2026, 10, 1);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 2);
    private static final LocalDate SAT = LocalDate.of(2026, 10, 3);

    private MutableClock clock;
    private FakeCandleClient kis;
    private NotificationService notifier;
    private FilterProperties filters;
    private KisIndexRegimeSource sut;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.EPOCH);
        clock.setTo(THU, LocalTime.of(9, 10));      // 조회 창(개장 1분 뒤~마감) 안 — 감사 M-2로 08:30 시작에서 옮겼다
        kis = new FakeCandleClient();
        notifier = mock(NotificationService.class);
        filters = new FilterProperties();
        filters.getIndexTrend().setEnabled(true);
        filters.getIndexTrend().setMaPeriod(120);
        sut = new KisIndexRegimeSource(kis, new MarketCalendarService(new MarketCalendarProperties(), clock),
                filters, notifier, clock);
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    /** lastDate까지 평일 count개 — 앞은 전부 base, 마지막 봉만 lastClose (MA120 대비 위치를 정한다) */
    private static List<Candle> kospi(LocalDate lastDate, int count, double base, double lastClose) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = lastDate; dates.size() < count; d = d.minusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) dates.add(0, d);
        }
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            double close = i == dates.size() - 1 ? lastClose : base;
            candles.add(new Candle(dates.get(i), close, close, close, close, 0));
        }
        return candles;
    }

    /** 전일(9/30) 종가가 MA120 아래 — 2026-09-18 실측(6894 vs MA120 7063, −2.39%)과 같은 방향 */
    private static List<Candle> belowTrendSeries(LocalDate lastDate) {
        return kospi(lastDate, 200, 7_063, 6_894);
    }

    private static List<Candle> aboveTrendSeries(LocalDate lastDate) {
        return kospi(lastDate, 200, 7_063, 7_200);
    }

    // ── 판정 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("지수 전일 종가가 MA120 아래면 '하락 추세' 판정")
    void below_ma120_is_bearish_trend() {
        kis.respond(() -> belowTrendSeries(THU.minusDays(1)));

        assertThat(sut.refreshIfDue()).isTrue();

        assertThat(sut.isBelowTrend(120)).contains(true);
        assertThat(kis.lastTo).isEqualTo(THU.minusDays(1));  // 오늘 봉은 애초에 요청하지 않는다
    }

    @Test
    @DisplayName("지수 전일 종가가 MA120 위면 '하락 추세 아님'")
    void above_ma120_is_not_bearish() {
        kis.respond(() -> aboveTrendSeries(THU.minusDays(1)));

        sut.refreshIfDue();

        assertThat(sut.isBelowTrend(120)).contains(false);
    }

    @Test
    @DisplayName("응답에 오늘(미완성) 봉이 섞여 와도 판정에 쓰지 않는다")
    void ignores_todays_partial_bar() {
        List<Candle> withToday = new ArrayList<>(belowTrendSeries(THU.minusDays(1)));
        withToday.add(new Candle(THU, 99_999, 99_999, 99_999, 99_999, 0));
        kis.respond(() -> withToday);

        sut.refreshIfDue();

        assertThat(sut.isBelowTrend(120)).contains(true);
    }

    // ── 호출 규율 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("KIS는 하루 1회만 부른다 — 틱마다 판정해도 추가 호출 없음, 다음 거래일에 1회 더")
    void calls_kis_once_per_trading_day() {
        kis.respond(() -> belowTrendSeries(THU.minusDays(1)));

        sut.refreshIfDue();
        for (int i = 0; i < 1_000; i++) {
            sut.isBelowTrend(120);          // 매수 신호마다 룰이 부르는 경로
        }
        clock.setTo(THU, LocalTime.of(14, 0));
        sut.refreshIfDue();
        sut.scheduleRefresh();              // 스케줄 틱도 오늘은 할 일이 없다
        assertThat(kis.indexCalls).isEqualTo(1);

        clock.setTo(FRI, LocalTime.of(9, 1));
        kis.respond(() -> belowTrendSeries(THU));
        sut.refreshIfDue();
        assertThat(kis.indexCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("장외·휴장일에는 부르지 않는다 — 장외 실패가 연속 실패 카운터에 쌓이지 않게")
    void does_not_call_outside_window() {
        kis.respond(() -> belowTrendSeries(THU.minusDays(1)));

        clock.setTo(THU, LocalTime.of(8, 0));
        assertThat(sut.refreshIfDue()).isFalse();
        clock.setTo(THU, LocalTime.of(20, 0));
        assertThat(sut.refreshIfDue()).isFalse();
        clock.setTo(SAT, LocalTime.of(10, 0));
        assertThat(sut.refreshIfDue()).isFalse();

        assertThat(kis.indexCalls).isZero();
    }

    @Test
    @DisplayName("필터가 꺼져 있으면 KIS를 아예 부르지 않는다")
    void filter_off_never_calls() {
        filters.getIndexTrend().setEnabled(false);

        assertThat(sut.refreshIfDue()).isFalse();
        assertThat(kis.indexCalls).isZero();
    }

    // ── 실패 처리 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("한 번도 성공 못 했으면 판정 불가(empty) — 통과로 위장하지 않는다")
    void never_succeeded_is_undetermined() {
        kis.fail();

        assertThat(sut.refreshIfDue()).isFalse();

        assertThat(sut.isBelowTrend(120)).isEmpty();
    }

    @Test
    @DisplayName("갱신이 실패해도 마지막으로 성공한 판정을 그대로 쓴다")
    void keeps_last_successful_verdict_on_failure() {
        kis.respond(() -> belowTrendSeries(THU.minusDays(1)));
        sut.refreshIfDue();

        clock.setTo(FRI, LocalTime.of(9, 10));
        kis.fail();
        assertThat(sut.refreshIfDue()).isFalse();

        assertThat(sut.isBelowTrend(120)).contains(true);
    }

    @Test
    @DisplayName("빈 응답(오류 본문 등)도 실패로 친다 — 빈 데이터로 판정을 덮어쓰지 않는다")
    void empty_response_is_failure() {
        kis.respond(() -> aboveTrendSeries(THU.minusDays(1)));
        sut.refreshIfDue();

        clock.setTo(FRI, LocalTime.of(9, 10));
        kis.respond(List::of);
        assertThat(sut.refreshIfDue()).isFalse();

        assertThat(sut.isBelowTrend(120)).contains(false);
    }

    @Test
    @DisplayName("실패 후 재시도는 30분 간격 — 그 사이에는 부르지 않는다")
    void retries_only_after_interval() {
        kis.fail();
        clock.setTo(THU, LocalTime.of(9, 10));
        sut.refreshIfDue();

        clock.setTo(THU, LocalTime.of(9, 25));
        sut.refreshIfDue();
        assertThat(kis.indexCalls).isEqualTo(1);

        clock.setTo(THU, LocalTime.of(9, 40));
        kis.respond(() -> belowTrendSeries(THU.minusDays(1)));
        assertThat(sut.refreshIfDue()).isTrue();
        assertThat(kis.indexCalls).isEqualTo(2);
        assertThat(sut.isBelowTrend(120)).contains(true);
    }

    @Test
    @DisplayName("실패 알림은 장중에 하루 1번 — 장전에는 조회 자체가 없어 알림 몫을 쓰지 않는다")
    void alerts_once_per_day_during_market_hours() {
        kis.fail();

        clock.setTo(THU, LocalTime.of(8, 40));      // 장전 — 조회 창(개장 1분 뒤~) 밖이라 부르지도 않는다
        sut.refreshIfDue();
        verify(notifier, never()).sendCritical(anyString());

        clock.setTo(THU, LocalTime.of(9, 10));
        sut.refreshIfDue();
        clock.setTo(THU, LocalTime.of(9, 40));
        sut.refreshIfDue();
        verify(notifier, times(1)).sendCritical(anyString());
    }

    /** KIS 대역 — 지수 일봉 호출 횟수를 세고, 정해 둔 응답(또는 예외)을 돌려준다 */
    private static final class FakeCandleClient implements CandleHistoryClient {
        private Supplier<List<Candle>> response = List::of;
        private int indexCalls;
        private LocalDate lastTo;

        void respond(Supplier<List<Candle>> supplier) {
            this.response = supplier;
        }

        void fail() {
            this.response = () -> {
                throw new IllegalStateException("HTTP 500 — 모의 서버 지연");
            };
        }

        @Override
        public List<Candle> fetchIndexDailyCandles(String indexCode, LocalDate from, LocalDate to) {
            indexCalls++;
            lastTo = to;
            return response.get();
        }

        @Override
        public List<Candle> fetchDailyCandles(String stockCode, LocalDate from, LocalDate to) {
            throw new UnsupportedOperationException("지수 데이터원은 종목 일봉을 부르지 않는다");
        }

        @Override
        public List<MinuteCandle> fetchTodayMinuteCandles(String stockCode) {
            throw new UnsupportedOperationException("지수 데이터원은 분봉을 부르지 않는다");
        }
    }
}
