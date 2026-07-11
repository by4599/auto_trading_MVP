package com.trading.research;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisclosureRepository extends JpaRepository<DisclosureItem, Long> {

    boolean existsByReceiptNo(String receiptNo);

    List<DisclosureItem> findTop50ByOrderByDisclosedAtDescIdDesc();

    /** event_type 컬럼 추가 이전 수집분 — 기동 시 재분류 대상 */
    List<DisclosureItem> findByEventTypeIsNull();
}
