package com.trading.backtest;

import com.trading.backtest.EventStatsBacktester.Quantiles;

/**
 * 리포트·로그 공통 서식 — 백분율, PF, 판정 문구, 경과 시간.
 *
 * <p>리포트 파일과 실행 로그가 같은 숫자를 다르게 찍지 않도록 한곳에 모았다.
 */
final class ReportFormat {

    private ReportFormat() {
    }

    /** 무한대(손실 0)는 "inf" — %.2f로 찍으면 Infinity가 표에 새어 나온다 */
    static String pf(double profitFactor) {
        return Double.isInfinite(profitFactor) ? "inf" : String.format("%.2f", profitFactor);
    }

    /** 비용·비율 로그용 (소수 셋째 자리) */
    static String pct(double rate) {
        return String.format("%.3f%%", rate * 100);
    }

    /** 부호 있는 백분율 */
    static String signedPct(double ratio) {
        return String.format("%+.2f%%", ratio * 100);
    }

    /**
     * 부호 있는 백분율 — 빈 표본(n=0)이면 {@code "-"}. 표본이 없는 칸을 {@code +0.00%}로 찍으면
     * "측정했는데 차이가 없다"로 오독된다(측정 못 함과 차이 0은 다르다).
     */
    static String signedPct(Quantiles q) {
        return q.n() == 0 ? "-" : signedPct(q.median());
    }

    /** 저−고 차 — 두 버킷이 함께 있는 이벤트가 0건이면 {@code "-"} */
    static String lowMinusHighText(LowVolCrashBacktester.HorizonStat h) {
        return h.events() == 0 ? "-" : signedPct(h.lowMinusHigh());
    }

    static String verdict(BacktestReportWriter.Judgment judgment) {
        return judgment.pass() ? "✅ 합격" : "❌ 불합격";
    }

    static String elapsedSec(long startNanos) {
        return String.format("%.1f", (System.nanoTime() - startNanos) / 1_000_000_000.0);
    }
}
