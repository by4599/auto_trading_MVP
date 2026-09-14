package com.trading.position;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface DailyEquityRepository extends JpaRepository<DailyEquity, LocalDate> {

    /** 전체 이력의 최대 시작 자산 — peakEquity 실측 검증 근거 (기록이 없으면 null) */
    @Query("select max(d.startEquity) from DailyEquity d")
    Double findMaxStartEquity();

    /** 일별 순손익 원장 조회 — 최근 날짜가 먼저 */
    List<DailyEquity> findByTradeDateGreaterThanEqualOrderByTradeDateDesc(LocalDate from);
}
