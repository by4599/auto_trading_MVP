package com.trading.strategy;

import com.trading.market.Candle;

/**
 * 변동성 돌파 목표가 공식의 단일 출처.
 *
 * 전략(VolatilityBreakoutStrategy)과 검토종목 대시보드(ReviewService)가
 * 같은 계산을 공유한다 — 화면에 보이는 목표가와 실제 매매 판정이 어긋나면 안 된다.
 */
public final class BreakoutCalculator {

    private BreakoutCalculator() {}

    /** 목표가 = 당일 시가 + (전일 고가 − 전일 저가) × K */
    public static double targetPrice(Candle yesterday, Candle today, double k) {
        return targetPrice(yesterday.getHigh(), yesterday.getLow(), today.getOpen(), k);
    }

    public static double targetPrice(double yesterdayHigh, double yesterdayLow,
                                     double todayOpen, double k) {
        return todayOpen + (yesterdayHigh - yesterdayLow) * k;
    }
}
