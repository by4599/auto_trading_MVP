package com.trading.backtest;

import com.trading.market.MinuteCandle;

import java.util.List;

/**
 * 분봉 원천 — 운영(paper) DB에 쌓인 당일 분봉을 백테스트 DB로 옮기기 위한 읽기 경계.
 *
 * 분봉은 {@code MinuteCandleCollector}(@Profile("paper"))가 <b>운영 DB(trading-db)</b>에 저장하는데
 * 백테스트 엔진은 <b>backtest-db</b>를 읽는다 — 두 DB 사이에 다리가 없어 축적된 분봉이
 * 검증에 전혀 닿지 않았다(2026-08-04 발견). 이 인터페이스가 그 다리의 읽기 쪽이며,
 * 인터페이스로 분리해 둔 덕에 임포터를 실제 DB 없이 테스트할 수 있다.
 */
public interface MinuteCandleSource {

    /** 원천에 있는 분봉 전량 (종목코드 + 봉) */
    List<Row> fetchAll();

    record Row(String stockCode, MinuteCandle candle) {}
}
