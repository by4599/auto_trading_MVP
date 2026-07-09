package com.trading.research;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisclosureRepository extends JpaRepository<DisclosureItem, Long> {

    boolean existsByReceiptNo(String receiptNo);

    List<DisclosureItem> findTop50ByOrderByDisclosedAtDescIdDesc();
}
