package com.trading.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기준선 yml 판독 — 앵커 수치의 정본은 이 파일 하나이므로, 읽기가 깨지면
 * 회귀 앵커 대조가 조용히 무력화된다.
 */
class BaselineStoreTest {

    /** docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml과 같은 자동 생성 포맷 (§14.1 앵커) */
    private static final List<String> ANCHOR_YML = List.of(
            "# 백테스트 기준선 (B-3) — 거버넌스 강등 판정의 분모 (PERFORMANCE-GOVERNANCE §2)",
            "# 자동 생성: BacktestReportWriter — 수동 편집 금지",
            "generated-at: 2026-08-17",
            "period: 2023-07-22 ~ 2026-07-21",
            "method: daily-bar-approximation-v1",
            "validation:   # Walk-Forward 검증 구간 집계 (비용 차감 후)",
            "  trades: 781",
            "  profit-factor: 1.959",
            "  expectancy-pct: 0.0130",
            "  max-drawdown: 0.0987",
            "  win-rate: 0.5186",
            "chosen-k-per-window: [0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5]",
            "adopted-filters: []");

    @Test
    @DisplayName("자동 생성 포맷을 파싱한다 — 지표는 파일에 적힌 문자열 그대로 유지된다")
    void parse_readsAnchorFormat() {
        BaselineSnapshot snap = BaselineStore.parse(ANCHOR_YML).orElseThrow();

        assertThat(snap.from()).isEqualTo(LocalDate.of(2023, 7, 22));
        assertThat(snap.to()).isEqualTo(LocalDate.of(2026, 7, 21));
        assertThat(snap.trades()).isEqualTo(781);
        assertThat(snap.profitFactor()).isEqualTo("1.959");
        // 반올림 자릿수가 앵커의 해상도다 — 0.013으로 줄어들면 대조가 어긋난다
        assertThat(snap.expectancyPct()).isEqualTo("0.0130");
        assertThat(snap.maxDrawdown()).isEqualTo("0.0987");
        assertThat(snap.winRate()).isEqualTo("0.5186");
    }

    @Test
    @DisplayName("키가 빠지거나 깨진 파일은 예외 없이 빈 값 — 대조는 부가 기능이라 실행을 죽이지 않는다")
    void parse_malformedReturnsEmpty() {
        assertThat(BaselineStore.parse(List.of("period: 2023-07-22 ~ 2026-07-21"))).isEmpty();
        assertThat(BaselineStore.parse(List.of("  trades: 781"))).isEmpty();
        assertThat(BaselineStore.parse(List.of("period: 이상한값", "  trades: 781"))).isEmpty();
        assertThat(BaselineStore.parse(List.of())).isEmpty();
    }

    @Test
    @DisplayName("파일이 없으면 빈 값 (첫 기록 실행 — 조용히 건너뛴다)")
    void read_missingFileReturnsEmpty(@TempDir Path dir) {
        assertThat(BaselineStore.read(dir.resolve("BACKTEST-BASELINE-NONE.yml"))).isEmpty();
    }

    @Test
    @DisplayName("실제 파일을 읽어 스냅샷으로 돌려준다")
    void read_parsesFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("BACKTEST-BASELINE-EXITLAB-MA-P3.yml");
        Files.write(file, ANCHOR_YML);

        Optional<BaselineSnapshot> snap = BaselineStore.read(file);

        assertThat(snap).isPresent();
        assertThat(snap.get().trades()).isEqualTo(781);
    }

    @Test
    @DisplayName("파일명 규칙은 기록부(BacktestReportWriter)와 같다")
    void fileName_matchesWriterConvention() {
        assertThat(BaselineStore.fileName("EXITLAB-MA-P3"))
                .isEqualTo("BACKTEST-BASELINE-EXITLAB-MA-P3.yml");
        assertThat(BaselineStore.fileName("")).isEqualTo("BACKTEST-BASELINE.yml");
        assertThat(BaselineStore.fileName(null)).isEqualTo("BACKTEST-BASELINE.yml");
    }

    @Test
    @DisplayName("저장소의 §14.1 앵커 파일이 실제로 읽힌다 (정본 ↔ 판독 연결 회귀)")
    void read_repositoryAnchorFile() {
        Path anchor = BaselineStore.DOCS_DIR.resolve(BaselineStore.fileName("EXITLAB-MA-P3"));
        if (!Files.exists(anchor)) return;   // 저장소 밖에서 돌 때는 건너뛴다

        BaselineSnapshot snap = BaselineStore.read(anchor).orElseThrow();

        assertThat(snap.from()).isEqualTo(LocalDate.of(2023, 7, 22));
        assertThat(snap.to()).isEqualTo(LocalDate.of(2026, 7, 21));
        assertThat(snap.trades()).isPositive();
    }
}
