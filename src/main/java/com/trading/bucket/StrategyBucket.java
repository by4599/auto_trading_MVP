package com.trading.bucket;

/**
 * 전략 실험용 자금 칸(지갑 칸) 구분 — 실험 설계(2026-07-19):
 * 방식별 1,000만원 한도로 성적을 분리 기록해 우열을 비교한다.
 *
 * 거래 이름표는 Signal → OrderHistory → Position → TradeResult로 흐른다.
 * 매수는 Signal의 bucket이 원천, 매도는 Position의 bucket이 원천(보유 칸 귀속).
 */
public enum StrategyBucket {

    /** 방식1 — 변동성 돌파 (기존 전략, 비교 기준선) */
    VB("방식1·돌파"),

    /** 방식2 — 뉴스·공시 이벤트 기반 (B-4 합격 재료 대기, 비활성) */
    EVENT("방식2·이벤트"),

    /** 방식3 — 방식1(70%) + 방식2(30%) 혼합 (방식2 활성화 후 가동) */
    MIX("방식3·혼합");

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
