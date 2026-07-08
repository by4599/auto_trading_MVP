package com.trading.research;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 뉴스 기반 투자 항목 추천 (Phase 3 사전 단계 — 추천 표시 전용, 매매 미연동).
 */
@DisplayName("RecommendationService — 뉴스 감성 기반 종목 추천")
class RecommendationServiceTest {

    private WatchlistRepository watchlistRepository;
    private NewsRepository newsRepository;
    private RecommendationService sut;

    @BeforeEach
    void setUp() {
        watchlistRepository = mock(WatchlistRepository.class);
        newsRepository = mock(NewsRepository.class);
        sut = new RecommendationService(watchlistRepository, newsRepository);
    }

    private static NewsItem news(String stockCode, String title, String sentiment, LocalDateTime publishedAt) {
        return NewsItem.of(stockCode, title, "https://news/" + title.hashCode(), "test", publishedAt, sentiment);
    }

    private void givenWatchlist(WatchlistItem... items) {
        when(watchlistRepository.findAll()).thenReturn(List.of(items));
    }

    private void givenNews(NewsItem... items) {
        when(newsRepository.findByStockCodeInAndPublishedAtAfterOrderByPublishedAtDesc(
                anyList(), any(LocalDateTime.class))).thenReturn(List.of(items));
    }

    // ── 기본 집계 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("관심 종목 없음 → 빈 목록 (뉴스 조회 안 함)")
    void empty_watchlist_returns_empty() {
        givenWatchlist();

        assertThat(sut.recommend(3)).isEmpty();
        verify(newsRepository, never())
                .findByStockCodeInAndPublishedAtAfterOrderByPublishedAtDesc(anyList(), any());
    }

    @Test
    @DisplayName("호재 2건(24h 밖) → 점수 +2, 매수 후보")
    void positive_news_scores_buy_candidate() {
        givenWatchlist(WatchlistItem.of("005930", "삼성전자", null));
        LocalDateTime old = LocalDateTime.now().minusDays(2);
        givenNews(
                news("005930", "삼성전자 수주 확대", "POSITIVE", old),
                news("005930", "삼성전자 호실적", "POSITIVE", old));

        List<RecommendationService.Recommendation> result = sut.recommend(3);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).score()).isEqualTo(2);
        assertThat(result.get(0).grade()).isEqualTo("BUY_CANDIDATE");
        assertThat(result.get(0).positiveCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("악재 2건 → 점수 -2, 주의")
    void negative_news_scores_caution() {
        givenWatchlist(WatchlistItem.of("005930", "삼성전자", null));
        LocalDateTime old = LocalDateTime.now().minusDays(2);
        givenNews(
                news("005930", "삼성전자 리콜 우려", "NEGATIVE", old),
                news("005930", "삼성전자 실적 부진", "NEGATIVE", old));

        List<RecommendationService.Recommendation> result = sut.recommend(3);

        assertThat(result.get(0).score()).isEqualTo(-2);
        assertThat(result.get(0).grade()).isEqualTo("CAUTION");
    }

    @Test
    @DisplayName("호재 1건 + 악재 1건 → 점수 0, 관망")
    void mixed_news_is_neutral() {
        givenWatchlist(WatchlistItem.of("005930", "삼성전자", null));
        LocalDateTime old = LocalDateTime.now().minusDays(2);
        givenNews(
                news("005930", "삼성전자 수주", "POSITIVE", old),
                news("005930", "삼성전자 소송", "NEGATIVE", old));

        List<RecommendationService.Recommendation> result = sut.recommend(3);

        assertThat(result.get(0).score()).isEqualTo(0);
        assertThat(result.get(0).grade()).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("NEUTRAL 뉴스는 점수 0이지만 뉴스 수에는 포함")
    void neutral_news_counts_but_no_score() {
        givenWatchlist(WatchlistItem.of("005930", "삼성전자", null));
        givenNews(news("005930", "삼성전자 신제품 발표", "NEUTRAL", LocalDateTime.now().minusDays(2)));

        List<RecommendationService.Recommendation> result = sut.recommend(3);

        assertThat(result.get(0).score()).isEqualTo(0);
        assertThat(result.get(0).newsCount()).isEqualTo(1);
    }

    // ── 최근성 가중 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("24시간 이내 호재는 ×2 가중 → 1건으로 매수 후보 도달")
    void recent_news_weighted_double() {
        givenWatchlist(WatchlistItem.of("005930", "삼성전자", null));
        givenNews(news("005930", "삼성전자 대규모 수주", "POSITIVE", LocalDateTime.now().minusHours(2)));

        List<RecommendationService.Recommendation> result = sut.recommend(3);

        assertThat(result.get(0).score()).isEqualTo(2);
        assertThat(result.get(0).grade()).isEqualTo("BUY_CANDIDATE");
    }

    // ── 정렬 / 헤드라인 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("점수 내림차순 정렬 — 호재 많은 종목이 먼저")
    void sorted_by_score_descending() {
        givenWatchlist(
                WatchlistItem.of("005930", "삼성전자", null),
                WatchlistItem.of("000660", "SK하이닉스", null));
        LocalDateTime old = LocalDateTime.now().minusDays(2);
        givenNews(
                news("005930", "삼성전자 소폭 상승", "POSITIVE", old),
                news("000660", "SK하이닉스 수주 1", "POSITIVE", old),
                news("000660", "SK하이닉스 수주 2", "POSITIVE", old),
                news("000660", "SK하이닉스 수주 3", "POSITIVE", old));

        List<RecommendationService.Recommendation> result = sut.recommend(3);

        assertThat(result).extracting(RecommendationService.Recommendation::stockCode)
                .containsExactly("000660", "005930");
    }

    @Test
    @DisplayName("헤드라인은 최신순 최대 3건")
    void headlines_limited_to_three() {
        givenWatchlist(WatchlistItem.of("005930", "삼성전자", null));
        LocalDateTime base = LocalDateTime.now().minusDays(2);
        givenNews(
                news("005930", "뉴스1", "NEUTRAL", base.plusHours(4)),
                news("005930", "뉴스2", "NEUTRAL", base.plusHours(3)),
                news("005930", "뉴스3", "NEUTRAL", base.plusHours(2)),
                news("005930", "뉴스4", "NEUTRAL", base.plusHours(1)));

        List<RecommendationService.Recommendation> result = sut.recommend(3);

        assertThat(result.get(0).headlines()).hasSize(3);
        assertThat(result.get(0).headlines().get(0).title()).isEqualTo("뉴스1");
    }

    @Test
    @DisplayName("뉴스 없는 관심 종목도 목록에 포함 (점수 0, 뉴스 0건)")
    void watchlist_without_news_still_listed() {
        givenWatchlist(WatchlistItem.of("005930", "삼성전자", null));
        givenNews();

        List<RecommendationService.Recommendation> result = sut.recommend(3);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).newsCount()).isEqualTo(0);
        assertThat(result.get(0).grade()).isEqualTo("NEUTRAL");
    }
}
