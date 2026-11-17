package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Crash-Vol (BACKLOG 2026-07-24 탐색) — 지수 급락 직후 저변동성 종목 전방수익률 측정.
 *
 * <p>측정 전용(전략 채택 아님). 유니버스는 후보 54종목을 그대로 쓰되 기간은 전용
 * crash-vol-from/to를 쓴다 — §14.1 판정 창에는 진짜 하락장이 없어 코로나·금리쇼크를 못 본다.
 */
@Component
@Profile("backtest")
public class CrashVolLab {

    private static final Logger log = LoggerFactory.getLogger(CrashVolLab.class);

    private final LowVolCrashBacktester lowVolCrashBacktester;
    private final CrashVolReportWriter reportWriter;
    private final BacktestDataProperties properties;

    public CrashVolLab(LowVolCrashBacktester lowVolCrashBacktester,
                       CrashVolReportWriter reportWriter,
                       BacktestDataProperties properties) {
        this.lowVolCrashBacktester = lowVolCrashBacktester;
        this.reportWriter = reportWriter;
        this.properties = properties;
    }

    public void run(List<String> symbols, LocalDate from, LocalDate to) {
        long start = System.nanoTime();
        log.info("[CrashVol] 사용 창: crash-vol-from/to = {} ~ {} (candidate-from/to {} ~ {} 와 별개 — "
                        + "2020 코로나·2022 금리쇼크를 포함하려고 소급 확장한 전용 창)",
                from, to, properties.getCandidateFrom(), properties.getCandidateTo());
        log.info("[CrashVol] 소급 저장 하한(backfill-from): {} — 이보다 이른 캔들은 DB에 없다",
                properties.getBackfillFrom() != null ? properties.getBackfillFrom()
                        : "미설정(기본 now-years-워밍업)");
        LowVolCrashBacktester.CrashVolReport report =
                lowVolCrashBacktester.compute(symbols, from, to);
        Path file = reportWriter.writeLowVolCrashReport(report);

        log.info("[CrashVol] ══ 완료 ({}초) ══", ReportFormat.elapsedSec(start));
        for (LowVolCrashBacktester.WindowStats w : report.windows()) {
            log.info("[CrashVol] ── {} 기준 버킷팅 ({}) ──", w.window().code(), w.window().label());
            log.info("[CrashVol] 호라이즌 | 저변동성 중앙값 | 고변동성 중앙값 | 저−고 차 | 이벤트 n");
            for (LowVolCrashBacktester.HorizonStat h : w.horizons()) {
                log.info("[CrashVol]   D+{} | {} | {} | {} | {}건", h.horizon(),
                        ReportFormat.signedPct(h.lowVol()),
                        ReportFormat.signedPct(h.highVol()),
                        ReportFormat.lowMinusHighText(h), h.events());
            }
        }
        log.info("[CrashVol] 두 표가 다르면 물었던 질문의 답은 PRE 쪽 (DURING은 '이번 급락에서 덜 맞은 정도')");
        log.info("[CrashVol] 리포트: {}", file.toAbsolutePath());
    }
}
