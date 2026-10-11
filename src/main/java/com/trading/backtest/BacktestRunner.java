package com.trading.backtest;

import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.position.ShadowPortfolio;
import com.trading.risk.RiskLimitsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    static final String EXIT_MAX_HOLD   = "MaxHoldDays";

    public record RunConfig(String label, List<String> symbols, LocalDate from, LocalDate to) {}

    public record RunResult(String label, LocalDate from, LocalDate to,
                            List<TradeRecorder.ClosedTrade> trades, BacktestMetrics metrics) {}

    private final BacktestMarketDataService market;
    private final DailyBarSimulator simulator;
    private final DailyBarExitSimulator exitSimulator;
    private final BacktestOrderClient orderClient;
    private final BacktestPositionManager positionManager;
    private final PositionRepository positionRepository;
    private final ShadowPortfolio shadowPortfolio;
    private final BacktestStateReset stateReset;
    private final TradeRecorder tradeRecorder;
    private final BacktestDataProperties properties;
    private final MutableClock clock;
    private final RiskLimitsProperties limits;
    private final ExitLabProperties exitLab;

    public BacktestRunner(BacktestMarketDataService market,
                          DailyBarSimulator simulator,
                          DailyBarExitSimulator exitSimulator,
                          BacktestOrderClient orderClient,
                          BacktestPositionManager positionManager,
                          PositionRepository positionRepository,
                          ShadowPortfolio shadowPortfolio,
                          BacktestStateReset stateReset,
                          TradeRecorder tradeRecorder,
                          BacktestDataProperties properties,
                          MutableClock clock,
                          RiskLimitsProperties limits,
                          ExitLabProperties exitLab) {
        this.market = market;
        this.simulator = simulator;
        this.exitSimulator = exitSimulator;
        this.orderClient = orderClient;
        this.positionManager = positionManager;
        this.positionRepository = positionRepository;
        this.shadowPortfolio = shadowPortfolio;
        this.stateReset = stateReset;
        this.tradeRecorder = tradeRecorder;
        this.properties = properties;
        this.clock = clock;
        this.limits = limits;
        this.exitLab = exitLab;
    }

    public RunResult run(RunConfig config) {
        stateReset.reset();
        market.loadSeries(config.symbols(), config.from(), config.to());

        List<LocalDate> dates = market.tradingDates(config.from(), config.to());
        log.info("[Backtest] 런 시작: {} — {}~{} 거래일 {}일, 종목 {}개 (타임컷={}, 최대보유={}일)",
                config.label(), config.from(), config.to(), dates.size(), config.symbols().size(),
                exitLab.isTimecutEnabled() ? "ON" : "OFF",
                exitLab.getMaxHoldDays() > 0 ? exitLab.getMaxHoldDays() : "무제한");

        // 종목별 진입 거래일 인덱스 — 다일 보유 시 최대 보유일 판정에 쓴다 (런마다 새로 시작).
        Map<String, Integer> entryDayIndex = new HashMap<>();

        for (int i = 0; i < dates.size(); i++) {
            LocalDate date = dates.get(i);
            market.clearSimPrices();
            market.setSimDate(date);
            clock.setTo(date, LocalTime.of(9, 5));

            for (String code : config.symbols()) {
                simulator.simulateDay(code, date);
            }

            // 새로 보유하게 된 포지션의 진입 인덱스 기록 (당일 진입·당일 청산은 기록 안 됨)
            for (Position pos : heldPositions()) {
                entryDayIndex.putIfAbsent(pos.getStockCode(), i);
            }

            // 최대 보유일 초과분 종가 강제청산 (무한 보유 방지·타임 손절)
            if (exitLab.getMaxHoldDays() > 0) {
                enforceMaxHold(date, i, entryDayIndex);
            }

            // 15:15 타임컷 — timecutEnabled면 당일 전량 청산, 아니면 다음 거래일로 이월
            if (exitLab.isTimecutEnabled()) {
                executeTimeCut(date);
            }

            closeDay(date); // 일마감 — 여기서 -5%/MDD 강제청산이 포지션을 정리할 수 있다

            // 청산된 종목(타임컷·강제청산 포함)의 진입 인덱스 정리 — closeDay 뒤에 둬야
            // 강제청산분까지 걷힌다 (재진입 시 옛 인덱스가 남아 보유일이 오산되지 않도록).
            entryDayIndex.keySet().retainAll(heldCodes());
        }

        BacktestMetrics metrics = BacktestMetrics.of(
                tradeRecorder.getTrades(), tradeRecorder.getDailyEquity(),
                properties.getInitialCash());
        log.info("[Backtest] 런 종료: {} — {}", config.label(), metrics.summaryLine());
        return new RunResult(config.label(), config.from(), config.to(),
                tradeRecorder.getTrades(), metrics);
    }

    /** 최대 보유일 초과분 종가 강제청산 — 진입 후 maxHoldDays 거래일 경과 시 (다일 보유 타임 손절) */
    private void enforceMaxHold(LocalDate date, int todayIndex, Map<String, Integer> entryDayIndex) {
        clock.setTo(date, LocalTime.of(15, 15));
        for (Position pos : heldPositions()) {
            Integer entryIndex = entryDayIndex.get(pos.getStockCode());
            if (entryIndex == null) continue;
            if (todayIndex - entryIndex < exitLab.getMaxHoldDays()) continue;
            Double close = closeOf(pos.getStockCode(), date);
            if (close == null) continue; // 당일 봉 없음(거래정지) — 이월
            exitSimulator.exitAt(pos.getStockCode(), close, EXIT_MAX_HOLD);
        }
    }

    /** 15:15 타임컷 — 잔여 보유분 전량 종가 매도 (TimeCutScheduler의 근사) */
    private void executeTimeCut(LocalDate date) {
        clock.setTo(date, LocalTime.of(15, 15));
        for (Position pos : heldPositions()) {
            Double close = closeOf(pos.getStockCode(), date);
            if (close == null) continue; // 당일 봉 없음(거래정지) — 이월
            exitSimulator.exitAt(pos.getStockCode(), close, EXIT_TIMECUT);
        }
    }

    /** 일마감 — 자산 곡선 기록, peakEquity 갱신, -5%/MDD 강제청산 모델 (RiskMonitor 근사) */
    private void closeDay(LocalDate date) {
        clock.setTo(date, LocalTime.of(15, 30));

        var account = positionManager.snapshotAccount();
        shadowPortfolio.tick();

        boolean dailyLossBreach = account.getDailyPnlPercent() <= limits.getDailyLossLiquidate();
        double peak = shadowPortfolio.getPeakEquity();
        boolean mddBreach = peak > 0
                && (peak - account.getTotalAssetValue()) / peak > limits.getMddLimit();

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

    private Set<String> heldCodes() {
        return heldPositions().stream()
                .map(Position::getStockCode)
                .collect(Collectors.toSet());
    }

    private Double closeOf(String stockCode, LocalDate date) {
        var bar = market.dayBar(stockCode, date);
        return bar == null ? null : bar.getClose();
    }
}
