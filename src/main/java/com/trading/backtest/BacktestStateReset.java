package com.trading.backtest;

import com.trading.order.OrderHistoryRepository;
import com.trading.position.DailyEquityRepository;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.PositionRepository;
import com.trading.position.ShadowPortfolio;
import com.trading.risk.ConsecutiveLossRule;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import com.trading.risk.TrailingStopTracker;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 백테스트 런 간 상태 초기화 (계획 D1).
 *
 * 한 JVM에서 수십 런(K 스윕 × WF 윈도우 × 필터 변형)을 순차 실행하므로,
 * 이전 런의 상태(peakEquity, 연속손실 차단시각, 포지션/주문 행)가 다음 런으로
 * 누출되면 결과가 오염된다. candle_history는 데이터 자산이므로 건드리지 않는다.
 */
@Component
@Profile("backtest")
public class BacktestStateReset {

    private final OrderHistoryRepository orderHistoryRepository;
    private final PositionRepository positionRepository;
    private final PortfolioStateRepository portfolioStateRepository;
    private final DailyEquityRepository dailyEquityRepository;
    private final TradingStatusManager statusManager;
    private final ShadowPortfolio shadowPortfolio;
    private final ConsecutiveLossRule consecutiveLossRule;
    private final BacktestPositionManager positionManager;
    private final TradeRecorder tradeRecorder;
    private final TrailingStopTracker trailingStopTracker;

    public BacktestStateReset(OrderHistoryRepository orderHistoryRepository,
                              PositionRepository positionRepository,
                              PortfolioStateRepository portfolioStateRepository,
                              DailyEquityRepository dailyEquityRepository,
                              TradingStatusManager statusManager,
                              ShadowPortfolio shadowPortfolio,
                              ConsecutiveLossRule consecutiveLossRule,
                              BacktestPositionManager positionManager,
                              TradeRecorder tradeRecorder,
                              TrailingStopTracker trailingStopTracker) {
        this.orderHistoryRepository = orderHistoryRepository;
        this.positionRepository = positionRepository;
        this.portfolioStateRepository = portfolioStateRepository;
        this.dailyEquityRepository = dailyEquityRepository;
        this.statusManager = statusManager;
        this.shadowPortfolio = shadowPortfolio;
        this.consecutiveLossRule = consecutiveLossRule;
        this.positionManager = positionManager;
        this.tradeRecorder = tradeRecorder;
        this.trailingStopTracker = trailingStopTracker;
    }

    @Transactional
    public void reset() {
        orderHistoryRepository.deleteAllInBatch();
        positionRepository.deleteAllInBatch();
        portfolioStateRepository.deleteAllInBatch();
        dailyEquityRepository.deleteAllInBatch();

        statusManager.changeMode(TradingMode.RUNNING);
        shadowPortfolio.reset();
        consecutiveLossRule.reset();
        positionManager.resetCash();
        tradeRecorder.reset();
        trailingStopTracker.clearAll();
    }
}
