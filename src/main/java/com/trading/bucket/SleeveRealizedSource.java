package com.trading.bucket;

import java.time.LocalDate;

/**
 * 칸 실현손익의 원천 — "그 칸의 끝난 거래가 얼마를 벌고 잃었나".
 *
 * <p>구현은 대시보드 쪽(매도가 추정기·거래 짝짓기)에 있다. bucket 패키지가 dashboard를 직접
 * 의존하지 않도록 여기에는 이 작은 인터페이스만 두고, 어댑터가 반대 방향에서 구현한다.
 *
 * <p>약속: TradeResult(실제 체결가로 남은 기록)가 있으면 그 값, 없으면 매도가 추정, 그것도 못 하면
 * 측정 불가(0원 + 개수). 기간은 <b>매도 날짜</b> 기준 [fromInclusive, toExclusive).
 */
public interface SleeveRealizedSource {

    SleeveRealized realized(StrategyBucket bucket, LocalDate fromInclusive, LocalDate toExclusive);

    /**
     * 다음 호출은 직전 조회를 다시 쓰지 말고 DB를 새로 확인하라는 요청 — 사람의 잠금 해제처럼 그 순간의 값이
     * 꼭 맞아야 할 때 부른다(46b N-2). 재사용을 하지 않는 구현은 아무것도 안 해도 된다.
     */
    default void refreshOnNextCall() {
    }
}
