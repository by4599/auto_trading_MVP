package com.trading.scheduler;

import com.trading.bucket.BucketParameterResolver;
import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarService;
import com.trading.order.OrderEngine;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 다일 보유 칸의 최대 보유일 청산 (2026-08-06).
 *
 * 며칠 들고 가는 전략은 무한정 물려 있으면 안 된다 — 백테스트로 검증된 조합은
 * <b>최대 20거래일</b>이라는 상한을 전제로 성적이 나왔다(BACKTEST-DESIGN §14 P3).
 * 지금까지 이 개념은 백테스트 엔진에만 있었고 모의투자에는 없었다.
 *
 * <p>타임컷(15:15) 직후인 15:17에 돈다 — 타임컷이 당일 청산 칸을 먼저 정리하고,
 * 남은 다일 보유분 중 기한이 찬 것만 이 스케줄러가 종가로 정리한다.
 *
 * <p><b>보유일은 거래일로 센다.</b> 달력일로 세면 연휴가 낀 포지션이 실제 거래일수보다
 * 일찍 잘린다. 진입일을 모르는 보유분(브로커 보정으로 생긴 행)은 관측한 날로 도장을 찍고
 * 그때부터 센다 — 모르는 과거를 추정해 앞당겨 파는 것보다 늦게 파는 쪽이 안전하다.
 *
 * <p>최대 보유일 기본값은 0(제한 없음)이므로, 칸 설정을 넣기 전에는 아무 일도 하지 않는다.
 */
@Component
@Profile("paper")
public class MaxHoldScheduler {

    private static final Logger log = LoggerFactory.getLogger(MaxHoldScheduler.class);

    private static final String STRATEGY_NAME = "MaxHoldDays";

    private final PositionRepository positionRepository;
    private final OrderHistoryRepository orderHistoryRepository;
    private final PositionManager positionManager;
    private final RiskEngine riskEngine;
    private final OrderEngine orderEngine;
    private final TradingStatusManager statusManager;
    private final KisProperties kisProperties;
    private final MarketCalendarService marketCalendar;
    private final BucketParameterResolver bucketParams;
    private final Clock clock;

    public MaxHoldScheduler(PositionRepository positionRepository,
                            OrderHistoryRepository orderHistoryRepository,
                            PositionManager positionManager,
                            RiskEngine riskEngine,
                            OrderEngine orderEngine,
                            TradingStatusManager statusManager,
                            KisProperties kisProperties,
                            MarketCalendarService marketCalendar,
                            BucketParameterResolver bucketParams,
                            Clock clock) {
        this.positionRepository = positionRepository;
        this.orderHistoryRepository = orderHistoryRepository;
        this.positionManager = positionManager;
        this.riskEngine = riskEngine;
        this.orderEngine = orderEngine;
        this.statusManager = statusManager;
        this.kisProperties = kisProperties;
        this.marketCalendar = marketCalendar;
        this.bucketParams = bucketParams;
        this.clock = clock;
    }

    /** 평일 15:17 KST — 타임컷(15:15) 이후, 장 마감(15:30) 이전 */
    @Scheduled(cron = "0 17 15 * * MON-FRI", zone = "Asia/Seoul")
    public void run() {
        enforceMaxHold();
    }

    void enforceMaxHold() {
        if (!kisProperties.isConfigured()) return;
        LocalDate today = LocalDate.now(clock);
        if (!marketCalendar.isTradingDay(today)) {
            log.info("[최대보유] 건너뜀 — 오늘은 KRX 휴장일");
            return;
        }
        TradingMode mode = statusManager.getCurrentMode();
        if (mode != TradingMode.RUNNING && mode != TradingMode.SAFE_MODE) {
            log.warn("[최대보유] 건너뜀 — 현재 mode={} (청산 상태머신이 포지션 소유)", mode);
            return;
        }

        List<Position> holdings = positionRepository.findAll().stream()
                .filter(p -> p.getQuantity() > 0)
                .filter(p -> bucketParams.maxHoldDays(p.getBucket()) > 0)
                .toList();
        if (holdings.isEmpty()) return;

        for (Position pos : holdings) {
            try {
                evaluate(pos, today);
            } catch (Exception e) {
                log.error("[최대보유] 처리 실패 — 계속 진행: stockCode={}", pos.getStockCode(), e);
            }
        }
    }

    private void evaluate(Position pos, LocalDate today) {
        if (pos.getEntryDate() == null) {
            pos.stampEntryDateIfAbsent(today);
            positionRepository.save(pos);
            log.info("[최대보유] {} 진입일 미상 — 오늘({})부터 보유일을 센다", pos.getStockCode(), today);
            return;
        }

        int limit = bucketParams.maxHoldDays(pos.getBucket());
        int held = tradingDaysBetween(pos.getEntryDate(), today);
        if (held < limit) return;

        log.info("[최대보유] {} 보유 {}거래일 ≥ 한도 {}일 — 종가 정리", pos.getStockCode(), held, limit);
        sellPosition(pos);
    }

    /** 진입일 다음 거래일부터 오늘까지의 거래일 수 (진입 당일은 0일차) */
    int tradingDaysBetween(LocalDate entry, LocalDate today) {
        int count = 0;
        for (LocalDate d = entry.plusDays(1); !d.isAfter(today); d = d.plusDays(1)) {
            if (marketCalendar.isTradingDay(d)) count++;
        }
        return count;
    }

    private void sellPosition(Position pos) {
        String stockCode = pos.getStockCode();
        if (hasPendingSell(stockCode)) {
            log.warn("[최대보유] 미체결 SELL 존재 — 중복 매도 방지: stockCode={}", stockCode);
            return;
        }
        Signal signal = Signal.sell(stockCode, STRATEGY_NAME);
        RiskResult result = riskEngine.check(signal, positionManager.snapshotAccount());
        if (!result.isPass()) {
            log.warn("[최대보유] RiskEngine 거부: stockCode={} 사유={}", stockCode, result.getReason());
            return;
        }
        orderEngine.execute(signal);
        log.info("[최대보유] 매도 접수 완료: stockCode={}", stockCode);
    }

    private boolean hasPendingSell(String stockCode) {
        return orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                        stockCode, OrderSide.SELL, OrderStatus.ACCEPTED)
                || orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                        stockCode, OrderSide.SELL, OrderStatus.PARTIAL_FILLED);
    }
}
