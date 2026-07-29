package com.trading.risk;

import com.trading.order.FillStateUpdater;
import com.trading.order.KisOrderClient;
import com.trading.order.OrderCancelClient;
import com.trading.order.OrderHistory;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderStatus;
import com.trading.position.BalanceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BrokerageApiClient 모의투자 구현체 — 강제청산(LiquidationService)의 실행 손발.
 * Gate 2(감사 F-3)에서 스텁 → 실구현으로 전환.
 *
 * 재사용: 잔고는 BalanceClient(무캐시 — 청산 시 최신 상태 보장),
 * 매도는 KisOrderClient.sell(수량 지정), 취소는 OrderCancelClient.
 *
 * 한계(운영 문서화됨): 미체결 취소 대상을 KIS 조회가 아니라 자체 order_history에서
 * 찾는다. 앱 밖(HTS 수동)에서 낸 주문은 취소하지 못한다 — v1은 모든 주문이 앱 경유.
 */
@Component
@Profile("paper")
public class KisBrokerageApiClient implements BrokerageApiClient {

    private static final Logger log = LoggerFactory.getLogger(KisBrokerageApiClient.class);

    private final BalanceClient balanceClient;
    private final KisOrderClient orderClient;
    private final OrderCancelClient cancelClient;
    private final OrderHistoryRepository orderHistoryRepository;
    private final FillStateUpdater fillStateUpdater;

    public KisBrokerageApiClient(BalanceClient balanceClient,
                                 KisOrderClient orderClient,
                                 OrderCancelClient cancelClient,
                                 OrderHistoryRepository orderHistoryRepository,
                                 FillStateUpdater fillStateUpdater) {
        this.balanceClient = balanceClient;
        this.orderClient = orderClient;
        this.cancelClient = cancelClient;
        this.orderHistoryRepository = orderHistoryRepository;
        this.fillStateUpdater = fillStateUpdater;
    }

    @Override
    public void cancelAllPendingOrders() {
        List<OrderHistory> pending = orderHistoryRepository.findByStatusIn(
                List.of(OrderStatus.ACCEPTED, OrderStatus.PARTIAL_FILLED));
        if (pending.isEmpty()) {
            log.info("[청산 준비] 취소할 미체결 주문 없음");
            return;
        }

        log.warn("[청산 준비] 미체결 주문 {}건 일괄 취소 개시", pending.size());
        for (OrderHistory order : pending) {
            try {
                switch (cancelClient.cancelAll(order.getOrderNo())) {
                    // 최종 취소 확정은 FillPoller의 finalizeAfterCancel 경로가 담당
                    case SENT -> fillStateUpdater.markCancelRequested(order.getId());
                    // 이미 체결/종료 — 취소할 잔량 없음. 잔고는 청산 본체가 실잔고 기준으로 처리
                    case NO_OPEN_QTY -> log.warn(
                            "[청산 준비] 취소할 잔량 없음(이미 체결/종료) — 건너뜀: ordNo={}", order.getOrderNo());
                    case FAILED -> log.error(
                            "[청산 준비] 취소 접수 실패 — 계속 진행: ordNo={}", order.getOrderNo());
                }
            } catch (Exception e) {
                // 한 건의 실패가 전체 취소 루프를 멈추지 않는다 (종목별 예외 격리 — ADR 2.3)
                log.error("[청산 준비] 취소 처리 오류 — 계속 진행: ordNo={}", order.getOrderNo(), e);
            }
        }
    }

    @Override
    public ActualAccountInfo getActualAccountAsset() {
        List<ActualPosition> holdings = balanceClient.fetchBalance().holdings().stream()
                .map(h -> new ActualPosition(h.stockCode(), h.quantity()))
                .toList();
        return new ActualAccountInfo(holdings);
    }

    @Override
    public void sendMarketOrder(String ticker, String side, int quantity) {
        if (!"SELL".equals(side)) {
            // ADR 2.3: 청산 경로는 매도 전용. 매수는 반드시 OrderEngine 경로.
            throw new IllegalArgumentException("청산 경로는 매도 전용입니다: side=" + side);
        }
        orderClient.sell(ticker, quantity);
    }

    @Override
    public int getActualHoldingQuantity(String ticker) {
        return balanceClient.fetchBalance().holdings().stream()
                .filter(h -> h.stockCode().equals(ticker))
                .mapToInt(BalanceClient.Holding::quantity)
                .findFirst()
                .orElse(0);
    }
}
