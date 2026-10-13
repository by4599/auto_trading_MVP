package com.trading.risk;

import java.util.Optional;

/**
 * 지수 레짐 판정 데이터 경계 (설계 문서 §3.3 — 일봉 변형).
 *
 * "약세 레짐" = KOSPI 당일 시가 < 전일 종가 (갭다운).
 * 원안("지수가 당일 시가 아래")은 장중 지수 현재가가 필요해 일봉으로는
 * 선견 편향 없이 판정 불가 — 갭다운 변형은 개장 직후 확정되는 정보만 쓴다.
 */
public interface IndexRegimeSource {

    /** 판정 불가(데이터 없음)면 empty — 룰은 통과시킨다 (오탐 방지) */
    Optional<Boolean> isBearishRegime();

    /**
     * 지수 종가가 N거래일 이동평균 아래인가(하락 <b>추세</b>). 판단 불가면 empty.
     *
     * <p>위 갭다운 판정과 <b>별개의 질문</b>이다 — 갭다운은 하루, 이쪽은 수개월~1년짜리
     * 국면을 본다(§14.4 하락 추세 휩쏘 차단 실험). 기본 구현이 empty라서 아직 지수
     * 시계열이 없는 구현체(paper 등)는 그대로 두면 된다 — 필터는 통과시킨다.
     *
     * <p><b>선견편향 금지</b>: 진입 판단은 장중에 일어나므로 구현체는 <b>전일까지</b>의
     * 종가만 써야 한다(당일 종가·당일 종가를 포함한 이동평균은 미래 정보다).
     *
     * @param maPeriod 이동평균 기간(거래일). 표본이 이보다 적으면 empty.
     */
    default Optional<Boolean> isBelowTrend(int maPeriod) {
        return Optional.empty();
    }
}
