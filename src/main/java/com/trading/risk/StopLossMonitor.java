package com.trading.risk;

import com.trading.bucket.StrategyBucket;
import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarService;
import com.trading.order.OrderEngine;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.signal.Signal;
import com.trading.strategy.ScalpingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ATR 손절 감시 (방법론 §4.1) — 1초마다 보유 포지션의 현재가를 손절선과 비교한다.
 *
 * 손절은 평시 매도이므로 청산 경로(LiquidationService)가 아닌
 * Signal → RiskEngine → OrderEngine 경로를 쓴다 (ADR 규칙 5).
 * 현재가는 KisPositionManager의 잔고 스냅샷(3초 캐시)을 재사용하므로 추가 API 호출이 없다.
 *
 * [데이터 품질 게이트] 잔고 API 실패로 낡은(폴백) 스냅샷이면 판정을 건너뛴다 —
 * 옛 가격으로 손절/익절/트레일링을 헛발동(특히 옛 고가로 익절 매도)하는 것을 막는다.
 * (RiskMonitor의 낡은-스냅샷 스킵과 동일 원칙. 매도=money-moving이라 신선값일 때만 판정.)
 *
 * [장 시간 게이트] 장 밖(거래일 09:00~마감 외·휴장일)에는 판정하지 않는다 (감사 H-1(c), 2026-10-01).
 * 다일 보유분이 15:30을 넘기면 장외에도 신선한 스냅샷이 생기는데, 종가가 손절선·트레일선 아래면
 * 매 초 시장가 매도 → KIS 거부 → FAILED → 다음 초 재시도가 다음 날 아침까지 반복된다.
 * 건너뛸 뿐 잊지 않는다 — 손절선·최고점은 Position(DB)에 남아 다음 개장 첫 틱에 그대로 판정된다.
 */
@Component
@Profile("paper")
public class StopLossMonitor {

    private static final Logger log = LoggerFactory.getLogger(StopLossMonitor.class);

    private static final String STRATEGY_NAME = "StopLoss-ATR";
    private static final String TRAILING_NAME = "TrailingStop";
    private static final String TAKE_PROFIT_NAME = "TakeProfit-Scalping";

    private final PositionRepository positionRepository;
    private final OrderHistoryRepository orderHistoryRepository;
    private final PositionManager positionManager;
    private final RiskEngine riskEngine;
    private final OrderEngine orderEngine;
    private final TradingStatusManager statusManager;
    private final KisProperties kisProperties;
    private final TrailingStopTracker trailingStopTracker;
    private final ScalpingProperties scalpingProperties;
    private final MarketCalendarService marketCalendar;

    public StopLossMonitor(PositionRepository positionRepository,
                           OrderHistoryRepository orderHistoryRepository,
                           PositionManager positionManager,
                           RiskEngine riskEngine,
                           OrderEngine orderEngine,
                           TradingStatusManager statusManager,
                           KisProperties kisProperties,
                           TrailingStopTracker trailingStopTracker,
                           ScalpingProperties scalpingProperties,
                           MarketCalendarService marketCalendar) {
        this.positionRepository = positionRepository;
        this.orderHistoryRepository = orderHistoryRepository;
        this.positionManager = positionManager;
        this.riskEngine = riskEngine;
        this.orderEngine = orderEngine;
        this.statusManager = statusManager;
        this.kisProperties = kisProperties;
        this.trailingStopTracker = trailingStopTracker;
        this.scalpingProperties = scalpingProperties;
        this.marketCalendar = marketCalendar;
    }

    @Scheduled(fixedDelay = 1000)
    public void monitor() {
        checkStops();
    }

    void checkStops() {
        if (!kisProperties.isConfigured()) return;
        // 장 시간 외에는 매도 판정을 하지 않는다 (RiskMonitor와 같은 판정) — 장외 매도 거부 폭주 차단
        if (!marketCalendar.isDuringMarketHoursNow()) return;
        TradingMode mode = statusManager.getCurrentMode();
        if (mode != TradingMode.RUNNING && mode != TradingMode.SAFE_MODE) return;

        Account account;
        try {
            account = positionManager.snapshotAccount();
        } catch (Exception e) {
            log.warn("[StopLoss] 계좌 스냅샷 실패 — 이번 틱 건너뜀: {}", e.getMessage());
            return;
        }

        // 낡은(폴백) 스냅샷이면 옛 가격 헛매도 방지를 위해 판정을 건너뛴다 (데이터 품질 게이트)
        if (!account.isFresh()) {
            log.debug("[StopLoss] 계좌 스냅샷이 낡음(잔고 API 폴백) — 이번 틱 판정 건너뜀");
            return;
        }

        for (Account.PositionSnapshot snapshot : account.getPositions()) {
            try {
                checkOne(snapshot, account);
            } catch (Exception e) {
                // 한 종목의 오류가 나머지 감시를 멈추지 않는다
                log.error("[StopLoss] 감시 오류 — 계속 진행: {}", snapshot.stockCode(), e);
            }
        }
    }

    private void checkOne(Account.PositionSnapshot snapshot, Account account) {
        Position pos = positionRepository.findByStockCode(snapshot.stockCode()).orElse(null);
        if (pos == null || pos.getQuantity() <= 0) return;

        double current = snapshot.currentPrice();
        if (current <= 0) return;                       // 시세 불명 — 판정하지 않는다

        // 스캘핑(방식3, MIX 버킷) 목표 익절 — 다른 버킷 포지션은 건드리지 않는다
        if (scalpingProperties.isEnabled() && pos.getBucket() == StrategyBucket.MIX) {
            double target = pos.getAveragePrice() * (1 + scalpingProperties.getTakeProfitPct());
            if (current >= target) {
                sellVia(TAKE_PROFIT_NAME, snapshot.stockCode(), account, current, pos);
                return;
            }
        }

        // 트레일링 스톱 필터 (§3.3, 기본 OFF) — ATR 손절과 별개의 수익 보존 훅.
        // 고점은 Position에 영속화한 값이 정본이다(재시작해도 유지, 2026-10-01) — 트래커는 판정만 한다.
        trailingStopTracker.syncHigh(snapshot.stockCode(), trailingHighAfter(pos, current));
        if (trailingStopTracker.exitPrice(
                snapshot.stockCode(), current, pos.getAveragePrice(), pos.getBucket()).isPresent()) {
            sellVia(TRAILING_NAME, snapshot.stockCode(), account, current, pos);
            return;
        }

        if (pos.getStopPrice() == null || current > pos.getStopPrice()) return;
        sellVia(STRATEGY_NAME, snapshot.stockCode(), account, current, pos);
    }

    /**
     * 진입 이후 최고가를 올려 저장하고 그 값을 돌려준다. 저장된 값이 없으면(새 진입·브로커 보정 행·
     * 도입 전 보유분) 지금 관측가에서 시작한다 — 모르는 과거 고점을 추정해 트레일을 당겨 파는 것보다
     * 늦게 파는 쪽이 안전하다(MaxHoldScheduler의 진입일 미상 처리와 같은 원칙). 오를 때만 쓴다.
     */
    private double trailingHighAfter(Position pos, double current) {
        if (pos.raiseTrailingHigh(current)) {
            persistTrailingHigh(pos);
        }
        return pos.getTrailingHigh();
    }

    /**
     * 저장이 실패해도(@Version 충돌·DB 잠금) 이 틱의 판정은 메모리 값으로 계속한다 (감사 M-3).
     * 여기서 던지면 그 종목은 ATR 손절(1차 방어선) 판정 없이 catch로 빠진다. 저장은 재시작 대비일 뿐이고,
     * 다음 틱이 DB 값을 다시 읽어 또 올리므로 저절로 재시도된다.
     */
    private void persistTrailingHigh(Position pos) {
        try {
            positionRepository.save(pos);
        } catch (RuntimeException e) {
            log.warn("[StopLoss] 최고점 저장 실패 — 이번 틱은 메모리 값 {}로 판정 계속: {} {}",
                    String.format("%.0f", pos.getTrailingHigh()), pos.getStockCode(), e.getMessage());
        }
    }

    private void sellVia(String reason, String stockCode, Account account,
                         double current, Position pos) {
        if (hasPendingSell(stockCode)) return; // 중복 매도 방지

        Signal signal = Signal.sell(stockCode, reason);
        RiskResult result = riskEngine.check(signal, account);
        if (!result.isPass()) {
            log.warn("[StopLoss] RiskEngine 거부: {} 사유={}", stockCode, result.getReason());
            return;
        }

        log.warn("[StopLoss] {} 트리거: {} 현재가={} 손절가={}",
                reason, stockCode, String.format("%.0f", current),
                pos.getStopPrice() == null ? "-" : String.format("%.0f", pos.getStopPrice()));
        orderEngine.execute(signal);
        trailingStopTracker.clear(stockCode);
    }

    private boolean hasPendingSell(String stockCode) {
        return orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                        stockCode, OrderSide.SELL, OrderStatus.ACCEPTED)
                || orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                        stockCode, OrderSide.SELL, OrderStatus.PARTIAL_FILLED);
    }
}
