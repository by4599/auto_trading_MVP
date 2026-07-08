package com.trading.research;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 뉴스 감성 기반 투자 항목 추천 (Phase 3 사전 단계).
 *
 * 관심 종목(watchlist)의 최근 뉴스를 종목별로 집계해 순위를 매긴다:
 *   점수 = Σ (POSITIVE +1 / NEGATIVE -1), 24시간 이내 뉴스는 ×2 가중.
 *   판정 = 점수 >= +2 매수 후보(BUY_CANDIDATE) / <= -2 주의(CAUTION) / 그 외 관망(NEUTRAL).
 *
 * ⚠ 추천 표시 전용 — 자동 매매 파이프라인과 연결하지 않는다 (뉴스→매매 연동은 Phase 3,
 * 이벤트 유형별 백테스트 검증 후에만 신호로 승격한다. INVESTMENT-METHODOLOGY §5 참고).
 * 키워드 감성분류 정확도는 70-80% 수준 — 최종 판단은 사람이 한다.
 */
@Service
public class RecommendationService {

    private static final int RECENT_HOURS_DOUBLE_WEIGHT = 24;
    private static final int SCORE_BUY_THRESHOLD = 2;
    private static final int SCORE_CAUTION_THRESHOLD = -2;
    private static final int MAX_HEADLINES = 3;

    private final WatchlistRepository watchlistRepository;
    private final NewsRepository newsRepository;

    public RecommendationService(WatchlistRepository watchlistRepository,
                                 NewsRepository newsRepository) {
        this.watchlistRepository = watchlistRepository;
        this.newsRepository = newsRepository;
    }

    /** @param days 집계 창 (최근 N일 뉴스) */
    public List<Recommendation> recommend(int days) {
        List<WatchlistItem> watchlist = watchlistRepository.findAll();
        if (watchlist.isEmpty()) return List.of();

        List<String> codes = watchlist.stream().map(WatchlistItem::getStockCode).toList();
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        LocalDateTime recentCutoff = LocalDateTime.now().minusHours(RECENT_HOURS_DOUBLE_WEIGHT);

        Map<String, List<NewsItem>> newsByStock = newsRepository
                .findByStockCodeInAndPublishedAtAfterOrderByPublishedAtDesc(codes, since)
                .stream()
                .collect(Collectors.groupingBy(NewsItem::getStockCode));

        return watchlist.stream()
                .map(w -> toRecommendation(w, newsByStock.getOrDefault(w.getStockCode(), List.of()), recentCutoff))
                .sorted(Comparator.comparingInt(Recommendation::score).reversed()
                        .thenComparing(Comparator.comparingInt(Recommendation::newsCount).reversed())
                        .thenComparing(Recommendation::stockCode))
                .toList();
    }

    private Recommendation toRecommendation(WatchlistItem item, List<NewsItem> news,
                                            LocalDateTime recentCutoff) {
        int score = 0, positive = 0, negative = 0;
        for (NewsItem n : news) {
            int direction = switch (n.getSentiment() == null ? "NEUTRAL" : n.getSentiment()) {
                case "POSITIVE" -> { positive++; yield 1; }
                case "NEGATIVE" -> { negative++; yield -1; }
                default -> 0;
            };
            boolean recent = n.getPublishedAt() != null && n.getPublishedAt().isAfter(recentCutoff);
            score += direction * (recent ? 2 : 1);
        }

        List<Headline> headlines = news.stream()
                .limit(MAX_HEADLINES)
                .map(n -> new Headline(n.getTitle(), n.getUrl(), n.getSentiment()))
                .toList();

        return new Recommendation(item.getStockCode(), item.getStockName(),
                score, positive, negative, news.size(), gradeOf(score), headlines);
    }

    private static String gradeOf(int score) {
        if (score >= SCORE_BUY_THRESHOLD)     return "BUY_CANDIDATE";
        if (score <= SCORE_CAUTION_THRESHOLD) return "CAUTION";
        return "NEUTRAL";
    }

    // ── 응답 DTO ─────────────────────────────────────────────────────────────

    public record Recommendation(
            String stockCode,
            String stockName,
            int score,
            int positiveCount,
            int negativeCount,
            int newsCount,
            String grade,
            List<Headline> headlines
    ) {}

    public record Headline(String title, String url, String sentiment) {}
}
