package com.trading.scheduler;

import com.trading.market.Candle;
import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarService;
import com.trading.market.MarketDataService;
import com.trading.order.OrderEngine;
import com.trading.position.Account;
import com.trading.position.PositionManager;
import com.trading.risk.OpportunityCostLogger;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskResult;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import com.trading.signal.Signal;
import com.trading.universe.TradingUniverseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import com.trading.signal.SignalDispatcher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 1초마다 도는 메인 루프.
 * 시세조회 -> 전략평가(Signal) -> RiskEngine 검증 -> OrderEngine 주문 순서를 강제한다.
 *
 * running 플래그가 없으면, 한투 API 응답이 느려지는 순간 이전 루프가 안 끝났는데
 * 다음 스케줄이 겹쳐 돌면서 중복 주문이 나갈 수 있다. 반드시 필요한 가드다.
 *
 * 매매 대상은 trading_universe 테이블(TradingUniverseService)이 결정한다.
 * KIS 모의투자 레이트리밋(초당 2건, getRecentCandles = 2호출) 때문에
 * 틱당 1종목씩 라운드로빈으로 순회한다 — N종목이면 종목당 N초 간격 평가.
 */
@Component
@Profile("paper")
public class TradingScheduler {

    private static final Logger log = LoggerFactory.getLogger(TradingScheduler.class);

    private final MarketDataService marketDataService;
    private final SignalDispatcher signalDispatcher;
    private final RiskEngine riskEngine;
    private final OrderEngine orderEngine;
    private final PositionManager positionManager;
    private final TradingStatusManager statusManager;
    private final KisProperties kisProperties;
    private final TradingUniverseService universeService;
    private final MarketCalendarService marketCalendarService;
    private final OpportunityCostLogger opportunityCostLogger;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger roundRobinCursor = new AtomicInteger(0);

    public TradingScheduler(MarketDataService marketDataService,
                             SignalDispatcher signalDispatcher,
                             RiskEngine riskEngine,
                             OrderEngine orderEngine,
                             PositionManager positionManager,
                             TradingStatusManager statusManager,
                             KisProperties kisProperties,
                             TradingUniverseService universeService,
                             MarketCalendarService marketCalendarService,
                             OpportunityCostLogger opportunityCostLogger) {
        this.marketDataService = marketDataService;
        this.signalDispatcher = signalDispatcher;
        this.riskEngine = riskEngine;
        this.orderEngine = orderEngine;
        this.positionManager = positionManager;
        this.statusManager = statusManager;
        this.kisProperties = kisProperties;
        this.universeService = universeService;
        this.marketCalendarService = marketCalendarService;
        this.opportunityCostLogger = opportunityCostLogger;
    }

    @Scheduled(fixedDelay = 1000)
    public void run() {
        if (!kisProperties.isConfigured()) {
            return; // 자격증명 미설정 — 설정 페이지(localhost:8080)에서 입력 후 재시작
        }
        if (marketCalendarService.isHolidayToday()) {
            return; // KRX 휴장일 — 루프 자체를 돌리지 않는다 (OPERATIONS §5.1)
        }
        if (!marketCalendarService.isDuringMarketHoursNow()) {
            // 장 시간 밖에서는 시세 조회도 주문도 의미가 없다. 게이트가 없던 동안 새벽에도
            // 1초마다 돌며 KIS 유량(모의 1건/초)을 태우고 "모의투자 장시작전 입니다" 거부를
            // 양산했다 (2026-08-05~06 실측: 매수 요청 2,389건 중 2,336건이 이 사유).
            return;
        }
        if (statusManager.getCurrentMode() != TradingMode.RUNNING) {
            return; // FORCE_LIQUIDATING / EMERGENCY_STOPPED 상태에서 신규 매매 루프 진입 금지
        }
        if (!running.compareAndSet(false, true)) {
            return; // 이전 루프가 아직 실행 중이면 이번 틱은 건너뛴다
        }

        try {
            String stockCode = nextStock();
            if (stockCode == null) return; // 유니버스 비어 있음

            List<Candle> candles = marketDataService.getRecentCandles(stockCode);
            List<Signal> signals = signalDispatcher.dispatch(stockCode, candles);

            for (Signal signal : signals) {
                Account account = positionManager.snapshotAccount();
                RiskResult result = riskEngine.check(signal, account);

                if (result.isPass()) {
                    orderEngine.execute(signal);
                } else {
                    // 막힌 이유를 남긴다 (로그 + DB). 판정은 이미 끝난 뒤라 여기서 무슨 일이 나도
                    // 매매 결정은 바뀌지 않는다. 기록은 OpportunityCostLogger가 한 번 삼키지만,
                    // 그마저 새더라도 1초 루프가 멈추면 안 되므로 여기서 한 겹 더 받는다.
                    recordDropQuietly(signal, result.getReason());
                }
            }
        } finally {
            running.set(false);
        }
    }

    /** 기록은 부가 기능이다 — 어떤 예외도 매매 루프 밖으로 내보내지 않는다 */
    private void recordDropQuietly(Signal signal, String reason) {
        try {
            opportunityCostLogger.logDropped(signal, reason);
        } catch (Exception e) {
            log.warn("[TradingScheduler] 차단 이력 기록 실패 (매매 판정에는 영향 없음) — {}: {}",
                    signal.getStockCode(), e.toString());
        }
    }

    /** 틱당 1종목 라운드로빈 — 유니버스 크기가 바뀌어도 커서를 모듈로 연산으로 흡수 */
    private String nextStock() {
        List<String> codes = universeService.getActiveCodes();
        if (codes.isEmpty()) return null;
        int idx = Math.floorMod(roundRobinCursor.getAndIncrement(), codes.size());
        return codes.get(idx);
    }
}
