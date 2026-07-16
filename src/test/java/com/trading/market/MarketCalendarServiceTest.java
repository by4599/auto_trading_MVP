package com.trading.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarketCalendarService — KRX 거래일 캘린더")
class MarketCalendarServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static MarketCalendarProperties propsWith(List<LocalDate> holidays,
                                                        List<MarketCalendarProperties.EarlyOpenDay> earlyOpenDays) {
        MarketCalendarProperties p = new MarketCalendarProperties();
        p.setHolidays(holidays);
        p.setEarlyOpenDays(earlyOpenDays);
        return p;
    }

    private static MarketCalendarProperties.EarlyOpenDay earlyOpenDay(LocalDate date, LocalTime open, LocalTime close) {
        MarketCalendarProperties.EarlyOpenDay d = new MarketCalendarProperties.EarlyOpenDay();
        d.setDate(date);
        d.setOpenTime(open);
        d.setCloseTime(close);
        return d;
    }

    private static Clock fixedAt(LocalDate date, int hour, int minute) {
        return Clock.fixed(LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(KST).toInstant(), KST);
    }

    // ── 휴장일 판정 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("토요일 → 휴장")
    void saturday_is_holiday() {
        MarketCalendarService sut = new MarketCalendarService(
                propsWith(List.of(), List.of()), fixedAt(LocalDate.of(2026, 7, 18), 10, 0));

        assertThat(sut.isHoliday(LocalDate.of(2026, 7, 18))).isTrue(); // 2026-07-18 토요일
    }

    @Test
    @DisplayName("일요일 → 휴장")
    void sunday_is_holiday() {
        MarketCalendarService sut = new MarketCalendarService(
                propsWith(List.of(), List.of()), fixedAt(LocalDate.of(2026, 7, 19), 10, 0));

        assertThat(sut.isHoliday(LocalDate.of(2026, 7, 19))).isTrue(); // 2026-07-19 일요일
    }

    @Test
    @DisplayName("등록된 휴장일(평일) → 휴장")
    void registered_weekday_holiday() {
        LocalDate christmas = LocalDate.of(2026, 12, 25); // 금요일
        MarketCalendarService sut = new MarketCalendarService(
                propsWith(List.of(christmas), List.of()), fixedAt(christmas, 10, 0));

        assertThat(sut.isHoliday(christmas)).isTrue();
        assertThat(sut.isTradingDay(christmas)).isFalse();
    }

    @Test
    @DisplayName("평범한 평일 → 거래일")
    void regular_weekday_is_trading_day() {
        LocalDate wed = LocalDate.of(2026, 7, 15); // 수요일
        MarketCalendarService sut = new MarketCalendarService(
                propsWith(List.of(), List.of()), fixedAt(wed, 10, 0));

        assertThat(sut.isTradingDay(wed)).isTrue();
        assertThat(sut.isHoliday(wed)).isFalse();
    }

    // ── 개장·마감 시각 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("특례일 미등록 → 기본 09:00~15:30")
    void default_hours_when_no_early_open_day() {
        LocalDate day = LocalDate.of(2026, 7, 15);
        MarketCalendarService sut = new MarketCalendarService(
                propsWith(List.of(), List.of()), fixedAt(day, 10, 0));

        assertThat(sut.openTime(day)).isEqualTo(LocalTime.of(9, 0));
        assertThat(sut.closeTime(day)).isEqualTo(LocalTime.of(15, 30));
    }

    @Test
    @DisplayName("수능일(특례일 등록) → 10:00~16:30")
    void csat_day_overrides_hours() {
        LocalDate csat = LocalDate.of(2026, 11, 19);
        MarketCalendarService sut = new MarketCalendarService(
                propsWith(List.of(), List.of(earlyOpenDay(csat, LocalTime.of(10, 0), LocalTime.of(16, 30)))),
                fixedAt(csat, 10, 0));

        assertThat(sut.openTime(csat)).isEqualTo(LocalTime.of(10, 0));
        assertThat(sut.closeTime(csat)).isEqualTo(LocalTime.of(16, 30));
    }

    // ── 장중 판정 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("평일 장중 시각 → true")
    void during_market_hours_on_weekday() {
        LocalDate wed = LocalDate.of(2026, 7, 15);
        MarketCalendarService sut = new MarketCalendarService(
                propsWith(List.of(), List.of()), fixedAt(wed, 12, 0));

        assertThat(sut.isDuringMarketHoursNow()).isTrue();
    }

    @Test
    @DisplayName("평일 장외 시각(22:00) → false")
    void outside_market_hours_on_weekday() {
        LocalDate wed = LocalDate.of(2026, 7, 15);
        MarketCalendarService sut = new MarketCalendarService(
                propsWith(List.of(), List.of()), fixedAt(wed, 22, 0));

        assertThat(sut.isDuringMarketHoursNow()).isFalse();
    }

    @Test
    @DisplayName("휴장일 낮 시각이어도 장중 아님")
    void holiday_is_never_during_market_hours() {
        LocalDate sat = LocalDate.of(2026, 7, 18);
        MarketCalendarService sut = new MarketCalendarService(
                propsWith(List.of(), List.of()), fixedAt(sat, 12, 0));

        assertThat(sut.isDuringMarketHoursNow()).isFalse();
        assertThat(sut.isHolidayToday()).isTrue();
    }

    @Test
    @DisplayName("수능일 10:30(평시라면 장중, 특례 시각으로도 장중) → true")
    void during_market_hours_on_csat_day() {
        LocalDate csat = LocalDate.of(2026, 11, 19);
        MarketCalendarService sut = new MarketCalendarService(
                propsWith(List.of(), List.of(earlyOpenDay(csat, LocalTime.of(10, 0), LocalTime.of(16, 30)))),
                fixedAt(csat, 9, 30)); // 평시라면 장중이지만 수능일 개장 전(10:00 전)

        assertThat(sut.isDuringMarketHoursNow()).isFalse(); // 아직 개장 전
    }
}
