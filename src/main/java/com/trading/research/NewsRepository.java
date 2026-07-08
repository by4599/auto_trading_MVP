package com.trading.research;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface NewsRepository extends JpaRepository<NewsItem, Long> {

    /** 특정 종목 뉴스 최신순 */
    List<NewsItem> findByStockCodeOrderByPublishedAtDesc(String stockCode);

    /** 복수 종목 최신 뉴스 (관심 종목 전체 피드) */
    List<NewsItem> findTop50ByStockCodeInOrderByPublishedAtDesc(List<String> stockCodes);

    /** 추천 집계용 — 복수 종목의 최근 N일 뉴스 (RecommendationService) */
    List<NewsItem> findByStockCodeInAndPublishedAtAfterOrderByPublishedAtDesc(
            List<String> stockCodes, LocalDateTime after);

    /** 중복 URL 체크 */
    boolean existsByUrl(String url);

    /** 30일 초과 뉴스 정리 */
    @Transactional
    @Modifying
    @Query("DELETE FROM NewsItem n WHERE n.fetchedAt < :before")
    int deleteOlderThan(LocalDateTime before);
}
