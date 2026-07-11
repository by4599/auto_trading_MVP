package com.trading.market;

import java.time.LocalDate;
import java.util.List;

/**
 * 과거 캔들 수취 HTTP 경계 (B-1).
 * DartApiClient와 같은 이유로 인터페이스 뒤에 숨긴다 — 서비스 테스트에서 Mockito 대체.
 */
public interface CandleHistoryClient {

    /** 기간 일봉 (수정주가, 과거→최신). KIS 응답 상한(~100행)은 구현체가 페이지네이션으로 흡수한다. */
    List<Candle> fetchDailyCandles(String stockCode, LocalDate from, LocalDate to);

    /** 지수 기간 일봉 (예: KOSPI = "0001", 과거→최신) */
    List<Candle> fetchIndexDailyCandles(String indexCode, LocalDate from, LocalDate to);

    /** 당일 분봉 전체 (장 시작→현재, 과거→최신) */
    List<MinuteCandle> fetchTodayMinuteCandles(String stockCode);
}
