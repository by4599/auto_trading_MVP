package com.trading.risk;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface RiskBlockRecordRepository extends JpaRepository<RiskBlockRecord, Long> {

    /** 차단 이력 조회 — 최신이 먼저. Pageable로 상한을 둔다 */
    List<RiskBlockRecord> findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
            LocalDateTime from, Pageable pageable);

    /** 보존 기간 정리용 */
    long countByOccurredAtBefore(LocalDateTime cutoff);

    long deleteByOccurredAtBefore(LocalDateTime cutoff);
}
