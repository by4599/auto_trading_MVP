package com.trading.position;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TradeResultRepository extends JpaRepository<TradeResult, Long> {

    /** 실적 대시보드: 기간 조회 (버킷 집계는 서비스에서) */
    List<TradeResult> findByTradeDateBetweenOrderBySoldAtAsc(LocalDate from, LocalDate to);

    /** 당일 실현손익 (/api/pnl/daily) */
    List<TradeResult> findByTradeDate(LocalDate tradeDate);

    /** 백필 멱등성: 기존 BACKFILL 전체 삭제 후 재생성 */
    void deleteBySource(TradeResult.Source source);

    /** 백필 중복 방지: 가장 이른 LIVE 기록 이후 구간은 백필하지 않는다 */
    Optional<TradeResult> findFirstBySourceOrderBySoldAtAsc(TradeResult.Source source);
}
