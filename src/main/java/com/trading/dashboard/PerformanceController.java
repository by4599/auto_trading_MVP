package com.trading.dashboard;

import com.trading.position.PerformanceBackfillService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 실적 대시보드 API.
 *
 * GET  /api/performance?period=daily|weekly|monthly&days=90 — 기간별 누적 성과
 * POST /api/performance/backfill — 과거 체결(order_history)에서 실현손익 소급 생성 (멱등)
 */
@RestController
@RequestMapping("/api/performance")
public class PerformanceController {

    private final PerformanceService performanceService;
    private final PerformanceBackfillService backfillService;

    public PerformanceController(PerformanceService performanceService,
                                 PerformanceBackfillService backfillService) {
        this.performanceService = performanceService;
        this.backfillService = backfillService;
    }

    @GetMapping
    public Map<String, Object> getPerformance(
            @RequestParam(defaultValue = "daily") String period,
            @RequestParam(defaultValue = "90") int days) {
        String normalized = switch (period) {
            case "weekly", "monthly" -> period;
            default -> "daily";
        };
        int boundedDays = Math.max(1, Math.min(days, 1095)); // 최대 3년
        return performanceService.performance(normalized, boundedDays);
    }

    @PostMapping("/backfill")
    public Map<String, Object> backfill() {
        PerformanceBackfillService.BackfillResult r = backfillService.backfill();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created",  r.created());
        result.put("warnings", r.warnings());
        return result;
    }
}
