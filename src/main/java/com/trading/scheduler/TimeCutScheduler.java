package com.trading.scheduler;

import com.trading.NotificationService;
import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarService;
import com.trading.order.OrderEngine;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.risk.BrokerageApiClient;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskResult;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 15:15 타임컷 (F-4) — 변동성 돌파는 당일 청산이 전제인 단타 전략이므로
 * 장 마감 전 보유 포지션을 전량 시장가 매도로 정리한다.
 *
 * ADR-001 2.3: 평시 매도이므로 청산 경로(LiquidationService)가 아닌
 * Signal → RiskEngine → OrderEngine 경로를 사용한다.
 * FORCE_LIQUIDATING/EMERGENCY_STOPPED 상태에서는 양보한다 (포지션 소유권은 청산 상태머신).
 *
 * 매도 수량은 OrderEngine이 보유 전량으로 결정한다 (P2-A R 사이징 이후 수량 > 1 가능).
 *
 * 재시도 스윕(15:20·15:24·15:28): 2026-09-01 15:16:34에 066570 5주 매도가
 * Read timeout으로 끊겼고, 종목별 catch가 로그만 남기고 넘어가 그 종목만 정리되지 않은 채
 * 밤을 넘겼다 — 당일 청산 전제가 방어 없이 깨진 것이다. 스윕은 15:15 뒤에도 남아 있는
 * 대상을 마감(15:30) 전에 다시 판다. 스케줄 스레드는 단일이라 인라인 대기(sleep)로
 * 재시도하면 RiskMonitor·StopLossMonitor 감시가 함께 멈추므로 뒤따르는 스케줄로 분리했다.
 * 타임아웃은 "주문이 안 갔다"는 뜻이 아니므로 재매도 전에 브로커 실잔고를 확인하되,
 * 조회가 실패하면 재매도한다 (재시도 과다는 거부로 끝나지만 누락은 무방비 오버나잇이다).
 */
@Component
@Profile("paper")
public class TimeCutScheduler {

    private static final Logger log = LoggerFactory.getLogger(TimeCutScheduler.class);

    private static final String STRATEGY_NAME = "TimeCut-1515";

    private final PositionRepository positionRepository;
    private final OrderHistoryRepository orderHistoryRepository;
    private final PositionManager positionManager;
    private final RiskEngine riskEngine;
    private final OrderEngine orderEngine;
    private final TradingStatusManager statusManager;
    private final KisProperties kisProperties;
    private final MarketCalendarService marketCalendarService;
    private final com.trading.bucket.BucketParameterResolver bucketParams;
    private final BrokerageApiClient brokerageClient;
    private final NotificationService notifier;

    public TimeCutScheduler(PositionRepository positionRepository,
                            OrderHistoryRepository orderHistoryRepository,
                            PositionManager positionManager,
                            RiskEngine riskEngine,
                            OrderEngine orderEngine,
                            TradingStatusManager statusManager,
                            KisProperties kisProperties,
                            MarketCalendarService marketCalendarService,
                            com.trading.bucket.BucketParameterResolver bucketParams,
                            BrokerageApiClient brokerageClient,
                            NotificationService notifier) {
        this.positionRepository = positionRepository;
        this.orderHistoryRepository = orderHistoryRepository;
        this.positionManager = positionManager;
        this.riskEngine = riskEngine;
        this.orderEngine = orderEngine;
        this.statusManager = statusManager;
        this.kisProperties = kisProperties;
        this.marketCalendarService = marketCalendarService;
        this.bucketParams = bucketParams;
        this.brokerageClient = brokerageClient;
        this.notifier = notifier;
    }

    /** 평일 15:15 KST 1회 실행. 앱이 그 시각에 꺼져 있었으면 해당일 타임컷은 건너뛴다 (운영 문서화). */
    // 이 시각은 PostTimeCutBuyRule.TIME_CUT_AT과 짝 — 한쪽만 바꾸면 15:15~15:20 신규 매수 구멍이 되살아난다
    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Seoul")
    public void run() {
        executeTimeCut();
    }

    void executeTimeCut() {
        if (!canRun("타임컷")) {
            return;
        }

        List<Position> all = heldPositions();
        List<Position> holdings = timeCutTargets(all);
        int kept = all.size() - holdings.size();
        if (kept > 0) {
            log.info("[타임컷] 다일 보유 칸 {}종목은 제외 — 이월한다", kept);
        }
        if (holdings.isEmpty()) {
            log.info("[타임컷] 정리 대상 없음");
            return;
        }

        log.info("[타임컷] 15:15 보유분 정리 개시 — {}종목", holdings.size());
        for (Position pos : holdings) {
            try {
                sellPosition(pos);
            } catch (Exception e) {
                // 한 종목의 실패가 나머지 정리를 멈추지 않는다 (종목별 예외 격리 — ADR 2.3)
                log.error("[타임컷] 매도 실패 — 계속 진행: stockCode={}", pos.getStockCode(), e);
            }
        }
    }

    /** 사전 가드 — 타임컷과 스윕이 같은 조건에서만 움직이도록 한 곳에 둔다 */
    private boolean canRun(String tag) {
        if (!kisProperties.isConfigured()) {
            return false;
        }
        if (marketCalendarService.isHolidayToday()) {
            // cron은 MON-FRI까지만 알고 KRX 특정 휴장일(설날 등)은 모른다 — 방어 가드
            log.info("[{}] 건너뜀 — 오늘은 KRX 휴장일", tag);
            return false;
        }
        TradingMode mode = statusManager.getCurrentMode();
        if (mode != TradingMode.RUNNING && mode != TradingMode.SAFE_MODE) {
            log.warn("[{}] 건너뜀 — 현재 mode={} (청산 상태머신이 포지션 소유)", tag, mode);
            return false;
        }
        return true;
    }

    private List<Position> heldPositions() {
        return positionRepository.findAll().stream()
                .filter(p -> p.getQuantity() > 0)
                .toList();
    }

    /**
     * 다일 보유 칸(A동)은 타임컷 대상이 아니다 — 며칠 들고 가는 것이 그 전략의 본체다.
     * 기본값은 false라 설정을 넣기 전까지 전 보유분이 종전대로 정리된다.
     * 타임컷과 스윕은 반드시 같은 집합을 봐야 한다 (스윕이 이월분을 팔아버리면 안 된다).
     */
    private List<Position> timeCutTargets(List<Position> held) {
        return held.stream()
                .filter(p -> !bucketParams.multiDayHold(p.getBucket()))
                .toList();
    }

    private void sellPosition(Position pos) {
        String stockCode = pos.getStockCode();

        if (hasPendingSell(stockCode)) {
            log.warn("[타임컷] 미체결 SELL 존재 — 중복 매도 방지: stockCode={}", stockCode);
            return;
        }

        Signal signal = Signal.sell(stockCode, STRATEGY_NAME);
        RiskResult result = riskEngine.check(signal, positionManager.snapshotAccount());
        if (!result.isPass()) {
            log.warn("[타임컷] RiskEngine 거부: stockCode={} 사유={}", stockCode, result.getReason());
            return;
        }
        orderEngine.execute(signal);
        log.info("[타임컷] 매도 접수 완료: stockCode={}", stockCode);
    }

    /** 마감(15:30) 전 재시도 — 접수가 늦어도 체결 여유가 남게 두 번 나눠 돈다 */
    @Scheduled(cron = "0 20,24 15 * * MON-FRI", zone = "Asia/Seoul")
    public void retrySweep() {
        executeRetrySweep(false);
    }

    @Scheduled(cron = "0 28 15 * * MON-FRI", zone = "Asia/Seoul")
    public void finalRetrySweep() {
        executeRetrySweep(true);
    }

    void executeRetrySweep(boolean finalAttempt) {
        String tag = finalAttempt ? "타임컷 최종스윕" : "타임컷 스윕";
        if (!canRun(tag)) {
            return;
        }

        List<Position> targets = timeCutTargets(heldPositions());
        if (targets.isEmpty()) {
            return;   // 정상 상황이 대부분 — 로그를 남기지 않는다
        }

        log.warn("[{}] 15:15에 정리되지 않은 {}종목 발견 — 재매도 시도", tag, targets.size());
        for (Position pos : targets) {
            try {
                retrySell(tag, pos);
            } catch (Exception e) {
                // 한 종목의 실패가 나머지 정리를 멈추지 않는다 (종목별 예외 격리 — ADR 2.3)
                log.error("[{}] 재매도 실패 — 계속 진행: stockCode={}", tag, pos.getStockCode(), e);
            }
        }

        if (finalAttempt) {
            alertIfStillHeld(tag);
        }
    }

    private void retrySell(String tag, Position pos) {
        String stockCode = pos.getStockCode();
        if (hasPendingSell(stockCode)) {
            log.info("[{}] 미체결 SELL 대기 중 — 재매도 보류: stockCode={}", tag, stockCode);
            return;
        }
        if (brokerConfirmedFlat(tag, stockCode)) {
            return;
        }
        sellPosition(pos);
    }

    /**
     * 신선하게 확인된 0일 때만 true. 조회가 실패하면 false로 답해 재매도로 보낸다 —
     * 타임아웃이 곧 미접수는 아니어서(09-01 15:23 대사에서 브로커 보유는 0이었다)
     * 확인 없는 재전송은 중복 매도가 되지만, 확인 실패를 정리 완료로 오인하면 무방비로 밤을 넘긴다.
     */
    private boolean brokerConfirmedFlat(String tag, String stockCode) {
        try {
            if (brokerageClient.getActualHoldingQuantity(stockCode) == 0) {
                log.warn("[{}] 브로커 보유 0 — 앞선 매도가 체결된 것으로 보고 재매도 보류: stockCode={}",
                        tag, stockCode);
                return true;
            }
        } catch (Exception e) {
            log.warn("[{}] 브로커 잔고 확인 실패 — 확실하지 않으므로 재매도 시도: stockCode={} ({})",
                    tag, stockCode, e.toString());
        }
        return false;
    }

    private void alertIfStillHeld(String tag) {
        List<Position> remaining = timeCutTargets(heldPositions());
        if (remaining.isEmpty()) {
            return;
        }
        String detail = remaining.stream()
                .map(p -> p.getStockCode() + " " + p.getQuantity() + "주")
                .collect(Collectors.joining(", "));
        log.error("[{}] 마감 전 정리 실패 — 사람 확인 필요: {}", tag, detail);
        notifier.sendCritical(
                "[타임컷 실패] 오늘 팔지 못한 주식이 남았습니다: " + detail + "."
                + " 곧 장이 닫혀서 이대로면 이 주식을 밤새 들고 갑니다."
                + " 자동 손절선은 그대로 살아 있어 내일 장이 열리면 다시 지켜줍니다."
                + " 확인 후 직접 팔지 판단해 주세요.");
    }

    private boolean hasPendingSell(String stockCode) {
        return orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                        stockCode, OrderSide.SELL, OrderStatus.ACCEPTED)
                || orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                        stockCode, OrderSide.SELL, OrderStatus.PARTIAL_FILLED);
    }
}
