package com.trading.scheduler;

import com.trading.market.Candle;
import com.trading.market.KisProperties;
import com.trading.market.MarketDataService;
import com.trading.order.OrderEngine;
import com.trading.position.Account;
import com.trading.position.PositionManager;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskResult;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import com.trading.signal.Signal;
import com.trading.universe.TradingUniverseService;
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

    private final MarketDataService marketDataService;
    private final SignalDispatcher signalDispatcher;
    private final RiskEngine riskEngine;
    private final OrderEngine orderEngine;
    private final PositionManager positionManager;
    private final TradingStatusManager statusManager;
    private final KisProperties kisProperties;
    private final TradingUniverseService universeService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger roundRobinCursor = new AtomicInteger(0);

    public TradingScheduler(MarketDataService marketDataService,
                             SignalDispatcher signalDispatcher,
                             RiskEngine riskEngine,
                             OrderEngine orderEngine,
                             PositionManager positionManager,
                             TradingStatusManager statusManager,
                             KisProperties kisProperties,
                             TradingUniverseService universeService) {
        this.marketDataService = marketDataService;
        this.signalDispatcher = signalDispatcher;
        this.riskEngine = riskEngine;
        this.orderEngine = orderEngine;
        this.positionManager = positionManager;
        this.statusManager = statusManager;
        this.kisProperties = kisProperties;
        this.universeService = universeService;
    }

    @Scheduled(fixedDelay = 1000)
    public void run() {
        if (!kisProperties.isConfigured()) {
            return; // 자격증명 미설정 — 설정 페이지(localhost:8080)에서 입력 후 재시작
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
                }
                // TODO: result.isPass()==false면 거부 사유를 signal_history에 기록
            }
        } finally {
            running.set(false);
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
