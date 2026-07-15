package com.trading.dashboard;

import com.trading.position.TradeResult;
import com.trading.position.TradeResultRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;

/**
 * trade_result를 기간별 버킷(일/주/월)으로 집계한다 (실적 탭 백엔드).
 * 데이터량이 작아(1인 운영, 일 수 건) 자바 스트림 집계로 충분하다.
 */
@Component
public class PerformanceService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final TradeResultRepository tradeResultRepository;

    public PerformanceService(TradeResultRepository tradeResultRepository) {
        this.tradeResultRepository = tradeResultRepository;
    }

    public Map<String, Object> performance(String period, int days) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(days);
        List<TradeResult> results = tradeResultRepository.findByTradeDateBetweenOrderBySoldAtAsc(from, to);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("summary", summary(results));
        out.put("buckets", buckets(results, period));
        return out;
    }

    private Map<String, Object> summary(List<TradeResult> results) {
        double totalPnl = results.stream().mapToDouble(TradeResult::getRealizedPnl).sum();
        long winCount = results.stream().filter(r -> r.getRealizedPnl() > 0).count();
        long lossCount = results.stream().filter(r -> r.getRealizedPnl() < 0).count();
        double avgWin = results.stream().filter(r -> r.getRealizedPnl() > 0)
                .mapToDouble(TradeResult::getRealizedPnl).average().orElse(0);
        double avgLoss = results.stream().filter(r -> r.getRealizedPnl() < 0)
                .mapToDouble(TradeResult::getRealizedPnl).average().orElse(0);

        // 일 단위 최고/최악 (버킷 기간과 무관하게 일자 기준)
        Map<LocalDate, Double> byDay = new TreeMap<>();
        results.forEach(r -> byDay.merge(r.getTradeDate(), r.getRealizedPnl(), Double::sum));
        Map.Entry<LocalDate, Double> best = byDay.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);
        Map.Entry<LocalDate, Double> worst = byDay.entrySet().stream()
                .min(Map.Entry.comparingByValue()).orElse(null);

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalPnl",   Math.round(totalPnl));
        s.put("tradeCount", results.size());
        s.put("winCount",   winCount);
        s.put("lossCount",  lossCount);
        s.put("winRate",    results.isEmpty() ? null
                : Math.round((double) winCount / results.size() * 1000.0) / 10.0);
        s.put("avgWin",     Math.round(avgWin));
        s.put("avgLoss",    Math.round(avgLoss));
        s.put("bestDay",    best  != null ? Map.of("date", best.getKey().toString(),  "pnl", Math.round(best.getValue()))  : null);
        s.put("worstDay",   worst != null ? Map.of("date", worst.getKey().toString(), "pnl", Math.round(worst.getValue())) : null);
        return s;
    }

    private List<Map<String, Object>> buckets(List<TradeResult> results, String period) {
        // TreeMap 키 정렬 = 시간순 (라벨이 사전순=시간순이 되도록 설계)
        Map<String, List<TradeResult>> grouped = new TreeMap<>();
        for (TradeResult r : results) {
            grouped.computeIfAbsent(bucketLabel(r.getTradeDate(), period), k -> new ArrayList<>()).add(r);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        double cum = 0;
        for (Map.Entry<String, List<TradeResult>> e : grouped.entrySet()) {
            double pnl = e.getValue().stream().mapToDouble(TradeResult::getRealizedPnl).sum();
            cum += pnl;
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("label",  e.getKey());
            b.put("pnl",    Math.round(pnl));
            b.put("cumPnl", Math.round(cum));
            b.put("trades", e.getValue().size());
            b.put("wins",   e.getValue().stream().filter(r -> r.getRealizedPnl() > 0).count());
            out.add(b);
        }
        return out;
    }

    private static String bucketLabel(LocalDate date, String period) {
        return switch (period) {
            case "weekly" -> {
                WeekFields wf = WeekFields.ISO;
                yield String.format("%d-W%02d",
                        date.get(wf.weekBasedYear()), date.get(wf.weekOfWeekBasedYear()));
            }
            case "monthly" -> date.format(MONTH_FMT);
            default -> date.toString(); // daily
        };
    }
}
