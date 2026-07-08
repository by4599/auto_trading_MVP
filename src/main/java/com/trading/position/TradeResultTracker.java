package com.trading.position;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 실현손익 기반 연속 손실 카운터 (감사 F-5 나머지).
 *
 * 매도 체결(FillStateUpdater)이 실현손익을 기록하면:
 *   손실(< 0)  → 카운트 +1
 *   수익/본전  → 카운트 0으로 리셋
 *
 * 카운트는 portfolio_state에 영속화되어 장중 재시작에도 유지된다 (peakEquity와 동일 패턴).
 * ConsecutiveLossRule이 3회 도달 시 1시간 매수 차단 + resetStreak() 호출.
 *
 * v1 한계: 매도 체결 청크 단위로 1회 기록한다. 주문 1주 고정이라 "1 거래 = 1 매도 = 1 기록"이
 * 성립하며, v2에서 부분 체결 매도가 생기면 라운드트립 단위 집계로 바꿔야 한다.
 */
@Component
public class TradeResultTracker {

    private static final Logger log = LoggerFactory.getLogger(TradeResultTracker.class);

    private final PortfolioStateRepository portfolioStateRepository;

    public TradeResultTracker(PortfolioStateRepository portfolioStateRepository) {
        this.portfolioStateRepository = portfolioStateRepository;
    }

    /**
     * 매도 체결의 실현손익을 반영한다. FillStateUpdater의 트랜잭션 안에서 호출되므로
     * 포지션 갱신과 카운트 갱신이 원자적으로 커밋된다.
     */
    public void recordSellFill(String stockCode, int quantity, double sellPrice, double avgBuyPrice) {
        double realized = (sellPrice - avgBuyPrice) * quantity;
        int previous = getConsecutiveLossCount();
        int updated = realized < 0 ? previous + 1 : 0;

        portfolioStateRepository.save(
                PortfolioState.of(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT, updated));

        if (realized < 0) {
            log.warn("[TradeResult] 손실 확정: stockCode={} qty={} 실현손익={} 연속손실={}회",
                    stockCode, quantity, String.format("%.0f", realized), updated);
        } else {
            log.info("[TradeResult] 수익/본전 확정: stockCode={} qty={} 실현손익={} — 연속손실 리셋",
                    stockCode, quantity, String.format("%.0f", realized));
        }
    }

    public int getConsecutiveLossCount() {
        return portfolioStateRepository.findById(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT)
                .map(s -> (int) s.getStateValue())
                .orElse(0);
    }

    /** 차단 개시 시 호출 — 차단 해제 후 같은 카운트로 즉시 재차단되는 것을 막는다. */
    public void resetStreak() {
        portfolioStateRepository.save(
                PortfolioState.of(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT, 0));
    }
}
