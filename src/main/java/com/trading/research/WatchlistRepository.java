package com.trading.research;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WatchlistRepository extends JpaRepository<WatchlistItem, Long> {
    boolean existsByStockCode(String stockCode);
    Optional<WatchlistItem> findByStockCode(String stockCode);
    void deleteByStockCode(String stockCode);
}
