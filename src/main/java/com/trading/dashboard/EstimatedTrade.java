package com.trading.dashboard;

import com.trading.bucket.StrategyBucket;

import java.time.LocalDateTime;

/**
 * 짝지어진 거래 하나 (매수 로트 ↔ 매도 로트). <b>매도가는 추정치</b>다
 * ({@link SellPriceEstimator}).
 *
 * <p>칸(bucket)은 <b>매수</b> 주문의 것을 쓴다 — 매도 주문에는 칸이 붙지 않기 때문이다
 * (2026-09-21 운영 DB 실측: 체결 매도 104건 전부 {@code bucket=null}).
 *
 * <p>수수료·세금은 빼지 않은 <b>총손익</b>이다. 추정가로 계산한 값에 비용까지 얹으면
 * 정확해 보이지만 실제로는 오차만 커진다 — 정확한 금액은 계좌 기준
 * ({@code /api/performance/account})을 봐야 한다.
 */
public record EstimatedTrade(String stockCode,
                             StrategyBucket bucket,
                             LocalDateTime boughtAt,
                             LocalDateTime soldAt,
                             int quantity,
                             double buyPrice,
                             double sellPrice,
                             SellPriceEstimator.Source sellSource) {

    /** 손익(원) — 추정 매도가 기준 */
    public double pnl() {
        return (sellPrice - buyPrice) * quantity;
    }

    /** 수익률(%) — 추정 매도가 기준 */
    public double returnPercent() {
        return buyPrice <= 0 ? 0 : (sellPrice - buyPrice) / buyPrice * 100;
    }

    public boolean isWin()  { return sellPrice > buyPrice; }
    public boolean isLoss() { return sellPrice < buyPrice; }
}
