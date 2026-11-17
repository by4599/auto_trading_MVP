package com.trading.bucket;

/**
 * 한 칸의 자산 계산 결과 — 칸 자산 = 배분금 + 실현손익 + 보유 평가손익.
 *
 * <p>사이징용 칸 자산({@link BucketAccountService#equity}, 실현손익만)과는 별개다 — 낙폭 상한은
 * 보유 중인 손실까지 봐야 의미가 있고, 실현손익도 모의투자 결함을 우회한 추정치를 쓴다.
 */
public record SleeveEquity(StrategyBucket bucket, double allocation, SleeveRealized realized, double unrealized) {

    public double equity() {
        return allocation + realized.pnl() + unrealized;
    }
}
