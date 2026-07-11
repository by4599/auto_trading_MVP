package com.trading.backtest;

import com.trading.order.KisOrderClient;
import com.trading.order.OrderFilledEvent;
import com.trading.order.OrderHistory;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResultTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 가상 체결 주문 클라이언트 (B-2, 설계 문서 §2.3).
 *
 * FillPoller 비동기 체인 전체를 동기 체결 하나로 대체한다:
 *   접수(ACCEPTED) → 즉시 FILLED → Position 반영 → TradeResultTracker(매도)
 *   → OrderFilledEvent 발행 (커밋 후 StopLossArmer가 ATR 손절 장착 — 프로덕션 동일 경로)
 *
 * 체결가는 시뮬 현재가에 슬리피지를 불리한 방향으로 얹는다. 현금 부족 매수는
 * 증권사 증거금 거부를 모사해 주문 자체를 거부한다.
 */
@Component
@Profile("backtest")
public class BacktestOrderClient implements KisOrderClient {

    private static final Logger log = LoggerFactory.getLogger(BacktestOrderClient.class);

    private final OrderHistoryRepository orderHistoryRepository;
    private final PositionRepository positionRepository;
    private final TradeResultTracker tradeResultTracker;
    private final ApplicationEventPublisher eventPublisher;
    private final BacktestMarketDataService market;
    private final BacktestPositionManager positionManager;
    private final TradeRecorder tradeRecorder;
    private final Clock clock;

    private final AtomicLong orderSeq = new AtomicLong();
    /** 시뮬레이터가 매도 직전에 설정하는 청산 사유 (TradeRecorder 표기용) */
    private volatile String exitContext = "UNKNOWN";

    public BacktestOrderClient(OrderHistoryRepository orderHistoryRepository,
                               PositionRepository positionRepository,
                               TradeResultTracker tradeResultTracker,
                               ApplicationEventPublisher eventPublisher,
                               BacktestMarketDataService market,
                               BacktestPositionManager positionManager,
                               TradeRecorder tradeRecorder,
                               Clock clock) {
        this.orderHistoryRepository = orderHistoryRepository;
        this.positionRepository = positionRepository;
        this.tradeResultTracker = tradeResultTracker;
        this.eventPublisher = eventPublisher;
        this.market = market;
        this.positionManager = positionManager;
        this.tradeRecorder = tradeRecorder;
        this.clock = clock;
    }

    public void setExitContext(String exitContext) {
        this.exitContext = exitContext;
    }

    @Override
    public void buy(String stockCode) {
        buy(stockCode, 1);
    }

    @Override
    public void sell(String stockCode) {
        sell(stockCode, 1);
    }

    @Override
    @Transactional
    public void buy(String stockCode, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("매수 수량은 1 이상이어야 합니다: " + quantity);
        }
        double raw = requireSimPrice(stockCode);
        double fillPrice = BacktestCosts.buyFillPrice(raw);
        double cashOut = BacktestCosts.buyCashOut(fillPrice, quantity);

        if (cashOut > positionManager.getCash()) {
            log.warn("[BacktestOrder] 매수 거부 — 현금 부족: {} 필요={} 보유={}",
                    stockCode, (long) cashOut, (long) positionManager.getCash());
            return;
        }

        OrderHistory order = OrderHistory.accepted(
                stockCode, OrderSide.BUY, quantity, nextOrderNo());
        order.markFilled(quantity, fillPrice);
        orderHistoryRepository.save(order);

        Position pos = positionRepository.findByStockCode(stockCode)
                .orElse(Position.empty(stockCode));
        pos.applyBuy(quantity, fillPrice);
        positionRepository.save(pos);

        positionManager.debit(cashOut);
        tradeRecorder.onBuyFill(stockCode, quantity, cashOut, LocalDate.now(clock));

        // 커밋 후 StopLossArmer.onOrderFilled → ATR 손절 장착 (프로덕션 동일 경로)
        eventPublisher.publishEvent(
                new OrderFilledEvent(OrderSide.BUY, stockCode, quantity, fillPrice, false));
        log.debug("[BacktestOrder] 매수 체결: {} qty={} fill={}", stockCode, quantity, (long) fillPrice);
    }

    @Override
    @Transactional
    public void sell(String stockCode, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("매도 수량은 1 이상이어야 합니다: " + quantity);
        }
        double raw = requireSimPrice(stockCode);
        double fillPrice = BacktestCosts.sellFillPrice(raw);

        Position pos = positionRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new IllegalStateException("보유 없는 매도: " + stockCode));

        OrderHistory order = OrderHistory.accepted(
                stockCode, OrderSide.SELL, quantity, nextOrderNo());
        order.markFilled(quantity, fillPrice);
        orderHistoryRepository.save(order);

        // applySell 전에 기록 — 평단가는 매도 반영 전 값 (FillStateUpdater와 동일 순서, F-5)
        tradeResultTracker.recordSellFill(stockCode, quantity, fillPrice, pos.getAveragePrice());
        pos.applySell(quantity);
        if (pos.getQuantity() == 0) {
            positionRepository.delete(pos);
        } else {
            positionRepository.save(pos);
        }

        double cashIn = BacktestCosts.sellCashIn(fillPrice, quantity);
        positionManager.credit(cashIn);
        tradeRecorder.onSellFill(stockCode, quantity, cashIn, LocalDate.now(clock), exitContext);

        eventPublisher.publishEvent(
                new OrderFilledEvent(OrderSide.SELL, stockCode, quantity, fillPrice, false));
        log.debug("[BacktestOrder] 매도 체결: {} qty={} fill={} 사유={}",
                stockCode, quantity, (long) fillPrice, exitContext);
    }

    private double requireSimPrice(String stockCode) {
        double price = market.currentPrice(stockCode);
        if (price <= 0) {
            throw new IllegalStateException("시뮬 현재가 없음: " + stockCode);
        }
        return price;
    }

    private String nextOrderNo() {
        return "SIM-" + orderSeq.incrementAndGet();
    }
}
