package com.trading.backtest;

import java.time.LocalDate;
import java.util.List;

/**
 * 판정 창 끝까지 캔들이 있는지 본 결과 (채점 전 검사).
 *
 * @param windowEnd          이번 실행이 채점할 창의 끝
 * @param coveredThrough     창 끝 이전 구간에서 실제로 캔들이 있는 마지막 날 (전 종목 중 가장 늦은 날).
 *                           조회 구간에 캔들이 하나도 없으면 null
 * @param missingTradingDays coveredThrough 다음날부터 창 끝까지의 <b>거래일</b> 결측 목록
 * @param lookbackDays       조회한 소급 달력일수 (결측이 이보다 길면 목록도 여기서 끊긴다)
 */
record CandleCoverage(LocalDate windowEnd, LocalDate coveredThrough,
                      List<LocalDate> missingTradingDays, int lookbackDays) {

    boolean sufficient() {
        return missingTradingDays.isEmpty();
    }

    String summary() {
        if (sufficient()) {
            return String.format("판정 창 끝(%s)까지 커버 — 마지막 캔들 %s", windowEnd, coveredThrough);
        }
        return String.format("판정 창 끝(%s)까지 캔들 부족 — 마지막 캔들 %s, 결측 거래일 %d일 %s",
                windowEnd, coveredThrough == null ? "없음(최근 " + lookbackDays + "일)" : coveredThrough,
                missingTradingDays.size(), missingTradingDays);
    }

    String markdownLine() {
        return "## 데이터 커버리지 (채점 전 검사)\n\n- "
                + (sufficient() ? "✅ " : "⚠ **") + summary() + (sufficient() ? "" : "**")
                + "\n\n";
    }
}
