package com.trading.backtest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기준선 기록 관문 — 거버넌스 파일(`docs/BACKTEST-BASELINE-*.yml`)이 바뀌는 **단일 지점**을 지킨다.
 *
 * <p>배경: 커버리지 관문을 랩 라우터에만 걸어 뒀더니 `FullBacktestLab`·`SingleStrategyLab`이
 * 그 옆으로 빠져나가 검사도, `write-baseline` opt-in도 없이 기준선을 덮어쓸 수 있었다
 * (risk-auditor 2026-08-17 MEDIUM 1). 호출부마다 검사를 흩는 대신 기록 지점에서 막는다.
 *
 * <p>Java 25 인라인 목 제약 — 구체 클래스를 목킹하지 않고 익명 하위 클래스로 값을 주입한다.
 */
class BaselineWriteGateTest {

    private static final LocalDate FROM = LocalDate.of(2023, 7, 22);
    private static final LocalDate TO   = LocalDate.of(2026, 7, 21);
    /** 실제 거버넌스 파일을 건드리지 않도록 전용 슬러그를 쓴다 */
    private static final String TEST_SLUG = "TEST-GATE";
    private static final List<String> SYMBOLS = List.of("005930", "000660");

    private Path written;

    @AfterEach
    void cleanUp() throws IOException {
        if (written != null) Files.deleteIfExists(written);
    }

    /** 채점 대상(= reportData)과 같은 스코프를 검사한 결과 */
    private static CandleCoverage coverage(LocalDate coveredThrough, List<LocalDate> missing) {
        return coverage(new CoverageScope("risk-lab", SYMBOLS, TO), coveredThrough, missing);
    }

    private static CandleCoverage coverage(CoverageScope scope, LocalDate coveredThrough,
                                           List<LocalDate> missing) {
        return new CandleCoverage(scope, coveredThrough, missing, 14);
    }

    /** lastReport만 갈아끼운 검사기 — 생성자 인자는 이 경로에서 쓰이지 않는다 */
    private static CandleCoverageChecker checkerWith(CandleCoverage last) {
        return new CandleCoverageChecker(null, null) {
            @Override
            public Optional<CandleCoverage> lastReport() {
                return Optional.ofNullable(last);
            }
        };
    }

    private static BacktestReportWriter writerWith(boolean optIn, CandleCoverage last) {
        BacktestDataProperties properties = new BacktestDataProperties();
        properties.setWriteBaseline(optIn);
        return new BacktestReportWriter(properties, checkerWith(last));
    }

    private static BacktestReportWriter.ReportData reportData() {
        BacktestMetrics metrics =
                new BacktestMetrics(781, 0.5186, 1.959, 0.0130, 0.0987, 1.2428, 22_427_654, 2.0, 9.9);
        return new BacktestReportWriter.ReportData(
                SYMBOLS, FROM, TO, Map.of(),
                new WalkForwardEngine.WalkForwardResult(List.of(), metrics), List.of(),
                "관문 테스트", TEST_SLUG);
    }

    @Test
    @DisplayName("write-baseline opt-in이 없으면 기록하지 않는다 — full·ma-breakout의 옛 자동 기록 경로 차단")
    void refuses_whenNotOptedIn() {
        written = writerWith(false, coverage(TO, List.of())).writeBaseline(reportData(), List.of());

        assertThat(written).isNull();
    }

    @Test
    @DisplayName("커버리지 검사를 거치지 않은 실행은 기록하지 않는다 — 관문 우회 경로 차단")
    void refuses_whenCoverageNeverVerified() {
        written = writerWith(true, null).writeBaseline(reportData(), List.of());

        assertThat(written).isNull();
    }

    @Test
    @DisplayName("커버리지 미달이면 기록하지 않는다 — 창 끝이 빈 채로 굳던 §14.1 드리프트 차단")
    void refuses_whenCoverageInsufficient() {
        CandleCoverage gap = coverage(LocalDate.of(2026, 7, 16),
                List.of(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 21)));

        written = writerWith(true, gap).writeBaseline(reportData(), List.of());

        assertThat(written).isNull();
    }

    // ── 스코프 대조 (risk-auditor 2026-08-17: lastReport는 실행당 하나뿐인 가변 필드) ──

    @Test
    @DisplayName("다른 창을 검사한 리포트로는 기록하지 않는다 — 스코프 A 검사 → 스코프 B 기록 차단")
    void refuses_whenVerifiedWindowDiffers() {
        CandleCoverage otherWindow = coverage(
                new CoverageScope("risk-lab", SYMBOLS, TO.minusYears(3)), TO.minusYears(3), List.of());

        written = writerWith(true, otherWindow).writeBaseline(reportData(), List.of());

        assertThat(written).isNull();
    }

    @Test
    @DisplayName("다른 종목 집합을 검사한 리포트로는 기록하지 않는다")
    void refuses_whenVerifiedSymbolsDiffer() {
        CandleCoverage otherSymbols = coverage(
                new CoverageScope("full", List.of("005930"), TO), TO, List.of());

        written = writerWith(true, otherSymbols).writeBaseline(reportData(), List.of());

        assertThat(written).isNull();
    }

    @Test
    @DisplayName("opt-in + 커버리지 충족이면 정상 기록한다 — 관문이 정당한 갱신을 막지 않는다")
    void writes_whenOptedInAndCovered() throws IOException {
        written = writerWith(true, coverage(TO, List.of())).writeBaseline(reportData(), List.of());

        assertThat(written).isNotNull();
        assertThat(Files.readString(written))
                .contains("trades: 781")
                .contains("period: 2023-07-22 ~ 2026-07-21");
    }

    @Test
    @DisplayName("모드 이름만 다르고 데이터가 같으면 기록한다 — withNames·빈 슬러그가 정당한 갱신을 막지 않는다")
    void writes_whenOnlyModeLabelDiffers() {
        CandleCoverage sameDataOtherName = coverage(
                new CoverageScope("donchian-risk-lab", SYMBOLS.reversed(), TO), TO, List.of());

        written = writerWith(true, sameDataOtherName).writeBaseline(reportData(), List.of());

        assertThat(written).isNotNull();
    }
}
