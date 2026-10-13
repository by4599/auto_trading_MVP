package com.trading.backtest;

import java.time.LocalDate;
import java.util.List;

/**
 * B-4 이벤트 리포트 1건의 입력 — 표본 범위 + 네 갈래 통계.
 *
 * <p>grouped/spillover/triggers는 비어 있을 수 있고, 비면 해당 절이 리포트에서 빠진다.
 */
record EventReportData(List<String> symbols, LocalDate from, LocalDate to,
                       List<EventStatsBacktester.EventStat> stats,
                       List<EventStatsBacktester.EventStat> groupedStats,
                       List<SpilloverStatsBacktester.SpilloverStat> spillover,
                       List<EntryTriggerBacktester.TriggerStat> triggers) {}
