package com.trading.market;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

/**
 * KRX 거래일 캘린더 (OPERATIONS §5.1) — 휴장일 판정과 당일 개장·마감 시각을
 * 제공한다. 데이터는 market-calendar.yml(MarketCalendarProperties)에서 온다.
 *
 * 백테스트 등 캘린더 파일이 로드되지 않는 프로파일에서도 안전하게 동작한다
 * (holidays/earlyOpenDays가 비어 있으면 토·일만 휴장으로 판정).
 */
@Component
public class MarketCalendarService {

    private static final Logger log = LoggerFactory.getLogger(MarketCalendarService.class);

    private static final LocalTime DEFAULT_OPEN  = LocalTime.of(9, 0);
    private static final LocalTime DEFAULT_CLOSE = LocalTime.of(15, 30);
    private static final int MIN_EXPECTED_HOLIDAYS = 5;

    private final MarketCalendarProperties props;
    private final Clock clock;

    public MarketCalendarService(MarketCalendarProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    void warnIfIncomplete() {
        if (props.getHolidays().size() < MIN_EXPECTED_HOLIDAYS) {
            log.warn("[MarketCalendar] 거래일 캘린더 데이터 불완전 (휴장일 {}건) — "
                    + "설날/추석 등 실제 KRX 공지로 market-calendar.yml을 보완할 것",
                    props.getHolidays().size());
        }
    }

    public boolean isHoliday(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return true;
        return props.getHolidays().contains(date);
    }

    public boolean isTradingDay(LocalDate date) {
        return !isHoliday(date);
    }

    public LocalTime openTime(LocalDate date) {
        return earlyOpenDay(date).map(MarketCalendarProperties.EarlyOpenDay::getOpenTime).orElse(DEFAULT_OPEN);
    }

    public LocalTime closeTime(LocalDate date) {
        return earlyOpenDay(date).map(MarketCalendarProperties.EarlyOpenDay::getCloseTime).orElse(DEFAULT_CLOSE);
    }

    public boolean isDuringMarketHours(LocalDateTime dateTime) {
        LocalDate date = dateTime.toLocalDate();
        if (isHoliday(date)) return false;
        LocalTime t = dateTime.toLocalTime();
        return !t.isBefore(openTime(date)) && !t.isAfter(closeTime(date));
    }

    // ── Clock 기반 편의 메서드 ───────────────────────────────────────────────

    public boolean isHolidayToday() {
        return isHoliday(LocalDate.now(clock));
    }

    public boolean isDuringMarketHoursNow() {
        return isDuringMarketHours(LocalDateTime.now(clock));
    }

    private Optional<MarketCalendarProperties.EarlyOpenDay> earlyOpenDay(LocalDate date) {
        return props.getEarlyOpenDays().stream()
                .filter(d -> date.equals(d.getDate()))
                .findFirst();
    }
}
