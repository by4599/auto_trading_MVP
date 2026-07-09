package com.trading.universe;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface TradingUniverseRepository extends JpaRepository<TradingUniverseItem, Long> {

    boolean existsByStockCode(String stockCode);

    @Transactional
    void deleteByStockCode(String stockCode);
}
