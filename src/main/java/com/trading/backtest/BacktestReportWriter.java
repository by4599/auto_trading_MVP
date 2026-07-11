package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * B-3 결과 리포트 + 거버넌스 기준선 파일 (설계 문서 §4).
 *
 * 리포트: logs/backtest/REPORT-{일시}.md (gitignore 대상 — 실행 산출물)
 * 기준선: 합격 시 docs/BACKTEST-BASELINE.yml (버전 관리 — 거버넌스 강등 판정의 분모)
 */
@Component
@Profile("backtest")
public class BacktestReportWriter {

    private static final Logger log = LoggerFactory.getLogger(BacktestReportWriter.class);

    /** 합격 기준 (설계 문서 §4) */
    static final int    MIN_TRADES        = 100;
    static final double MIN_PF            = 1.3;
    static final double MAX_MDD           = 0.15;
    static final double MIN_ADJACENT_PF   = 1.15;

    public record FilterVariant(String name, WalkForwardEngine.WalkForwardResult result,
                                boolean adopted, String verdictNote) {}

    public record ReportData(List<String> symbols, LocalDate from, LocalDate to,
                             Map<Double, BacktestMetrics> kSensitivity,
                             WalkForwardEngine.WalkForwardResult baseline,
                             List<FilterVariant> filterVariants) {}

    public record Judgment(boolean pass, List<String> reasons) {}

    /** §4 합격 판정 — 검증 구간 집계 + 전기간 K 민감도 */
    public Judgment judge(ReportData data) {
        BacktestMetrics v = data.baseline().aggregateValidation();
        List<String> reasons = new java.util.ArrayList<>();

        if (v.tradeCount() < MIN_TRADES) {
            reasons.add(String.format("검증 트레이드 %d건 < %d (소표본)", v.tradeCount(), MIN_TRADES));
        }
        if (v.profitFactor() < MIN_PF) {
            reasons.add(String.format("검증 PF %.2f < %.1f", v.profitFactor(), MIN_PF));
        }
        if (v.expectancyPct() <= 0) {
            reasons.add(String.format("검증 기대값 %.3f%% ≤ 0", v.expectancyPct() * 100));
        }
        if (v.maxDrawdown() > MAX_MDD) {
            reasons.add(String.format("검증 MDD %.1f%% > %.0f%%", v.maxDrawdown() * 100, MAX_MDD * 100));
        }
        data.kSensitivity().forEach((k, m) -> {
            if (m.profitFactor() < MIN_ADJACENT_PF) {
                reasons.add(String.format("K=%.1f 전기간 PF %.2f < %.2f (민감도)", k,
                        m.profitFactor(), MIN_ADJACENT_PF));
            }
        });
        return new Judgment(reasons.isEmpty(), reasons);
    }

    public Path writeReport(ReportData data, Judgment judgment) {
        StringBuilder md = new StringBuilder();
        md.append("# 백테스트 리포트 (B-3) — 변동성 돌파 소급 검증\n\n");
        md.append(String.format("- 실행: %s%n- 기간: %s ~ %s%n- 종목: %s%n- 초기 자본: 10,000,000원%n",
                LocalDateTime.now(), data.from(), data.to(), String.join(", ", data.symbols())));
        md.append("- 비용 모델: 슬리피지 0.1%×2 + 수수료 0.015%×2 + 매도 제세 0.18% (왕복 ≈0.41%)\n\n");

        md.append("## 판정: ").append(judgment.pass() ? "✅ 합격" : "❌ 불합격").append("\n\n");
        judgment.reasons().forEach(r -> md.append("- ").append(r).append('\n'));
        md.append('\n');

        md.append("## 1. K 민감도 (전기간, 참고용 — 채점은 Walk-Forward 검증 구간)\n\n");
        md.append("| K | 트레이드 | 승률 | PF | 기대값 | MDD | 수익률 |\n|---|---|---|---|---|---|---|\n");
        data.kSensitivity().forEach((k, m) -> md.append(metricsRow(String.format("%.1f", k), m)));
        md.append('\n');

        md.append("## 2. Walk-Forward (학습 6개월 / 검증 3개월 / 3개월 롤링)\n\n");
        md.append("검증 구간 집계: **").append(data.baseline().aggregateValidation().summaryLine()).append("**\n\n");
        md.append("| 윈도우 | 학습 | 검증 | 선택 K | 검증 PF | 검증 MDD | 검증 트레이드 |\n|---|---|---|---|---|---|---|\n");
        List<WalkForwardEngine.WindowResult> ws = data.baseline().windows();
        for (int i = 0; i < ws.size(); i++) {
            WalkForwardEngine.WindowResult w = ws.get(i);
            md.append(String.format("| %d | %s~%s | %s~%s | %.1f | %s | %.1f%% | %d |%n",
                    i, w.window().trainFrom(), w.window().trainTo(),
                    w.window().validateFrom(), w.window().validateTo(), w.chosenK(),
                    pfText(w.validateMetrics().profitFactor()),
                    w.validateMetrics().maxDrawdown() * 100,
                    w.validateMetrics().tradeCount()));
        }
        md.append('\n');

        md.append("## 3. 필터 A/B (검증 구간 — PF·MDD 동반 우위일 때만 채택)\n\n");
        md.append("| 변형 | 트레이드 | PF | MDD | 판정 |\n|---|---|---|---|---|\n");
        BacktestMetrics base = data.baseline().aggregateValidation();
        md.append(String.format("| (순정) | %d | %s | %.1f%% | 기준선 |%n",
                base.tradeCount(), pfText(base.profitFactor()), base.maxDrawdown() * 100));
        for (FilterVariant fv : data.filterVariants()) {
            BacktestMetrics m = fv.result().aggregateValidation();
            md.append(String.format("| %s | %d | %s | %.1f%% | %s |%n",
                    fv.name(), m.tradeCount(), pfText(m.profitFactor()),
                    m.maxDrawdown() * 100, fv.verdictNote()));
        }
        md.append("\n※ 진입 시간창·거래량 필터는 일봉으로 돌파 시각을 알 수 없어 검증 유보 —\n");
        md.append("   분봉 축적(MinuteCandleCollector, 매일 15:40) 후 별도 세션에서 A/B한다.\n\n");

        md.append("## 4. 방법 한계 (일봉 근사 v1 — 보수 방향)\n\n");
        md.append("- 진입 체크포인트 10:00 고정 (돌파 시각 불명 — MarketCloseRule 등 시간 룰 근사)\n");
        md.append("- 진입가 = 이분탐색 복원 돌파가 + 슬리피지 0.1% (분봉 다음 봉 시가 대신)\n");
        md.append("- 당일 진입 후 저가 ≤ 손절가면 손절 체결로 간주 (저가가 진입 전이었을 가능성 무시 — 비관)\n");
        md.append("- 타임컷 15:15 체결가 = 당일 종가 근사\n");
        md.append("- 생존 편향: 현 유니버스(대형주)는 영향 작음 — 유니버스 확대 시 재평가 (§2.2)\n");

        try {
            Path dir = Path.of("logs", "backtest");
            Files.createDirectories(dir);
            Path file = dir.resolve("REPORT-" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
            Files.writeString(file, md.toString());
            log.info("[Report] 리포트 저장: {}", file.toAbsolutePath());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("리포트 저장 실패", e);
        }
    }

    /** 합격 시에만 호출 — 거버넌스가 소비하는 기준선 (버전 관리 대상) */
    public Path writeBaseline(ReportData data, List<FilterVariant> adoptedFilters) {
        BacktestMetrics v = data.baseline().aggregateValidation();
        StringBuilder yml = new StringBuilder();
        yml.append("# 백테스트 기준선 (B-3) — 거버넌스 강등 판정의 분모 (PERFORMANCE-GOVERNANCE §2)\n");
        yml.append("# 자동 생성: BacktestReportWriter — 수동 편집 금지\n");
        yml.append(String.format("generated-at: %s%n", LocalDate.now()));
        yml.append(String.format("period: %s ~ %s%n", data.from(), data.to()));
        yml.append("method: daily-bar-approximation-v1\n");
        yml.append("validation:   # Walk-Forward 검증 구간 집계 (비용 차감 후)\n");
        yml.append(String.format("  trades: %d%n", v.tradeCount()));
        yml.append(String.format("  profit-factor: %.3f%n", v.profitFactor()));
        yml.append(String.format("  expectancy-pct: %.4f%n", v.expectancyPct()));
        yml.append(String.format("  max-drawdown: %.4f%n", v.maxDrawdown()));
        yml.append(String.format("  win-rate: %.4f%n", v.winRate()));
        yml.append(String.format("chosen-k-per-window: %s%n", data.baseline().chosenKs()));
        yml.append(String.format("adopted-filters: %s%n",
                adoptedFilters.stream().map(FilterVariant::name).toList()));

        try {
            Path file = Path.of("docs", "BACKTEST-BASELINE.yml");
            Files.writeString(file, yml.toString());
            log.info("[Report] 기준선 저장: {}", file.toAbsolutePath());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("기준선 저장 실패", e);
        }
    }

    private static String metricsRow(String label, BacktestMetrics m) {
        return String.format("| %s | %d | %.1f%% | %s | %.3f%% | %.1f%% | %.1f%% |%n",
                label, m.tradeCount(), m.winRate() * 100, pfText(m.profitFactor()),
                m.expectancyPct() * 100, m.maxDrawdown() * 100, m.totalReturnPct() * 100);
    }

    private static String pfText(double pf) {
        return Double.isInfinite(pf) ? "inf" : String.format("%.2f", pf);
    }
}
