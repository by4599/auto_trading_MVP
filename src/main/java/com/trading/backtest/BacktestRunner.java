package com.trading.backtest;

import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.position.ShadowPortfolio;
import com.trading.risk.RiskLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 봉 루프 러너 (B-2) — TradingScheduler의 백테스트 대응물.
 *
 * 거래일마다: 종목별 DailyBarSimulator(손절→진입→당일손절) → 15:15 타임컷
 * → 일마감(자산 곡선 기록, peakEquity 갱신, -5%/MDD 강제청산 모델).
 * 파이프라인은 라이브와 동일한 Strategy → Signal → RiskEngine → OrderEngine을 지난다.
 */
@Component
@Profile("backtest")
public class BacktestRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);

    static final String EXIT_TIMECUT    = "TimeCut-1515";
    static final String EXIT_FORCED_LIQ = "ForcedLiquidation";

    public record RunConfig(String label, List<String> symbols, LocalDate from, LocalDate to) {}

    public record RunResult(String label, LocalDate from, LocalDate to,
                            List<TradeRecorder.ClosedTrade> trades, BacktestMetrics metrics) {}

    private final BacktestMarketDataService market;
    private final DailyBarSimulator simulator;
    private final BacktestOrderClient orderClient;
    private final BacktestPositionManager positionManager;
    private final PositionRepository positionRepository;
    private final ShadowPortfolio shadowPortfolio;
    private final BacktestStateReset stateReset;
    private final TradeRecorder tradeRecorder;
    private final BacktestDataProperties properties;
    private final MutableClock clock;

    public BacktestRunner(BacktestMarketDataService market,
                          DailyBarSimulator simulator,
                          BacktestOrderClient orderClient,
                          BacktestPositionManager positionManager,
                          PositionRepository positionRepository,
                          ShadowPortfolio shadowPortfolio,
                          BacktestStateReset stateReset,
                          TradeRecorder tradeRecorder,
                          BacktestDataProperties properties,
                          MutableClock clock) {
        this.market = market;
        this.simulator = simulator;
        this.orderClient = orderClient;
        this.positionManager = positionManager;
        this.positionRepository = positionRepository;
        this.shadowPortfolio = shadowPortfolio;
        this.stateReset = stateReset;
        this.tradeRecorder = tradeRecorder;
        this.properties = properties;
        this.clock = clock;
    }

    public RunResult run(RunConfig config) {
        stateReset.reset();
        market.loadSeries(config.symbols(), config.from(), config.to());

        List<LocalDate> dates = market.tradingDates(config.from(), config.to());
        log.info("[Backtest] 런 시작: {} — {}~{} 거래일 {}일, 종목 {}개",
                config.label(), config.from(), config.to(), dates.size(), config.symbols().size());

        for (LocalDate date : dates) {
            market.clearSimPrices();
            market.setSimDate(date);
            clock.setTo(date, LocalTime.of(9, 5));

            for (String code : config.symbols()) {
                simulator.simulateDay(code, date);
            }
            executeTimeCut(date);
            closeDay(date);
        }

        BacktestMetrics metrics = BacktestMetrics.of(
                tradeRecorder.getTrades(), tradeRecorder.getDailyEquity(),
                properties.getInitialCash());
        log.info("[Backtest] 런 종료: {} — {}", config.label(), metrics.summaryLine());
        return new RunResult(config.label(), config.from(), config.to(),
                tradeRecorder.getTrades(), metrics);
    }

    /** 15:15 타임컷 — 잔여 보유분 전량 종가 매도 (TimeCutScheduler의 근사) */
    private void executeTimeCut(LocalDate date) {
        clock.setTo(date, LocalTime.of(15, 15));
        for (Position pos : heldPositions()) {
            Double close = closeOf(pos.getStockCode(), date);
            if (close == null) continue; // 당일 봉 없음(거래정지) — 이월
            simulator.exitAt(pos.getStockCode(), close, EXIT_TIMECUT);
        }
    }

    /** 일마감 — 자산 곡선 기록, peakEquity 갱신, -5%/MDD 강제청산 모델 (RiskMonitor 근사) */
    private void closeDay(LocalDate date) {
        clock.setTo(date, LocalTime.of(15, 30));

        var account = positionManager.snapshotAccount();
        shadowPortfolio.tick();

        boolean dailyLossBreach = account.getDailyPnlPercent() <= RiskLimits.DAILY_LOSS_LIQUIDATE;
        double peak = shadowPortfolio.getPeakEquity();
        boolean mddBreach = peak > 0
                && (peak - account.getTotalAssetValue()) / peak > RiskLimits.MDD_LIMIT;

        if ((dailyLossBreach || mddBreach) && !heldPositions().isEmpty()) {
            log.warn("[Backtest] {} 강제청산 모델 발동 (dailyLoss={} mdd={})",
                    date, dailyLossBreach, mddBreach);
            for (Position pos : heldPositions()) {
                Double close = closeOf(pos.getStockCode(), date);
                if (close == null) continue;
                // 강제청산은 라이브에서도 RiskEngine을 거치지 않는다 (LiquidationService 경로)
                market.setSimPrice(pos.getStockCode(), close);
                orderClient.setExitContext(EXIT_FORCED_LIQ);
                orderClient.sell(pos.getStockCode(), pos.getQuantity());
            }
        }

        tradeRecorder.recordDayEnd(positionManager.snapshotAccount().getTotalAssetValue());
    }

    private List<Position> heldPositions() {
        return positionRepository.findAll().stream()
                .filter(p -> p.getQuantity() > 0)
                .toList();
    }

    private Double closeOf(String stockCode, LocalDate date) {
        var bar = market.dayBar(stockCode, date);
        return bar == null ? null : bar.getClose();
    }
}
