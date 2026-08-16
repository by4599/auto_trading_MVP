package com.trading.backtest;

import org.slf4j.Logger;

import java.util.List;

/**
 * 랩 종료 요약 표 로그 — 리포트 파일을 열지 않아도 판독 가능하게 남긴다.
 *
 * <p>랩마다 글자 그대로 같은 표를 찍고 있었으므로 태그만 인자로 뽑아 한곳에 모았다.
 */
final class LabLog {

    private LabLog() {
    }

    static void logProfileTable(Logger log, String tag, List<ExitLabRow> rows) {
        log.info("[{}] 프로필 | 트레이드 | PF | 기대값 | MDD | 판정", tag);
        for (ExitLabRow row : rows) {
            BacktestMetrics m = row.result().aggregateValidation();
            log.info("[{}]   {} | {}건 | PF {} | 기대값 {}% | MDD {}% | {}", tag,
                    row.profileName(), m.tradeCount(), ReportFormat.pf(m.profitFactor()),
                    String.format("%+.3f", m.expectancyPct() * 100),
                    String.format("%.1f", m.maxDrawdown() * 100),
                    ReportFormat.verdict(row.judgment()));
        }
    }
}
