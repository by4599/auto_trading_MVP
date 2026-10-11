package com.trading.position;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 실현손익 기반 연속 손실 카운터 (감사 F-5 나머지).
 *
 * 집계 단위는 라운드트립(진입~전량 청산) 1회다:
 *   손실(< 0)  → 카운트 +1
 *   수익/본전  → 카운트 0으로 리셋
 *
 * 매도가 여러 조각으로 체결돼도 한 매매는 한 번만 센다 — 조각 손익은 Position에 누적되고
 * 보유 수량이 0이 되는 순간 합계로 판정한다. 일부만 줄인 상태(Trim)는 아직 세지 않는다.
 *
 * 카운트는 portfolio_state에 영속화되어 장중 재시작에도 유지된다 (peakEquity와 동일 패턴).
 * ConsecutiveLossRule이 3회 도달 시 1시간 매수 차단 + resetStreak() 호출.
 */
@Component
public class TradeResultTracker {

    private static final Logger log = LoggerFactory.getLogger(TradeResultTracker.class);

    private final PortfolioStateRepository portfolioStateRepository;

    public TradeResultTracker(PortfolioStateRepository portfolioStateRepository) {
        this.portfolioStateRepository = portfolioStateRepository;
    }

    /**
     * 라운드트립 1회(진입~전량 청산)의 실현손익을 반영한다. 호출부(FillStateUpdater)의
     * 트랜잭션 안에서 호출되므로 포지션 갱신과 카운트 갱신이 원자적으로 커밋된다.
     *
     * 조각 체결을 어떻게 합칠지는 호출부의 책임이다 — 여기는 이미 확정된 한 매매의 손익만 받는다.
     */
    public void recordRoundTrip(String stockCode, double realizedPnl) {
        int previous = getConsecutiveLossCount();
        int updated = realizedPnl < 0 ? previous + 1 : 0;

        portfolioStateRepository.save(
                PortfolioState.of(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT, updated));

        if (realizedPnl < 0) {
            log.warn("[TradeResult] 손실 확정: stockCode={} 실현손익={} 연속손실={}회",
                    stockCode, String.format("%.0f", realizedPnl), updated);
        } else {
            log.info("[TradeResult] 수익/본전 확정: stockCode={} 실현손익={} — 연속손실 리셋",
                    stockCode, String.format("%.0f", realizedPnl));
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
