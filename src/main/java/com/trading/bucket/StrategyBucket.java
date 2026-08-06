package com.trading.bucket;

/**
 * 전략 실험용 자금 칸(지갑 칸) 구분 — 실험 설계(2026-07-19, 방식 재정의 2026-07-20):
 * 방식별 1,000만원 한도로 성적을 분리 기록해 우열을 비교한다.
 *
 * 거래 이름표는 Signal → OrderHistory → Position → TradeResult로 흐른다.
 * 매수는 Signal의 bucket이 원천, 매도는 Position의 bucket이 원천(보유 칸 귀속).
 *
 * ⚠ 2026-07-20: 방식2·3은 원래 EVENT(공시)·MIX(혼합)이었으나 B-4 재검증(CANDIDATE 0건,
 * BACKTEST-DESIGN §12)으로 재료가 없어 폐기하고, 이동평균선·스캘핑으로 교체했다.
 * enum 상수명(EVENT/MIX)은 DB 컬럼 호환을 위해 유지 — displayName만 새 의미를 반영한다.
 * 둘 다 **백테스트 검증 없이** 사용자 판단으로 바로 모의투자에 연결(2026-07-20).
 */
public enum StrategyBucket {

    /** 방식1 — 변동성 돌파 (기존 전략, 비교 기준선) */
    VB("방식1·돌파"),

    /** 방식2 — 이동평균선 정배열 돌파 (MovingAverageBreakoutStrategy, 검증 없이 가동) */
    EVENT("방식2·이평선"),

    /** 방식3 — 눌림목 반등 스캘핑 (ScalpingStrategy, 검증 없이 가동) */
    MIX("방식3·스캘핑"),

    /**
     * A동 — 다일 추세추종 (DonchianBreakoutStrategy). ADR-001 Sleeve A에 해당.
     *
     * 위 셋(당일 청산)과 성격이 근본적으로 다르다: 며칠 보유하고, 사이징·손절·트레일링
     * 값이 정반대이며(0.25R·ATR1.0·arm1%/trail3%), 15:15 타임컷에서 제외된다.
     * 그래서 기존 VB 칸에 얹지 않고 자기 칸을 쓴다 — 얹으면 자금과 성적이 섞인다.
     * 기본 잠금(trend-enabled=false) — ADR-001 개정 승인 전까지 켜지 않는다.
     */
    TREND("A동·추세추종(돈치안)");

    private final String displayName;

    StrategyBucket(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 레거시 데이터(칸 도입 이전 행)는 전부 방식1 소속으로 간주한다 */
    public static StrategyBucket orDefault(StrategyBucket bucket) {
        return bucket != null ? bucket : VB;
    }
}
