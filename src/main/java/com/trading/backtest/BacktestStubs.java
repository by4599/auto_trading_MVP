package com.trading.backtest;

import com.trading.order.OrderCancelClient;
import com.trading.risk.ActualAccountInfo;
import com.trading.risk.BrokerageApiClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * backtest 프로파일 의존성 스텁 (B-2).
 *
 * LiquidationService(프로파일 무관 @Component)가 BrokerageApiClient를 요구하므로
 * no-op 구현을 공급한다 — 백테스트의 강제청산은 BacktestRunner가 종가 전량 매도로
 * 모델링하며 실 청산 상태머신은 구동하지 않는다 (설계 문서 D5).
 */
@Configuration
@Profile("backtest")
public class BacktestStubs {

    @Bean
    public OrderCancelClient backtestOrderCancelClient() {
        // 동기 체결이라 미체결 잔량이 존재하지 않는다 — 취소는 항상 성공 처리
        return orderNo -> true;
    }

    @Bean
    public BrokerageApiClient backtestBrokerageApiClient() {
        return new BrokerageApiClient() {
            @Override
            public void cancelAllPendingOrders() { /* no-op */ }

            @Override
            public ActualAccountInfo getActualAccountAsset() {
                throw new UnsupportedOperationException(
                        "백테스트는 실 청산 상태머신을 구동하지 않는다 (BacktestRunner가 모델링)");
            }

            @Override
            public void sendMarketOrder(String ticker, String side, int quantity) {
                throw new UnsupportedOperationException(
                        "백테스트는 실 청산 상태머신을 구동하지 않는다 (BacktestRunner가 모델링)");
            }

            @Override
            public int getActualHoldingQuantity(String ticker) {
                return 0;
            }
        };
    }
}
