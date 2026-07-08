package com.trading.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * 국내 금융 뉴스 RSS 피드를 30분마다 수집해 DB에 저장한다.
 *
 * 뉴스-종목 매핑 방법:
 *   관심 종목명(stockName)이 뉴스 제목에 포함되면 해당 종목 뉴스로 분류.
 *   미분류 뉴스는 stock_code = null로 저장 (시장 전체 뉴스).
 *
 * 30일 초과 뉴스는 매일 새벽 2시 자동 삭제.
 */
@Service
public class NewsAggregatorService {

    private static final Logger log = LoggerFactory.getLogger(NewsAggregatorService.class);

    /** 국내 금융 뉴스 RSS 목록 (공개 피드, 인증 불필요) */
    private static final List<RssFeed> RSS_FEEDS = List.of(
            new RssFeed("edaily",   "https://rss.edaily.co.kr/edaily_stock.xml"),
            new RssFeed("newspim",  "https://www.newspim.com/rss/stock"),
            new RssFeed("heraldkorea", "https://biz.heraldkorea.com/rss"),
            new RssFeed("inews24",  "https://www.inews24.com/rss/feed/newsstock")
    );

    private static final DateTimeFormatter RFC822 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final WatchlistRepository    watchlistRepository;
    private final NewsRepository         newsRepository;
    private final NewsSentimentAnalyzer  sentimentAnalyzer;
    private final RestClient             httpClient;

    public NewsAggregatorService(WatchlistRepository   watchlistRepository,
                                  NewsRepository        newsRepository,
                                  NewsSentimentAnalyzer sentimentAnalyzer) {
        this.watchlistRepository = watchlistRepository;
        this.newsRepository      = newsRepository;
        this.sentimentAnalyzer   = sentimentAnalyzer;
        this.httpClient          = RestClient.builder()
                .defaultHeader("User-Agent", "AutoTrading-NewsBot/1.0")
                .build();
    }

    @Scheduled(fixedRate = 1_800_000) // 30분
    public void aggregate() {
        List<WatchlistItem> watchlist = watchlistRepository.findAll();
        int total = 0;
        for (RssFeed feed : RSS_FEEDS) {
            try {
                total += fetchAndSave(feed, watchlist);
            } catch (Exception e) {
                log.warn("[NewsAggregator] {} 피드 실패 — {}", feed.source(), e.getMessage());
            }
        }
        if (total > 0) log.info("[NewsAggregator] 뉴스 {}건 저장 완료", total);
    }

    @Scheduled(cron = "0 0 2 * * *") // 매일 새벽 2시
    public void cleanOld() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);
        int deleted = newsRepository.deleteOlderThan(threshold);
        if (deleted > 0) log.info("[NewsAggregator] 30일 초과 뉴스 {}건 삭제", deleted);
    }

    // ── 구현 ─────────────────────────────────────────────────────────────────

    private int fetchAndSave(RssFeed feed, List<WatchlistItem> watchlist) throws Exception {
        String xml = httpClient.get()
                .uri(feed.url())
                .retrieve()
                .body(String.class);

        if (xml == null || xml.isBlank()) return 0;

        Document doc = parseXml(xml);
        NodeList items = doc.getElementsByTagName("item");
        int saved = 0;

        for (int i = 0; i < items.getLength(); i++) {
            var item = items.item(i);
            String title   = textOf(item, "title");
            String link    = textOf(item, "link");
            String pubDate = textOf(item, "pubDate");

            if (title == null || link == null) continue;
            if (newsRepository.existsByUrl(link))  continue; // 중복 skip

            LocalDateTime publishedAt = parseDate(pubDate);
            String stockCode          = matchStock(title, watchlist);
            String sentiment          = sentimentAnalyzer.analyze(title);

            newsRepository.save(
                    NewsItem.of(stockCode, title, link, feed.source(), publishedAt, sentiment));
            saved++;
        }
        return saved;
    }

    private String matchStock(String title, List<WatchlistItem> watchlist) {
        for (WatchlistItem w : watchlist) {
            if (w.getStockName() != null && title.contains(w.getStockName())) {
                return w.getStockCode();
            }
            if (title.contains(w.getStockCode())) {
                return w.getStockCode();
            }
        }
        return null; // 미분류
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // XXE 방어
        DocumentBuilder builder = factory.newDocumentBuilder();
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        return builder.parse(new ByteArrayInputStream(bytes));
    }

    private static String textOf(org.w3c.dom.Node parent, String tagName) {
        if (!(parent instanceof org.w3c.dom.Element el)) return null;
        var nodes = el.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        return nodes.item(0).getTextContent().strip();
    }

    private static LocalDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) return LocalDateTime.now();
        try {
            return ZonedDateTime.parse(raw, RFC822).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return LocalDateTime.now();
        }
    }

    private record RssFeed(String source, String url) {}
}
