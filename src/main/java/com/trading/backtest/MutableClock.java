package com.trading.backtest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 러너가 전진시키는 가상 시계 (B-2, 설계 문서 §2.1).
 *
 * ClockConfig의 시스템 KST 시계를 backtest 프로파일에서 대체한다.
 * MarketCloseRule(15:20 컷)·ConsecutiveLossRule(1시간 차단)이 시뮬 시간 기준으로 동작한다.
 */
public class MutableClock extends Clock {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private volatile Instant instant;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    public void setTo(LocalDate date, LocalTime time) {
        this.instant = ZonedDateTime.of(date, time, KST).toInstant();
    }

    @Override
    public ZoneId getZone() {
        return KST;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("백테스트 시계는 KST 고정");
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
