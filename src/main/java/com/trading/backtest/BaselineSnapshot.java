package com.trading.backtest;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 거버넌스 기준선 yml(= §14.1 회귀 앵커)이 담는 값 묶음 — 기록부와 판독부가 공유하는 표현.
 *
 * <p>지표를 double이 아니라 <b>yml에 적히는 문자열 그대로</b> 들고 있는다: 앵커의 해상도는
 * 파일에 기록된 자릿수(PF 소수 3자리 등)이고, 대조도 그 자릿수에서 해야 "파일에 적힌 값을
 * 그대로 재현했는가"에 곧바로 답할 수 있다. 포맷 상수를 여기 한 곳에 모아 두어
 * 기록부({@link BacktestReportWriter#writeBaseline})와 판독부({@link BaselineStore})가
 * 어긋날 수 없게 한다.
 */
record BaselineSnapshot(LocalDate from, LocalDate to, int trades,
                        String profitFactor, String expectancyPct,
                        String maxDrawdown, String winRate) {

    static BaselineSnapshot of(LocalDate from, LocalDate to, BacktestMetrics m) {
        return new BaselineSnapshot(from, to, m.tradeCount(),
                pf(m.profitFactor()), expectancy(m.expectancyPct()),
                drawdown(m.maxDrawdown()), winRate(m.winRate()));
    }

    // ── yml 기록 포맷 (이 값이 곧 앵커의 해상도) ────────────────────────────────
    static String pf(double v)         { return String.format("%.3f", v); }
    static String expectancy(double v) { return String.format("%.4f", v); }
    static String drawdown(double v)   { return String.format("%.4f", v); }
    static String winRate(double v)    { return String.format("%.4f", v); }

    /** 판정 창이 같은가 — 다르면 대조 대상이 아니다(창을 일부러 바꾼 실행은 정상 사용) */
    boolean sameWindow(BaselineSnapshot other) {
        return Objects.equals(from, other.from) && Objects.equals(to, other.to);
    }

    /** 앵커(this) 대비 현재값 5지표 — 같고 다름은 각 행의 {@link MetricDiff#drifted()} */
    List<MetricDiff> compare(BaselineSnapshot current) {
        return List.of(
                new MetricDiff("트레이드", String.valueOf(trades), String.valueOf(current.trades())),
                new MetricDiff("PF", profitFactor, current.profitFactor()),
                new MetricDiff("기대값", expectancyPct, current.expectancyPct()),
                new MetricDiff("MDD", maxDrawdown, current.maxDrawdown()),
                new MetricDiff("승률", winRate, current.winRate()));
    }

    record MetricDiff(String metric, String anchor, String current) {
        boolean drifted() { return !anchor.equals(current); }
        String arrow()    { return metric + " " + anchor + "→" + current; }
    }
}
