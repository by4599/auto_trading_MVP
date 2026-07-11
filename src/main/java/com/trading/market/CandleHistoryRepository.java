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
}
