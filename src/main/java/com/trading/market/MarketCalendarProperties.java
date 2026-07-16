package com.trading.market;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * market-calendar.yml(OPERATIONS §5.1)의 KRX 휴장일·개장시간 특례일 바인딩.
 * 파일이 없거나 항목이 비어 있어도 빈 리스트로 안전 폴백된다.
 */
@ConfigurationProperties(prefix = "market-calendar")
public class MarketCalendarProperties {

    private List<LocalDate> holidays = new ArrayList<>();
    private List<EarlyOpenDay> earlyOpenDays = new ArrayList<>();

    public List<LocalDate> getHolidays() { return holidays; }
    public void setHolidays(List<LocalDate> holidays) { this.holidays = holidays; }

    public List<EarlyOpenDay> getEarlyOpenDays() { return earlyOpenDays; }
    public void setEarlyOpenDays(List<EarlyOpenDay> earlyOpenDays) { this.earlyOpenDays = earlyOpenDays; }

    /** 개장·마감 시각이 평시(09:00~15:30)와 다른 날 — 예: 수능일 10:00~16:30 */
    public static class EarlyOpenDay {
        private LocalDate date;
        private LocalTime openTime;
        private LocalTime closeTime;

        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }

        public LocalTime getOpenTime() { return openTime; }
        public void setOpenTime(LocalTime openTime) { this.openTime = openTime; }

        public LocalTime getCloseTime() { return closeTime; }
        public void setCloseTime(LocalTime closeTime) { this.closeTime = closeTime; }
    }
}
