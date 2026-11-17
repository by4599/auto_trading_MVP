package com.trading.risk;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ModeTransitionRepository extends JpaRepository<ModeTransition, Long> {

    /** 모드 전환 이력 조회 — 최신이 먼저. Pageable로 상한을 둔다 (화면이 한 번에 다 받지 않게) */
    List<ModeTransition> findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
            LocalDateTime from, Pageable pageable);
}
