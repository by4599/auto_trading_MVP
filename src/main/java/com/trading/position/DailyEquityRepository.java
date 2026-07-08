package com.trading.position;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface DailyEquityRepository extends JpaRepository<DailyEquity, LocalDate> {
}
