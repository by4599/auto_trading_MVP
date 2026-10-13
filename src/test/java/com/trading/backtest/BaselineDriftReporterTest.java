package com.trading.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회귀 앵커 대조 — "기준선 yml만 고치면 되도록" 만드는 장치의 핵심.
 *
 * <p>Java 25 인라인 목 제약 때문에 구체 클래스를 목킹하지 않는다 —
 * {@link BaselineStore}는 익명 하위 클래스로 값을 주입하고 나머지는 실객체다.
 */
class BaselineDriftReporterTest {

    private static final LocalDate FROM = LocalDate.of(2023, 7, 22);
    private static final LocalDate TO   = LocalDate.of(2026, 7, 21);

    /** §14.1 앵커 (docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml, 2026-08-17 재측정값) */
    private static final BaselineSnapshot ANCHOR = new BaselineSnapshot(
            FROM, TO, 781, "1.959", "0.0130", "0.0987", "0.5186");

    private static BaselineDriftReporter reporterWith(BaselineSnapshot anchor) {
        return new BaselineDriftReporter(new BaselineStore() {
            @Override
            public Optional<BaselineSnapshot> load(String fileSlug) {
                return Optional.ofNullable(anchor);
            }
        });
    }

    /** 검증 구간 집계 — 대조에 쓰는 5지표만 의미가 있다 */
    private static BacktestMetrics metrics(int trades, double pf, double expectancy,
                                           double mdd, double winRate) {
        return new BacktestMetrics(trades, winRate, pf, expectancy, mdd, 1.2428, 22_427_654, 2.0, 9.9);
    }

    @Test
    @DisplayName("앵커와 같은 값이면 일치로 표시한다 (사람이 문서 숫자를 맞춰 볼 필요가 없다)")
    void render_matchingRun() {
        String md = reporterWith(ANCHOR).render("EXITLAB-MA-P3", FROM, TO,
                "RR1 하프(0.5R·동시5)", metrics(781, 1.959, 0.0130, 0.0987, 0.5186));

        assertThat(md).contains("## 회귀 앵커 대조 — docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml");
        assertThat(md).contains("✅ 앵커와 일치");
        assertThat(md).contains("| 트레이드 | 781 | 781 | ✅ |");
        assertThat(md).doesNotContain("앵커 드리프트").doesNotContain("⚠ 다름");
    }

    @Test
    @DisplayName("값이 달라지면 어느 지표가 얼마나 달라졌는지 보여준다 (trades 781→779)")
    void render_driftShowsWhichMetricMoved() {
        String md = reporterWith(ANCHOR).render("EXITLAB-MA-P3", FROM, TO,
                "RR1 하프(0.5R·동시5)", metrics(779, 1.940, 0.0130, 0.0987, 0.5186));

        assertThat(md).contains("앵커 드리프트 2건");
        assertThat(md).contains("트레이드 781→779");
        assertThat(md).contains("PF 1.959→1.940");
        assertThat(md).contains("| MDD | 0.0987 | 0.0987 | ✅ |");
    }

    @Test
    @DisplayName("판정 창이 다르면 대조를 생략한다 — 창을 바꾼 실행(§14.3 약세장 등)은 정상 사용")
    void render_differentWindowSkipsComparison() {
        String md = reporterWith(ANCHOR).render("EXITLAB-MA-P3",
                LocalDate.of(2020, 1, 1), TO, "RR1", metrics(1591, 1.26, 0.004, 0.335, 0.48));

        assertThat(md).contains("대조를 생략");
        assertThat(md).doesNotContain("앵커 드리프트").doesNotContain("| 지표 |");
    }

    @Test
    @DisplayName("기준선 파일이 없으면 아무것도 쓰지 않는다 (첫 기록 실행)")
    void render_noAnchorProducesNothing() {
        String md = reporterWith(null).render("EXITLAB-DONCHIAN-P3", FROM, TO,
                "RR1", metrics(781, 1.959, 0.0130, 0.0987, 0.5186));

        assertThat(md).isEmpty();
    }

    @Test
    @DisplayName("기준선을 안 쓰는 랩(slug 없음)은 대조 자체를 하지 않는다")
    void render_nullSlugProducesNothing() {
        assertThat(reporterWith(ANCHOR).render(null, FROM, TO, "RR1",
                metrics(781, 1.959, 0.0130, 0.0987, 0.5186))).isEmpty();
    }

    @Test
    @DisplayName("합격 프로필이 없으면 대조 불가를 리포트에 남긴다 (조용히 넘어가지 않는다)")
    void render_noPasserIsRecorded() {
        String md = reporterWith(ANCHOR).render("EXITLAB-MA-P3", FROM, TO, null, null);

        assertThat(md).contains("합격 프로필이 없어 대조하지 않았다");
        assertThat(md).contains("781");
    }

    @Test
    @DisplayName("현재 결과를 앵커 포맷(반올림 자릿수)으로 접어 비교한다")
    void snapshot_foldsToAnchorResolution() {
        BaselineSnapshot now = BaselineSnapshot.of(FROM, TO,
                metrics(781, 1.9594321, 0.01299876, 0.098749, 0.518612));

        assertThat(now.profitFactor()).isEqualTo("1.959");
        assertThat(now.expectancyPct()).isEqualTo("0.0130");
        assertThat(now.maxDrawdown()).isEqualTo("0.0987");
        assertThat(now.winRate()).isEqualTo("0.5186");
        assertThat(ANCHOR.compare(now)).noneMatch(BaselineSnapshot.MetricDiff::drifted);
    }
}
