package com.trading.position;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PositionRepository extends JpaRepository<Position, Long> {

    Optional<Position> findByStockCode(String stockCode);
}
