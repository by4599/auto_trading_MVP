package com.trading.market;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface CandleHistoryRepository extends JpaRepository<CandleHistory, Long> {

    List<CandleHistory> findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
            String stockCode, Timeframe timeframe, LocalDate from, LocalDate to);

    long countByStockCodeAndTimeframeAndCandleDateBetween(
            String stockCode, Timeframe timeframe, LocalDate from, LocalDate to);

    Optional<CandleHistory> findFirstByStockCodeAndTimeframeOrderByCandleDateAsc(
            String stockCode, Timeframe timeframe);

    Optional<CandleHistory> findFirstByStockCodeAndTimeframeOrderByCandleDateDesc(
            String stockCode, Timeframe timeframe);

    boolean existsByStockCodeAndTimeframeAndCandleDateAndCandleTime(
            String stockCode, Timeframe timeframe, LocalDate date, LocalTime time);

    long countByStockCodeAndTimeframeAndCandleDate(
            String stockCode, Timeframe timeframe, LocalDate date);

    long countByTimeframeAndCandleDateBefore(Timeframe timeframe, LocalDate date);

    /** 운영 DB 보존 기간 정리용 — 일봉은 건드리지 않도록 timeframe을 반드시 지정한다 */
    long deleteByTimeframeAndCandleDateBefore(Timeframe timeframe, LocalDate date);
}
