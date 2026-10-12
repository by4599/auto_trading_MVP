package com.trading.backtest;

import com.trading.strategy.AccumulationRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Accumulation Lab (§18) — 흔적레이더 "매집 흔적" 진입이 A동 돈치안 진입을 이기는지 판정한다(사용자 요청 연구).
 *
 * <p>고정: 출구 P3 · 사이징 0.25R·동시5 · 지수 추세 MA120 ON · 나머지 필터 OFF · 왕복 0.41% · 약세장 6.5년 창
 * (= §15.7 D0). 바뀌는 것은 <b>진입 방아쇠 한 축</b>뿐이고, 같은 실행의 첫 행에 돈치안 D0 기준선이 들어간다.
 *
 * <p>격자는 결과를 보기 전에 `_workspace/51_quant` §0.4에 사전 등록했다 — 여기서 바꾸지 않는다.
 * 판정 질문은 "10칸 중 가장 좋은 칸"이 아니라 <b>"가족 전체가 D0를 이기는가"</b>다.
 */
@Component
@Profile("backtest")
public class AccumulationLab {

    private static final Logger log = LoggerFactory.getLogger(AccumulationLab.class);

    /** 사전 등록 격자 한 칸 */
    record Cell(String name, AccumulationRule rule) {}

    static final String BASELINE_NAME = "D0 기준선 — 돈치안(고가20·추세120)";

    static final double SZ2_RISK_FRACTION = 0.0025;
    static final int    MAX_POSITIONS     = 5;
    static final int    INDEX_TREND_MA    = 120;
    static final int    DONCHIAN_LOOKBACK = 20;
    static final int    DONCHIAN_TREND_MA = 120;

    /** 사전 등록 10칸 (순서 고정) — A 3칸 · B 4칸 · C 1칸 · NT(추세 조건 없음) 2칸 */
    static List<Cell> grid() {
        return List.of(
                new Cell("A2 조용한급증 k2", AccumulationRule.quietSpike(2.0)),
                new Cell("A3 조용한급증 k3", AccumulationRule.quietSpike(3.0)),
                new Cell("A4 조용한급증 k4", AccumulationRule.quietSpike(4.0)),
                new Cell("B3·11 매집일3+고저폭11%", AccumulationRule.clusterBase(3, 0.11)),
                new Cell("B3·17 매집일3+고저폭17%", AccumulationRule.clusterBase(3, 0.17)),
                new Cell("B4·11 매집일4+고저폭11%", AccumulationRule.clusterBase(4, 0.11)),
                new Cell("B4·17 매집일4+고저폭17%", AccumulationRule.clusterBase(4, 0.17)),
                new Cell("C OBV다이버전스(참고)", AccumulationRule.obvDivergence()),
                new Cell("A3-NT 조용한급증 k3·추세조건없음", AccumulationRule.quietSpike(3.0).withoutTrend()),
                new Cell("B3·17-NT 매집일3+고저폭17%·추세조건없음",
                        AccumulationRule.clusterBase(3, 0.17).withoutTrend()));
    }

    private final LabExecutor executor;
    private final LabComparisonReportWriter reportWriter;
    private final ExecutionKnobs knobs;
    private final StrategyToggles toggles;

    public AccumulationLab(LabExecutor executor, LabComparisonReportWriter reportWriter,
                           ExecutionKnobs knobs, StrategyToggles toggles) {
        this.executor = executor;
        this.reportWriter = reportWriter;
        this.knobs = knobs;
        this.toggles = toggles;
    }

    /**
     * 사전 등록 §0.5-5 조건부 후속 — 1~4를 통과한 칸이 나온 A가족의 <b>중심 칸 A3</b>에 대해 거래량 평균 창
     * ±20%(16·24일). 첫 칸(평균창 20)은 본 실행 A3를 다시 재는 재현 확인용이다.
     */
    static List<Cell> sensitivityCells() {
        AccumulationRule center = AccumulationRule.quietSpike(3.0);
        return List.of(
                new Cell("A3 중심(평균창20 — 본 실행 재현)", center),
                new Cell("A3-AW16 평균창16(−20%)", center.withVolumeAvgWindow(16)),
                new Cell("A3-AW24 평균창24(+20%)", center.withVolumeAvgWindow(24)));
    }

    public void run(LabScope scope) {
        runCells(scope, grid(), scope);
    }

    public void runSensitivity(LabScope scope) {
        runCells(scope, sensitivityCells(), scope.withNames(scope.label() + " · A3 거래량 평균창 ±20%",
                scope.slug() + "-SENS"));
    }

    private void runCells(LabScope scope, List<Cell> cells, LabScope reportScope) {
        log.info("[AccumulationLab] ══ 매집 흔적(흔적레이더) 진입 vs 돈치안 D0 (§18, {}) ══", reportScope.slug());
        log.info("[AccumulationLab] 기간 {}~{} · 종목 {}개 · 윈도우 {}개 · 기준선 1 + 칸 {}개 · 0.25R·동시5 · "
                        + "지수MA{} ON · 진입 = 신호(t일 완성 봉) 다음 날 시가",
                scope.from(), scope.to(), scope.symbols().size(), LabExecutor.windowCount(scope),
                cells.size(), INDEX_TREND_MA);

        List<ExitLabRow> rows = new ArrayList<>();
        try {
            rows.add(runBaseline(scope));
            for (Cell cell : cells) {
                rows.add(runCell(cell, scope));
            }
        } finally {
            restoreDefaults();
        }

        LabLog.logProfileTable(log, "AccumulationLab", rows);
        String extra = AccumulationLabSummary.markdown(rows);
        extra.lines().forEach(line -> log.info("[AccumulationLab] {}", line));
        Path report = reportWriter.writeAccumulationLabReport(reportScope, rows);
        appendToReport(report, extra);
        log.info("[AccumulationLab] 판독: 첫 행 D0가 §15.7(1181·PF 1.92·MDD 4.20%)을 재현해야 유효. "
                + "그다음 최고 칸이 아니라 가족 전체(과반·중앙값)가 D0를 넘는지 본다");
        log.info("[AccumulationLab] 리포트: {}", report.toAbsolutePath());
    }

    /** 기준선 — 매집 흔적 스위치를 먼저 꺼서 기존 10:00 돌파 경로로 돈치안을 돌린다(§15.7 D0와 같은 조건) */
    private ExitLabRow runBaseline(LabScope scope) {
        toggles.accumulation().setEnabled(false);
        toggles.enableDonchianOnlyResetRsi();
        toggles.donchian().setLookback(DONCHIAN_LOOKBACK);
        toggles.donchian().setTrendMaPeriod(DONCHIAN_TREND_MA);
        return evaluate(BASELINE_NAME, scope);
    }

    private ExitLabRow runCell(Cell cell, LabScope scope) {
        toggles.enableAccumulationOnly(cell.rule());
        return evaluate(cell.name(), scope);
    }

    /** 고정 조건을 칸마다 다시 건다 — 이전 칸의 상태가 새지 않게(복원 누락 = 오염) */
    private ExitLabRow evaluate(String name, LabScope scope) {
        knobs.applyExitProfile(ExitProfile.P3);
        knobs.applySizing(SZ2_RISK_FRACTION, MAX_POSITIONS);
        knobs.applyIndexTrend(true, INDEX_TREND_MA);

        ExitLabRow row = executor.evaluate(name, scope);
        log.info("[AccumulationLab] {}: {} → {}", name,
                row.result().aggregateValidation().summaryLine(), ReportFormat.verdict(row.judgment()));
        return row;
    }

    private void restoreDefaults() {
        toggles.accumulation().resetDefaults();
        toggles.donchian().setLookback(DONCHIAN_LOOKBACK);
        toggles.donchian().setTrendMaPeriod(DONCHIAN_TREND_MA);
        knobs.restoreRegimeDefaults();
    }

    private static void appendToReport(Path report, String extra) {
        try {
            Files.writeString(report, "\n" + extra, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("흔적레이더 보조 지표를 리포트에 덧붙이지 못했다: " + report, e);
        }
    }
}
