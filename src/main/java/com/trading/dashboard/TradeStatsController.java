package com.trading.dashboard;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 거래 기준 성적 조회 API (읽기 전용, 모의투자 전용).
 *
 * <pre>
 * GET /api/performance/trades?days=30      전체 요약 (승률·손익비·평균이익/손실)
 * GET /api/performance/by-stock?days=30    종목별 성적
 * GET /api/performance/by-bucket?days=30   지갑칸별 성적
 * </pre>
 *
 * <p><b>모든 응답에 {@code estimated=true}와 경고 문구, 그리고 집계에서 빠진 건수
 * ({@code unmeasurable})가 들어 있다</b> — 매도 체결가가 추정이기 때문이다
 * ({@link TradeStatsService}).
 *
 * <p>{@code @Profile("paper")}인 이유는 {@link AccountPerformanceController}와 같다:
 * 프로필 없는 {@code PerformanceController}에 paper 전용 빈을 얹으면 backtest 기동이
 * {@code NoSuchBeanDefinitionException}으로 깨진다.
 */
@RestController
@RequestMapping("/api/performance")
@Profile("paper")
public class TradeStatsController {

    private static final String DEFAULT_DAYS = "30";

    private final TradeStatsService tradeStatsService;

    public TradeStatsController(TradeStatsService tradeStatsService) {
        this.tradeStatsService = tradeStatsService;
    }

    @GetMapping("/trades")
    public Map<String, Object> getTradeSummary(@RequestParam(defaultValue = DEFAULT_DAYS) int days) {
        return tradeStatsService.summary(days);
    }

    @GetMapping("/by-stock")
    public Map<String, Object> getByStock(@RequestParam(defaultValue = DEFAULT_DAYS) int days) {
        return tradeStatsService.byStock(days);
    }

    @GetMapping("/by-bucket")
    public Map<String, Object> getByBucket(@RequestParam(defaultValue = DEFAULT_DAYS) int days) {
        return tradeStatsService.byBucket(days);
    }
}
