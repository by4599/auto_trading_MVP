package com.trading.risk;

import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import com.trading.position.Account;
import com.trading.position.PositionRepository;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

/**
 * 중복 매수를 차단하는 RiskRule.
 *
 * BUY 신호에 대해 두 가지를 순서대로 검사한다.
 *   1. 이미 해당 종목을 보유 중인 경우 (Position 테이블 quantity > 0)
 *   2. 아직 체결 확인 전인 ACCEPTED 매수 주문이 있는 경우 (FillPoller 처리 전)
 *
 * 이 룰 덕분에 "1초마다 BUY 신호가 반복 발생해도 한 번만 주문"이 보장된다.
 * RiskEngine에 별도 등록 없이 @Component만으로 자동 주입된다.
 */
@Component
public class PendingOrderRule implements RiskRule {

    private final PositionRepository positionRepository;
    private final OrderHistoryRepository orderHistoryRepository;

    public PendingOrderRule(PositionRepository positionRepository,
                            OrderHistoryRepository orderHistoryRepository) {
        this.positionRepository = positionRepository;
        this.orderHistoryRepository = orderHistoryRepository;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) {
            return RiskResult.pass();
        }

        String stockCode = signal.getStockCode();

        // 1. 이미 보유 중
        boolean alreadyHolding = positionRepository.findByStockCode(stockCode)
                .map(p -> p.getQuantity() > 0)
                .orElse(false);
        if (alreadyHolding) {
            return RiskResult.reject("이미 보유 중인 종목: " + stockCode);
        }

        // 2. 체결 대기 중인 매수 주문 존재
        boolean hasPendingBuy = orderHistoryRepository
                .existsByStockCodeAndSideAndStatus(stockCode, OrderSide.BUY, OrderStatus.ACCEPTED);
        if (hasPendingBuy) {
            return RiskResult.reject("미체결 매수 주문 대기 중: " + stockCode);
        }

        return RiskResult.pass();
    }
}
