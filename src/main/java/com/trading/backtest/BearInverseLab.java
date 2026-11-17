package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.function.Predicate;

/**
 * Bear Inverse Lab (63_quant, 2026-11-17) — 하락장(전일 KOSPI 종가 &lt; MA)에는 쉬는 대신 인버스 ETF를 든다.
 *
 * <p>A동 D0′(55_quant 사후 변경과 같은 구성)을 엔진으로 돌려 검증 창별 하루 마감 기록(현금·자산)을 받고, 그 위에
 * {@link InverseOverlay}로 인버스 보유를 얹는다. 엔진 경로·리스크 룰은 바꾸지 않는다 — 지수 필터 MA만 칸마다 M으로 맞춘다.
 */
@Component
@Profile("backtest")
public class BearInverseLab {

    private static final Logger log = LoggerFactory.getLogger(BearInverseLab.class);

    static final List<Double> WEIGHTS = List.of(0.30, 0.50, 1.00);

    /** M 하나 · 진입일 가정 하나의 결과 */
    record Section(int ma, String assumption, ExitLabRow engine, InverseOverlayStats.Cell alone,
                   List<InverseOverlayStats.Cell> composite, List<InverseOverlayStats.Cell> inverseOnly,
                   List<InverseOverlayStats.Subset[]> inverseSubsets) {}

    private final StopWidthLab stopWidthLab;
    private final LabExecutor executor;
    private final ExecutionKnobs knobs;
    private final ScaleInRunStats stats;
    private final BearRsGate gate;
    private final InverseData data;
    private final BacktestDataProperties properties;

    public BearInverseLab(StopWidthLab stopWidthLab, LabExecutor executor, ExecutionKnobs knobs, ScaleInRunStats stats,
                          BearRsGate gate, InverseData data, BacktestDataProperties properties) {
        this.stopWidthLab = stopWidthLab;
        this.executor = executor;
        this.knobs = knobs;
        this.stats = stats;
        this.gate = gate;
        this.data = data;
        this.properties = properties;
    }

    /** 사전 등록 12칸 (순서 고정) */
    static List<InverseOverlay.Config> grid() {
        List<InverseOverlay.Config> grid = new ArrayList<>();
        for (double w : WEIGHTS) {
            for (InverseOverlay.Exit e : InverseOverlay.Exit.values()) grid.add(InverseOverlay.Config.of(w, e));
        }
        return grid;
    }

    /**
     * @param overlay      false면 A동 단독만(앵커 재현)
     * @param costOverride 0보다 크면 왕복 비용(A동·인버스 같이)
     * @param synthetic    true면 KOSPI 합성 −1배, false면 114800 실제 일봉
     */
    public List<Section> run(LabScope scope, List<Integer> mas, boolean overlay, double costOverride, boolean synthetic) {
        Map<LocalDate, InverseOverlay.Bar> bars = overlay ? (synthetic ? data.syntheticBars() : data.realBars()) : Map.of();
        if (overlay && bars.isEmpty()) {
            throw new IllegalStateException("인버스 일봉이 없다(" + (synthetic ? "합성" : "114800") + ") — bear-inverse-fetch 먼저");
        }
        NavigableMap<LocalDate, Double> closes = data.kospiCloses();
        log.info("[BearInv] ══ {} · M {} · 오버레이 {} · 비용 {} · 인버스 {} {}일 ══", scope.slug(), mas, overlay,
                costOverride > 0 ? costOverride : "기본", synthetic ? "합성" : "실제", bars.size());
        List<Section> sections = new ArrayList<>();
        try {
            for (int ma : mas) {
                for (String code : List.of("F15-S", "F15-X")) {
                    sections.add(section(scope, ma, ScaleInLabRouter.findStopProfile(code), costOverride, overlay, bars, closes));
                }
            }
        } finally {
            gate.disable();
            stopWidthLab.restoreDefaults();
            stopWidthLab.useSizing(StopWidthLab.SZ2_RISK_FRACTION, false);
        }
        return sections;
    }

    private Section section(LabScope scope, int ma, StopWidthProfile exit, double cost, boolean overlay,
                            Map<LocalDate, InverseOverlay.Bar> bars, NavigableMap<LocalDate, Double> closes) {
        stopWidthLab.useSizing(BearRsLab.RISK_FRACTION, true, BearRsLab.MIN_ONE_SHARE_CAP);
        stopWidthLab.apply(exit);                 // D0′ — 지수 MA120 포함
        knobs.applyIndexTrend(true, ma);          // A동 지수 필터 = 인버스 신호와 같은 M
        if (cost > 0) knobs.setRoundTripCost(cost);
        gate.disable();
        stats.clear();
        long start = System.nanoTime();
        AppliedCost applied = knobs.appliedCost();
        String label = String.format("D0′ · 지수 MA%d · %s", ma, exit.code());
        ExitLabRow row = executor.evaluate(label, scope, applied);
        List<List<ScaleInRunStats.DayEnd>> tapes = new ArrayList<>(stats.tapes().values());
        List<List<TradeRecorder.ClosedTrade>> baseTrades = row.result().windows().stream()
                .map(WalkForwardEngine.WindowResult::validateTrades).toList();
        double initial = properties.getInitialCash();
        InverseOverlayStats.Cell alone = InverseOverlayStats.aggregate("A동 단독", baseTrades, noOverlay(tapes), initial);
        verifyTape(row, alone, tapes);
        log.info("[BearInv] MA{}-{} 엔진 ({}초): {} → {}", ma, exit.code(), ReportFormat.elapsedSec(start),
                row.result().aggregateValidation().summaryLine(), ReportFormat.verdict(row.judgment()));
        if (!overlay) return new Section(ma, exit.code(), row, alone, List.of(), List.of(), List.of());

        Map<LocalDate, Boolean> memo = new java.util.HashMap<>();
        Predicate<LocalDate> bear = d -> memo.computeIfAbsent(d,
                x -> BacktestIndexRegimeSource.belowTrend(closes, x, ma).orElse(false));
        List<List<TradeRecorder.ClosedTrade>> none = baseTrades.stream().map(t -> List.<TradeRecorder.ClosedTrade>of()).toList();
        List<InverseOverlayStats.Cell> composite = new ArrayList<>();
        List<InverseOverlayStats.Cell> inverseOnly = new ArrayList<>();
        List<InverseOverlayStats.Subset[]> subsets = new ArrayList<>();
        for (InverseOverlay.Config cfg : grid()) {
            List<InverseOverlay.WindowOutcome> comp = tapes.stream()
                    .map(t -> InverseOverlay.simulate(t, bars, bear, cfg, applied.slippageRate(), initial)).toList();
            composite.add(InverseOverlayStats.aggregate(cfg.code(), baseTrades, comp, initial));
            List<InverseOverlay.WindowOutcome> solo = tapes.stream()
                    .map(t -> InverseOverlay.simulate(InverseOverlayStats.flatTape(t, initial), bars, bear, cfg,
                            applied.slippageRate(), initial)).toList();
            inverseOnly.add(InverseOverlayStats.aggregate(cfg.code(), none, solo, initial));
            subsets.add(new InverseOverlayStats.Subset[]{
                    InverseOverlayStats.subset(solo, d -> true),
                    InverseOverlayStats.subset(solo, InverseOverlayStats.year(2022)),
                    InverseOverlayStats.subset(solo, InverseOverlayStats.month(InverseOverlayStats.AUG_2024))});
        }
        return new Section(ma, exit.code(), row, alone, composite, inverseOnly, subsets);
    }

    private static List<InverseOverlay.WindowOutcome> noOverlay(List<List<ScaleInRunStats.DayEnd>> tapes) {
        return tapes.stream().map(t -> new InverseOverlay.WindowOutcome(
                t.stream().map(ScaleInRunStats.DayEnd::equity).toList(), List.of())).toList();
    }

    /** 하루 마감 기록으로 다시 묶은 A동 단독이 엔진 집계와 같아야 한다 — 아니면 기록이 어긋난 것 */
    private static void verifyTape(ExitLabRow row, InverseOverlayStats.Cell alone, List<List<ScaleInRunStats.DayEnd>> tapes) {
        BacktestMetrics engine = row.result().aggregateValidation();
        int windows = row.result().windows().size();
        boolean ok = tapes.size() == windows
                && alone.metrics().tradeCount() == engine.tradeCount()
                && Math.abs(alone.metrics().finalEquity() - engine.finalEquity()) < 1e-6 * engine.finalEquity()
                && Math.abs(alone.metrics().maxDrawdown() - engine.maxDrawdown()) < 1e-9;
        if (!ok) {
            throw new IllegalStateException(String.format("하루 마감 기록 불일치: 창 %d/%d · 건수 %d/%d · 최종 %.0f/%.0f",
                    tapes.size(), windows, alone.metrics().tradeCount(), engine.tradeCount(),
                    alone.metrics().finalEquity(), engine.finalEquity()));
        }
    }

    Path report(LabScope scope, String tag, List<Section> sections, boolean synthetic) {
        return BearInverseReport.write(scope, tag, sections, synthetic);
    }
}
