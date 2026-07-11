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
}
