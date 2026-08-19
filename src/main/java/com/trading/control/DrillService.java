package com.trading.control;

import com.trading.market.KisProperties;
import com.trading.order.OrderEngine;
import com.trading.position.Account;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.risk.LiquidationService;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskResult;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * 청산 리허설(Gate 2 훈련)의 조작 관문 구현.
 *
 * 리허설은 실제 주문을 낸다 — 그래서 판정을 한 곳에 모았다. 어느 진입점으로 들어와도
 * 같은 가드를 지난다: 모의(paper) 프로필 · KIS 자격증명 · RUNNING 모드 · 리스크 룰 전원 통과.
 * 리스크 룰이 막으면 사유를 그대로 돌려주고 강제로 뚫지 않는다.
 *
 * 흐름 규칙(Signal -> RiskEngine -> OrderEngine)은 그대로다. 우회하는 것은 R 사이징 하나뿐이며,
 * 그 이유는 리허설이 "정해진 시각에 정해진 수량"을 필요로 하기 때문이다.
 */
// TradingController(@Profile("!backtest"))가 의존하므로 같은 조건으로 등록하고,
// 모의계좌 한정은 런타임에 판정한다 (real 프로필에서 빈이 사라져 기동이 깨지는 것을 막는다).
@Service
@Profile("!backtest")
public class DrillService implements DrillOperations {

    private static final Logger log = LoggerFactory.getLogger(DrillService.class);

    /** 리허설 전용 고정값 — 범용 수동 매수 도구가 아니다 (수량·종목을 파라미터로 열지 않는다) */
    public static final String STOCK_CODE = "005930";
    public static final int    QUANTITY   = 10;
    private static final String SIGNAL_REASON = "MANUAL_DRILL";

    private final KisProperties        kisProperties;
    private final TradingStatusManager statusManager;
    private final RiskEngine           riskEngine;
    private final OrderEngine          orderEngine;
    private final PositionManager      positionManager;
    private final PositionRepository   positionRepository;
    private final LiquidationService   liquidationService;
    private final Environment          environment;

    public DrillService(KisProperties        kisProperties,
                        TradingStatusManager statusManager,
                        RiskEngine           riskEngine,
                        OrderEngine          orderEngine,
                        PositionManager      positionManager,
                        PositionRepository   positionRepository,
                        LiquidationService   liquidationService,
                        Environment          environment) {
        this.kisProperties      = kisProperties;
        this.statusManager      = statusManager;
        this.riskEngine         = riskEngine;
        this.orderEngine        = orderEngine;
        this.positionManager    = positionManager;
        this.positionRepository = positionRepository;
        this.liquidationService = liquidationService;
        this.environment        = environment;
    }

    @Override
    public Outcome manualBuy() {
        if (!isPaperProfile()) {
            return new Outcome(false, "모의투자(paper) 프로필에서만 사용할 수 있는 리허설 기능입니다");
        }
        if (!kisProperties.isConfigured()) {
            return new Outcome(false, "KIS 자격증명 미설정");
        }
        TradingMode mode = statusManager.getCurrentMode();
        if (mode != TradingMode.RUNNING) {
            return new Outcome(false, "현재 모드(" + mode
                    + ")에서는 신규 매수가 차단됩니다 — RUNNING 전환 후 다시 시도하세요");
        }

        Signal signal = Signal.buy(STOCK_CODE, SIGNAL_REASON);
        Account account = positionManager.snapshotAccount();
        RiskResult risk = riskEngine.check(signal, account);
        if (!risk.isPass()) {
            log.warn("[Drill] 수동 매수 차단 — {}", risk.getReason());
            return new Outcome(false, "리스크 룰이 매수를 막았습니다 — " + risk.getReason());
        }

        log.warn("[Drill] 수동 매수 개시 — {} {}주 시장가", STOCK_CODE, QUANTITY);
        orderEngine.executeManualBuy(signal, QUANTITY);
        return new Outcome(true, STOCK_CODE + " " + QUANTITY
                + "주 시장가 매수를 접수했습니다 — 체결은 3초 주기 체결확인이 반영합니다");
    }

    @Override
    public Outcome liquidate() {
        if (!kisProperties.isConfigured()) {
            return new Outcome(false, "KIS 자격증명 미설정");
        }
        log.warn("[Drill] 강제청산 리허설 개시 — 미체결 취소 후 보유 전량 시장가 매도");
        liquidationService.triggerForceLiquidation();
        return new Outcome(true,
                "강제청산 개시 — 진행 상황은 텔레그램/로그 확인, 종료 후 EMERGENCY_STOPPED 유지");
    }

    @Override
    public boolean hasAnyPosition() {
        return positionRepository.findAll().stream().anyMatch(p -> p.getQuantity() > 0);
    }

    private boolean isPaperProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains("paper");
    }
}
