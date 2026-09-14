package com.trading.dashboard;

import com.trading.position.DailyEquity;
import com.trading.position.DailyEquityRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 일별 순손익 원장 조회 API (읽기 전용, 모의투자 전용).
 *
 * GET /api/daily-pnl?days=30 — 거래일별 시작/마감 총자산·예수금과 그 차이
 *
 * <p>거래 단위 손익이 아니라 <b>하루 단위</b>다. 모의 체결조회가 매도에 빈 응답을 주는
 * 결함(CLAUDE.md 결함 5)으로 거래별 실현손익은 막혀 있지만, 하루의 시작과 끝을 비교하면
 * 수수료·세금이 이미 빠진 실제 증감을 알 수 있다.
 *
 * <p>마감을 못 찍은 날(그 시각에 앱이 꺼져 있던 날)은 closed=false로 남고 합계에서 빠진다.
 */
@RestController
@RequestMapping("/api/daily-pnl")
@Profile("paper")
public class DailyPnlController {

    private static final int MAX_DAYS = 365;

    private final DailyEquityRepository dailyEquityRepository;
    private final Clock clock;

    public DailyPnlController(DailyEquityRepository dailyEquityRepository, Clock clock) {
        this.dailyEquityRepository = dailyEquityRepository;
        this.clock = clock;
    }

    @GetMapping
    public Map<String, Object> getDailyPnl(@RequestParam(defaultValue = "30") int days) {
        int boundedDays = Math.max(1, Math.min(days, MAX_DAYS));
        LocalDate from = LocalDate.now(clock).minusDays(boundedDays - 1L);

        List<DailyEquity> ledger =
                dailyEquityRepository.findByTradeDateGreaterThanEqualOrderByTradeDateDesc(from);

        double netPnlSum = ledger.stream()
                .filter(DailyEquity::isClosed)
                .mapToDouble(DailyEquity::getNetPnl)
                .sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days",       boundedDays);
        result.put("from",       from.toString());
        result.put("closedDays", ledger.stream().filter(DailyEquity::isClosed).count());
        result.put("netPnlSum",  Math.round(netPnlSum));
        result.put("rows",       ledger.stream().map(DailyPnlController::toRow).toList());
        return result;
    }

    private static Map<String, Object> toRow(DailyEquity d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date",          d.getTradeDate().toString());
        row.put("closed",        d.isClosed());
        row.put("startEquity",   Math.round(d.getStartEquity()));
        row.put("endEquity",     rounded(d.getEndEquity()));
        row.put("netPnl",        rounded(d.getNetPnl()));
        row.put("netPnlPercent", netPnlPercent(d));
        row.put("startDeposit",  rounded(d.getStartDeposit()));
        row.put("endDeposit",    rounded(d.getEndDeposit()));
        row.put("cashDelta",     rounded(d.getCashDelta()));
        return row;
    }

    /** 마감 전이거나 시작 자산이 0이면 null — 0%로 보이게 만들지 않는다 */
    private static Double netPnlPercent(DailyEquity d) {
        Double netPnl = d.getNetPnl();
        if (netPnl == null || d.getStartEquity() <= 0) return null;
        return Math.round(netPnl / d.getStartEquity() * 10_000) / 100.0;
    }

    private static Long rounded(Double value) {
        return value == null ? null : Math.round(value);
    }
}
