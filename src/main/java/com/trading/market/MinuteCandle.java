package com.trading.market;

import java.time.LocalDate;
import java.time.LocalTime;

/** 분봉 — Candle(일 단위)과 달리 봉 시각을 가진다 (B-1 전방 축적용). */
public record MinuteCandle(
        LocalDate date,
        LocalTime time,
        double open,
        double high,
        double low,
        double close,
        long volume
) {}
