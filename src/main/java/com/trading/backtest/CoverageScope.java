package com.trading.backtest;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 커버리지 검사가 "무엇을" 검증했는지 — 채점 대상을 식별하는 키.
 *
 * <p>배경: {@link CandleCoverageChecker#lastReport()}는 실행당 하나뿐인 가변 필드다. 스코프 A를
 * 검사한 뒤 스코프 B의 기준선을 기록하면 <b>엇갈린 리포트로 관문이 통과</b>한다
 * (risk-auditor 2026-08-17 LOW: "lastReport 단일 필드의 창 혼선"). 그래서 검사 결과에
 * 스코프를 붙여 두고, 기준선을 쓰기 직전에 "지금 채점한 대상"과 대조한다.
 *
 * <p>동일성 판정은 <b>종목 집합 + 창 끝</b>으로만 한다({@link #coversSameDataAs}) — 커버리지가
 * 답하는 질문이 "이 종목들의 캔들이 이 날짜까지 있는가"뿐이기 때문이다. {@code mode}는 사람이
 * 읽는 이름표라서 비교에 넣지 않는다: 같은 데이터로 돌면서 이름만 바꾸는 경로가 실제로 있고
 * ({@code LabScope.withNames}, full 모드의 빈 슬러그) 그걸 불일치로 보면 정당한 기록을 막는다.
 *
 * @param mode       검사를 요청한 모드/랩 이름 (기록·메시지용)
 * @param symbols    검사한 종목들
 * @param windowEnd  검사한 판정 창의 끝
 */
record CoverageScope(String mode, List<String> symbols, LocalDate windowEnd) {

    CoverageScope {
        symbols = List.copyOf(symbols);
    }

    /** 같은 데이터 질문에 대한 답인가 — 종목 집합과 창 끝이 같아야 한다 */
    boolean coversSameDataAs(CoverageScope other) {
        return other != null
                && windowEnd.equals(other.windowEnd())
                && Set.copyOf(symbols).equals(Set.copyOf(other.symbols()));
    }

    String describe() {
        return String.format("%s / 종목 %d개 / 창 끝 %s",
                mode == null || mode.isBlank() ? "(이름 없음)" : mode, symbols.size(), windowEnd);
    }
}
