package com.trading.control;

import com.trading.risk.ModeTransition;
import com.trading.risk.ModeTransitionRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 운전 모드 전환 이력 조회 (읽기 전용).
 *
 * <pre>
 * GET /api/trading/mode-history?days=30
 * </pre>
 *
 * <p>2026-09-10부터 7거래일간 매매가 0건이었는데 "언제 왜 멈췄나"가 화면에 없어 아무도 몰랐다.
 * 그 구멍을 메우는 조회다 — 아무 판정에도 쓰이지 않는다.
 *
 * <p>{@code TradingController}(222줄)에 얹지 않고 따로 둔 이유는 그 파일이 300줄 상한에
 * 가깝기 때문이다. URL 접두사는 같으므로 화면에서는 차이가 없다.
 */
@RestController
@RequestMapping("/api/trading")
@Profile("!backtest")
public class ModeHistoryController {

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 365;

    /** 한 번에 돌려주는 최대 건수 — 화면이 통째로 받아 느려지지 않게 상한을 둔다 */
    private static final int MAX_ROWS = 500;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss");

    private final ModeTransitionRepository repository;
    private final Clock clock;

    public ModeHistoryController(ModeTransitionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @GetMapping("/mode-history")
    public Map<String, Object> getModeHistory(
            @RequestParam(defaultValue = "" + DEFAULT_DAYS) int days) {

        int boundedDays = Math.max(1, Math.min(days, MAX_DAYS));
        LocalDate fromDate = LocalDate.now(clock).minusDays(boundedDays - 1L);
        LocalDateTime from = fromDate.atStartOfDay();

        List<ModeTransition> rows = repository
                .findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        from, PageRequest.of(0, MAX_ROWS));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days",        boundedDays);
        result.put("from",        fromDate.toString());
        result.put("count",       rows.size());
        result.put("limit",       MAX_ROWS);
        result.put("truncated",   rows.size() >= MAX_ROWS);
        result.put("transitions", rows.stream().map(ModeHistoryController::row).toList());
        return result;
    }

    private static Map<String, Object> row(ModeTransition t) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",           t.getId());
        item.put("at",           t.getOccurredAt().format(FMT));
        item.put("occurredAt",   t.getOccurredAt().toString());
        item.put("previousMode", t.getPreviousMode().name());
        item.put("newMode",      t.getNewMode().name());
        item.put("reason",       t.getReason());   // 지금은 거의 항상 null (ModeTransition 주석)
        item.put("source",       t.getSource());
        return item;
    }
}
