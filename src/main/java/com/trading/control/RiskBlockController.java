package com.trading.control;

import com.trading.risk.RiskBlockRecord;
import com.trading.risk.RiskBlockRecordRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 매수 차단 이력 조회 (읽기 전용).
 *
 * <pre>
 * GET /api/risk/blocks?days=7   최근 차단 내역 + 룰별 집계
 * </pre>
 *
 * <p>"왜 안 샀나"를 화면에서 보기 위한 것이다 — 아무 판정에도 쓰이지 않는다.
 *
 * <p>{@code count}(행 수)와 {@code blockedCount}(실제 차단 횟수)는 다르다.
 * 1초 루프에서 같은 종목·같은 룰이 반복 거부되므로 기록기가 창 단위로 합치고,
 * 합쳐진 횟수를 {@code blockedCount}에 담는다. <b>룰별 집계는 합쳐진 횟수 기준</b>이다.
 */
@RestController
@RequestMapping("/api/risk")
@Profile("!backtest")
public class RiskBlockController {

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 365;

    /** 한 번에 돌려주는 최대 행 수 */
    private static final int MAX_ROWS = 1000;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final RiskBlockRecordRepository repository;
    private final Clock clock;

    public RiskBlockController(RiskBlockRecordRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @GetMapping("/blocks")
    public Map<String, Object> getBlocks(@RequestParam(defaultValue = "" + DEFAULT_DAYS) int days) {
        int boundedDays = Math.max(1, Math.min(days, MAX_DAYS));
        LocalDate fromDate = LocalDate.now(clock).minusDays(boundedDays - 1L);
        LocalDateTime from = fromDate.atStartOfDay();

        List<RiskBlockRecord> rows = repository
                .findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        from, PageRequest.of(0, MAX_ROWS));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days",         boundedDays);
        result.put("from",         fromDate.toString());
        result.put("count",        rows.size());
        result.put("limit",        MAX_ROWS);
        result.put("truncated",    rows.size() >= MAX_ROWS);
        result.put("blockedTotal", rows.stream().mapToLong(RiskBlockRecord::getBlockedCount).sum());
        result.put("byRule",       aggregateByRule(rows));
        result.put("blocks",       rows.stream().map(RiskBlockController::row).toList());
        return result;
    }

    /** 룰별 집계 — 막은 횟수가 많은 순. 종목 수도 함께 준다(한 종목만 막힌 건지 전체인지 구분) */
    private static List<Map<String, Object>> aggregateByRule(List<RiskBlockRecord> rows) {
        Map<String, long[]> totals = new TreeMap<>();               // [차단횟수, 행수]
        Map<String, Set<String>> stocks = new TreeMap<>();
        for (RiskBlockRecord r : rows) {
            totals.computeIfAbsent(r.getRuleName(), k -> new long[2]);
            totals.get(r.getRuleName())[0] += r.getBlockedCount();
            totals.get(r.getRuleName())[1] += 1;
            stocks.computeIfAbsent(r.getRuleName(), k -> new LinkedHashSet<>())
                  .add(r.getStockCode());
        }
        return totals.entrySet().stream()
                .map(e -> ruleRow(e.getKey(), e.getValue(), stocks.get(e.getKey()).size()))
                .sorted(Comparator.comparingLong(m -> -(long) m.get("blockedCount")))
                .toList();
    }

    private static Map<String, Object> ruleRow(String ruleName, long[] totals, int stockCount) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ruleName",     ruleName);
        item.put("blockedCount", totals[0]);
        item.put("recordCount",  totals[1]);
        item.put("stockCount",   stockCount);
        return item;
    }

    private static Map<String, Object> row(RiskBlockRecord r) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",           r.getId());
        item.put("at",           r.getOccurredAt().format(FMT));
        item.put("occurredAt",   r.getOccurredAt().toString());
        item.put("stockCode",    r.getStockCode());
        item.put("ruleName",     r.getRuleName());
        item.put("reason",       r.getReason());
        item.put("strategyName", r.getStrategyName());
        item.put("blockedCount", r.getBlockedCount());
        return item;
    }
}
