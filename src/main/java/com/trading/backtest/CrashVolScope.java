package com.trading.backtest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 급락 앵커 집계 1회분의 고정 입력 — 지수 시계열 + 유니버스 + 버킷팅 기준.
 *
 * <p>변동성 창(PRE/DURING)마다 같은 앵커·같은 전방수익률에 <b>버킷팅 기준만</b> 바꿔
 * 두 번 집계하므로, 창을 포함한 나머지 조건을 한 덩어리로 묶었다.
 */
record CrashVolScope(List<Double> kospiCloses, List<LocalDate> kospiDates,
                     Map<String, LowVolCrashBacktester.StockSeries> universe,
                     LowVolCrashBacktester.VolWindow window, int minCoverage,
                     List<Integer> horizons) {}
