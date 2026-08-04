package com.trading.risk;

import com.trading.market.AtrCalculator;
import com.trading.market.MarketDataService;
import com.trading.order.OrderFilledEvent;
import com.trading.order.OrderPartialFilledEvent;
import com.trading.order.OrderSide;
import com.trading.position.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.OptionalDouble;

/**
 * 매수 전량 체결 시 ATR 손절선을 Position에 장착한다 (방법론 §4.1).
 *
 *   손절가 = 실제 체결가 − ATR(14) × 1.5
 *
 * 신호 시점가가 아닌 체결가 기준으로 계산한다 — 슬리피지로 진입가가 밀리면
 * 신호 시점 계산은 이미 틀린 값이기 때문 (방법론 §4.3 "체결가 기준 재계산").
 * 장착 실패는 치명 오류로 로그만 남긴다 — 손절선 없는 포지션은 타임컷(15:15)이
 * 최후 방어선으로 당일 정리한다.
 */
@Component
@Profile({"paper", "backtest"})   // 백테스트 동기 체결(BacktestOrderClient)의 커밋 후에도 동일 경로로 손절 장착
public class StopLossArmer {

    private static final Logger log = LoggerFactory.getLogger(StopLossArmer.class);

    private final MarketDataService marketDataService;
    private final AtrCalculator atrCalculator;
    private final PositionRepository positionRepository;
    private final RiskLimitsProperties limits;

    public StopLossArmer(MarketDataService marketDataService,
                         AtrCalculator atrCalculator,
                         PositionRepository positionRepository,
                         RiskLimitsProperties limits) {
        this.marketDataService = marketDataService;
        this.atrCalculator = atrCalculator;
        this.positionRepository = positionRepository;
        this.limits = limits;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderFilled(OrderFilledEvent event) {
        if (event.side() != OrderSide.BUY) return;
        arm(event.stockCode(), event.avgPrice());
    }

    /**
     * 부분 체결 보유분에도 손절선을 장착한다 (알려진 결함 #5 해소).
     * 추가 체결마다 갱신된 누적 평균 체결가로 재장착되고,
     * 전량 체결 시 onOrderFilled()가 최종가로 한 번 더 덮어쓴다 — 항등적으로 안전.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderPartialFilled(OrderPartialFilledEvent event) {
        if (event.side() != OrderSide.BUY) return;
        arm(event.stockCode(), event.avgPrice());
    }

    /**
     * @param fillPrice 손절선 기준가. 체결 경로에서는 실제 체결가, 브로커 기준 보정 경로에서는
     *                  브로커가 알려준 평균단가를 넣는다.
     */
    public void arm(String stockCode, double fillPrice) {
        try {
            OptionalDouble atrOpt = atrCalculator.atr(
                    marketDataService.getDailyCandles(stockCode, AtrCalculator.PERIOD + 1));
            if (atrOpt.isEmpty()) {
                log.error("[StopLoss] ATR 산출 불가 — 손절선 미장착 (타임컷이 최후 방어선): {}", stockCode);
                return;
            }

            double stopPrice = fillPrice - atrOpt.getAsDouble() * limits.getAtrStopMultiplier();
            positionRepository.findByStockCode(stockCode).ifPresentOrElse(pos -> {
                pos.armStopLoss(stopPrice);
                positionRepository.save(pos);
                log.info("[StopLoss] 손절선 장착: {} 체결가={} 손절가={} (ATR={})",
                        stockCode, String.format("%.0f", fillPrice),
                        String.format("%.0f", stopPrice),
                        String.format("%.0f", atrOpt.getAsDouble()));
            }, () -> log.error("[StopLoss] Position 없음 — 손절선 미장착: {}", stockCode));
        } catch (Exception e) {
            log.error("[StopLoss] 손절선 장착 실패 — 수동 확인 필요: {}", stockCode, e);
        }
    }
}
