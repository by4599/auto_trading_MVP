package com.trading.backtest;

import com.trading.strategy.StrategyParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Walk-Forward 검증 (설계 문서 §3.1).
 *
 * 학습 6개월 / 검증 3개월, 3개월 롤링. K는 학습 구간 PF로만 선택하고
 * 성과는 검증 구간만 집계한다 — 학습 구간 성과는 채점 대상이 아니다.
 */
@Component
@Profile("backtest")
public class WalkForwardEngine {

    private static final Logger log = LoggerFactory.getLogger(WalkForwardEngine.class);

    static final int TRAIN_MONTHS    = 6;
    static final int VALIDATE_MONTHS = 3;
    static final int STEP_MONTHS     = 3;

    public record Window(LocalDate trainFrom, LocalDate trainTo,
                         LocalDate validateFrom, LocalDate validateTo) {}

    public record WindowResult(Window window, double chosenK,
                               BacktestMetrics trainMetrics, BacktestMetrics validateMetrics,
                               List<TradeRecorder.ClosedTrade> validateTrades) {}

    public record WalkForwardResult(List<WindowResult> windows,
                                    BacktestMetrics aggregateValidation) {

        /** 윈도우별 선택 K 목록 (안정성 판독용) */
        public List<Double> chosenKs() {
            return windows.stream().map(WindowResult::chosenK).toList();
        }
    }

    private final BacktestRunner runner;
    private final StrategyParameters strategyParameters;
    private final BacktestDataProperties properties;

    public WalkForwardEngine(BacktestRunner runner,
                             StrategyParameters strategyParameters,
                             BacktestDataProperties properties) {
        this.runner = runner;
        this.strategyParameters = strategyParameters;
        this.properties = properties;
    }

    /** [from, to]를 6/3/3 윈도우로 분할한다. 검증 구간이 to를 넘으면 버린다. */
    static List<Window> windows(LocalDate from, LocalDate to) {
        List<Window> result = new ArrayList<>();
        LocalDate cursor = from;
        while (true) {
            LocalDate trainTo      = cursor.plusMonths(TRAIN_MONTHS).minusDays(1);
            LocalDate validateFrom = cursor.plusMonths(TRAIN_MONTHS);
            LocalDate validateTo   = validateFrom.plusMonths(VALIDATE_MONTHS).minusDays(1);
            if (validateTo.isAfter(to)) break;
            result.add(new Window(cursor, trainTo, validateFrom, validateTo));
            cursor = cursor.plusMonths(STEP_MONTHS);
        }
        return result;
    }

    /**
     * K 후보를 학습 구간에서 선택 → 검증 구간 재생. kOverride가 주어지면
     * 학습을 생략하고 그 K를 쓴다 (필터 A/B에서 기준선과 동일 K 재사용).
     */
    public WalkForwardResult run(List<String> symbols, LocalDate from, LocalDate to,
                                 List<Double> kCandidates, List<Double> kOverridePerWindow) {
        List<Window> windows = windows(from, to);
        List<WindowResult> results = new ArrayList<>();

        for (int i = 0; i < windows.size(); i++) {
            long windowStart = System.nanoTime();
            Window w = windows.get(i);
            double chosenK;
            BacktestMetrics trainMetrics = null;

            if (kOverridePerWindow != null) {
                chosenK = kOverridePerWindow.get(i);
            } else {
                chosenK = kCandidates.get(0);
                double bestPf = -Double.MAX_VALUE;
                for (double k : kCandidates) {
                    strategyParameters.setK(k);
                    BacktestRunner.RunResult train = runner.run(new BacktestRunner.RunConfig(
                            String.format("WF%02d-train-K%.1f", i, k),
                            symbols, w.trainFrom(), w.trainTo()));
                    double pf = comparablePf(train.metrics());
                    if (pf > bestPf) {
                        bestPf = pf;
                        chosenK = k;
                        trainMetrics = train.metrics();
                    }
                }
            }

            strategyParameters.setK(chosenK);
            BacktestRunner.RunResult validate = runner.run(new BacktestRunner.RunConfig(
                    String.format("WF%02d-validate-K%.1f", i, chosenK),
                    symbols, w.validateFrom(), w.validateTo()));

            results.add(new WindowResult(w, chosenK, trainMetrics, validate.metrics(),
                    validate.trades()));
            // 진행률·경과 시간 — 장시간 스윕에서 "멈춘 건지 도는 건지" 구분용 (로그 전용)
            log.info("[WalkForward] 윈도우 {}/{} 완료 ({}초): K={} 검증 {}",
                    i + 1, windows.size(),
                    String.format("%.1f", (System.nanoTime() - windowStart) / 1_000_000_000.0),
                    chosenK, validate.metrics().summaryLine());
        }

        strategyParameters.setK(0.5); // 기본값 복원
        return new WalkForwardResult(results, aggregate(results));
    }

    /** PF 비교값 — 무한대는 유한 상한으로 캡, 소표본(5건 미만)은 큰 페널티 (순서는 유지) */
    private static double comparablePf(BacktestMetrics m) {
        double pf = Double.isInfinite(m.profitFactor()) ? 100.0 : m.profitFactor();
        return m.tradeCount() < 5 ? pf - 1_000 : pf;
    }

    /** 검증 구간 트레이드 합산 + 윈도우 순 체인 자산 곡선으로 집계 지표 산출 */
    private BacktestMetrics aggregate(List<WindowResult> results) {
        List<TradeRecorder.ClosedTrade> allTrades = new ArrayList<>();
        results.forEach(r -> allTrades.addAll(r.validateTrades()));

        // 각 윈도우 검증 수익률을 곱연결한 가상 자산 곡선 (윈도우마다 초기 자본 리셋되므로)
        double initial = properties.getInitialCash();
        List<Double> chained = new ArrayList<>();
        double base = initial;
        for (WindowResult r : results) {
            double windowReturn = r.validateMetrics().totalReturnPct();
            base = base * (1 + windowReturn);
            chained.add(base);
        }
        return BacktestMetrics.of(allTrades, chained, initial);
    }
}
