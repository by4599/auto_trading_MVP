package com.trading.dashboard;

import com.trading.position.DailyEquity;
import com.trading.position.DailyEquityRepository;
import com.trading.position.ShadowPortfolio;
import com.trading.risk.RiskLimitsProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 계좌 잔고 원장(daily_equity) 기준 성적 — "얼마 벌었나"의 정본.
 *
 * <p>왜 trade_result가 아닌가: 모의 체결조회가 매도에 빈 응답을 주는 결함(CLAUDE.md 결함 5)으로
 * trade_result.sell_price가 0원으로 기록돼 손익이 과대계상된다(실적 탭이 -5,014,000원을 보여줬으나
 * 실제는 -212,040원). daily_equity는 하루의 시작·마감 총자산이라 수수료·세금이 이미 반영돼 있고
 * 그 결함의 영향을 받지 않는다.
 *
 * <p>읽기 전용이다 — KIS를 부르지 않고(레이트리밋 소모 없음) 판정 로직도 건드리지 않는다.
 *
 * <p>두 가지 주의:
 * <ul>
 *   <li><b>daily_equity에는 주말 행도 들어 있다</b>(2026-09-19 토·09-20 일 실측). 손익 집계는
 *       마감(closed)을 찍은 행만 쓰므로 주말이 "손익 0인 거래일"로 세어지지 않는다.
 *       시계열은 값이 있는 날을 그대로 그린다 — 값이 이어지는 주말 행은 낙폭을 흔들지 않는다.</li>
 *   <li><b>전고점은 원장 최고가 아니라 {@link ShadowPortfolio} 값</b>이다 —
 *       {@code GlobalEquityStopRule}·{@code RiskMonitor}가 보는 바로 그 값이어야 화면과 안전장치가
 *       같은 숫자를 말한다. 한도(mddLimit)도 같은 출처({@link RiskLimitsProperties})를 쓴다.</li>
 * </ul>
 */
@Component
@Profile("paper")
public class AccountPerformanceService {

    private static final int MAX_DAYS = 3650;

    static final String SOURCE =
            "daily_equity — 계좌 잔고 원장(수수료·세금 반영). 모의 매도 체결가 결함의 영향을 받지 않는다";

    private final DailyEquityRepository dailyEquityRepository;
    private final ShadowPortfolio shadowPortfolio;
    private final RiskLimitsProperties limits;
    private final Clock clock;

    public AccountPerformanceService(DailyEquityRepository dailyEquityRepository,
                                     ShadowPortfolio shadowPortfolio,
                                     RiskLimitsProperties limits,
                                     Clock clock) {
        this.dailyEquityRepository = dailyEquityRepository;
        this.shadowPortfolio       = shadowPortfolio;
        this.limits                = limits;
        this.clock                 = clock;
    }

    public Map<String, Object> accountPerformance(int days) {
        int boundedDays = Math.max(1, Math.min(days, MAX_DAYS));
        LocalDate from = LocalDate.now(clock).minusDays(boundedDays - 1L);

        // 리포지토리는 최신 날짜가 먼저다. 시계열·러닝 최대는 시간순으로 훑어야 하므로 뒤집어 쓴다.
        List<DailyEquity> newestFirst =
                dailyEquityRepository.findByTradeDateGreaterThanEqualOrderByTradeDateDesc(from);
        List<DailyEquity> oldestFirst = newestFirst.reversed();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days",   boundedDays);
        result.put("from",   from.toString());
        result.put("source", SOURCE);
        result.putAll(equitySummary(oldestFirst));
        result.putAll(drawdown(oldestFirst));
        result.putAll(dailyLedger(newestFirst));
        result.put("series", oldestFirst.stream().map(AccountPerformanceService::seriesPoint).toList());
        return result;
    }

    // ── 누적 성적 ─────────────────────────────────────────────────────────────

    /** 최초 기록의 시작 자산과 마지막 기록의 총자산 차이 — 이것이 "실제로 얼마 벌었나"다 */
    private static Map<String, Object> equitySummary(List<DailyEquity> oldestFirst) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (oldestFirst.isEmpty()) {
            m.put("initialEquity", null);
            m.put("currentEquity", null);
            m.put("cumulativePnl", null);
            m.put("cumulativeReturnPercent", null);
            return m;
        }
        double initial = oldestFirst.getFirst().getStartEquity();
        double current = equityOf(oldestFirst.getLast());

        m.put("initialEquity", Math.round(initial));
        m.put("currentEquity", Math.round(current));
        m.put("cumulativePnl", Math.round(current - initial));
        m.put("cumulativeReturnPercent", initial > 0 ? percent((current - initial) / initial) : null);
        return m;
    }

    // ── 낙폭 · 강제정지 문턱 ──────────────────────────────────────────────────

    private Map<String, Object> drawdown(List<DailyEquity> oldestFirst) {
        double peak = shadowPortfolio.getPeakEquity();
        Double current = oldestFirst.isEmpty() ? null : equityOf(oldestFirst.getLast());
        // 전고점 × (1 - 한도) = 이 아래로 내려가면 RiskMonitor가 강제청산하는 금액
        Double threshold = peak > 0 ? peak * (1 - limits.getMddLimit()) : null;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("peakEquity",     peak > 0 ? Math.round(peak) : null);
        m.put("peakUnverified", shadowPortfolio.isPeakUnverified());
        m.put("currentDrawdownPercent",
                (peak > 0 && current != null) ? percent((current - peak) / peak) : null);
        m.put("maxDrawdownPercent",  maxDrawdownPercent(oldestFirst));
        m.put("mddLimitPercent",     percent(limits.getMddLimit()));
        m.put("forcedStopThreshold", threshold == null ? null : Math.round(threshold));
        m.put("roomToThreshold",
                (threshold == null || current == null) ? null : Math.round(current - threshold));
        return m;
    }

    /**
     * 최대 낙폭 = 시계열을 시간순으로 훑으며 "그때까지의 최고점 대비 가장 많이 내려간 폭".
     * 음수(아래로 내려간 정도)로 돌려준다 — 한 번도 꺾이지 않았으면 0.
     */
    private static Double maxDrawdownPercent(List<DailyEquity> oldestFirst) {
        if (oldestFirst.isEmpty()) return null;

        double runningMax = 0;
        double worst = 0;
        for (DailyEquity d : oldestFirst) {
            double equity = equityOf(d);
            if (equity > runningMax) runningMax = equity;
            if (runningMax > 0) worst = Math.min(worst, (equity - runningMax) / runningMax);
        }
        return percent(worst);
    }

    // ── 일별 원장 ─────────────────────────────────────────────────────────────

    /** 합계는 마감(closed)을 찍은 날만 쓴다 — 마감 기록이 없는 날(주말 포함)을 0으로 세지 않는다 */
    private static Map<String, Object> dailyLedger(List<DailyEquity> newestFirst) {
        List<DailyEquity> closed = newestFirst.stream().filter(DailyEquity::isClosed).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("closedDays", (long) closed.size());
        m.put("netPnlSum",  Math.round(closed.stream().mapToDouble(DailyEquity::getNetPnl).sum()));
        m.put("daily",      newestFirst.stream().map(AccountPerformanceService::dailyRow).toList());
        return m;
    }

    private static Map<String, Object> dailyRow(DailyEquity d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date",          d.getTradeDate().toString());
        row.put("closed",        d.isClosed());
        row.put("startEquity",   Math.round(d.getStartEquity()));
        row.put("endEquity",     rounded(d.getEndEquity()));
        row.put("netPnl",        rounded(d.getNetPnl()));
        row.put("netPnlPercent", netPnlPercent(d));
        return row;
    }

    private static Map<String, Object> seriesPoint(DailyEquity d) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("date",   d.getTradeDate().toString());
        p.put("equity", Math.round(equityOf(d)));
        return p;
    }

    // ── 계산 도우미 ───────────────────────────────────────────────────────────

    /** 그날을 대표하는 총자산 — 마감을 찍었으면 마감값, 아직이면 시작값 */
    private static double equityOf(DailyEquity d) {
        return d.getEndEquity() != null ? d.getEndEquity() : d.getStartEquity();
    }

    /** 비율(0.0298) → 백분율 소수 둘째 자리(2.98) */
    private static Double percent(double ratio) {
        return Math.round(ratio * 10_000) / 100.0;
    }

    /** 마감 전이거나 시작 자산이 0이면 null — 0%로 보이게 만들지 않는다 */
    private static Double netPnlPercent(DailyEquity d) {
        Double netPnl = d.getNetPnl();
        if (netPnl == null || d.getStartEquity() <= 0) return null;
        return percent(netPnl / d.getStartEquity());
    }

    private static Long rounded(Double value) {
        return value == null ? null : Math.round(value);
    }
}
