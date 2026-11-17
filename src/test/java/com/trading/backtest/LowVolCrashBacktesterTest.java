package com.trading.backtest;

import com.trading.backtest.EventStatsBacktester.Quantiles;
import com.trading.backtest.LowVolCrashBacktester.Buckets;
import com.trading.backtest.LowVolCrashBacktester.HorizonStat;
import com.trading.backtest.LowVolCrashBacktester.StockSeries;
import com.trading.backtest.LowVolCrashBacktester.VolWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 약세장 방어 측정의 순수 계산 로직 검증 — 목 없이(static/package-private 헬퍼) 확인한다.
 * 특히 "표본 접기": 한 급락 이벤트의 여러 종목이 버킷 중앙값 하나로 접혀 n이 이벤트 수가 되는지.
 */
@DisplayName("LowVolCrashBacktester — 변동성/버킷/앵커/전방수익률/표본접기")
class LowVolCrashBacktesterTest {

    private static final LocalDate AXIS0 = LocalDate.of(2024, 1, 1);

    /** 픽스처용 짧은 변동성 창 (앵커 직전 3거래일) — 프로덕션 PRE/DURING과 같은 슬라이스 규칙 */
    private static final VolWindow W3 = new VolWindow("T3", "테스트 3거래일", 3, 0);

    // ── 변동성 (모표준편차) ────────────────────────────────────────────────────

    @Test
    @DisplayName("로그수익률 모표준편차: 수익률 [0.1, 0.2] → 0.05")
    void logReturnStdev_knownSequence() {
        List<Double> closes = List.of(100.0, 100.0 * Math.exp(0.1), 100.0 * Math.exp(0.3));
        assertThat(CrashVolCalculations.logReturnStdev(closes)).isCloseTo(0.05, within(1e-9));
    }

    @Test
    @DisplayName("수익률 2개 미만이면 표준편차 0")
    void logReturnStdev_tooFew() {
        assertThat(CrashVolCalculations.logReturnStdev(List.of(100.0, 110.0)))
                .isCloseTo(0.0, within(1e-12));
    }

    // ── 버킷 3등분 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("변동성 3등분: 9종목(1..9) → 저={a,b,c}·고={g,h,i}·중={d,e,f}")
    void terciles_splitsExactly() {
        Map<String, Double> vol = new LinkedHashMap<>();
        String[] syms = {"a", "b", "c", "d", "e", "f", "g", "h", "i"};
        for (int i = 0; i < syms.length; i++) vol.put(syms[i], (double) (i + 1));

        Buckets b = CrashVolCalculations.terciles(vol);

        assertThat(b.low()).containsExactly("a", "b", "c");
        assertThat(b.mid()).containsExactly("d", "e", "f");
        assertThat(b.high()).containsExactly("g", "h", "i");
    }

    @Test
    @DisplayName("정렬은 변동성 값 기준 — 삽입 순서가 뒤섞여도 저/고가 값으로 갈린다")
    void terciles_sortsByValue() {
        Map<String, Double> vol = new LinkedHashMap<>();
        vol.put("high1", 9.0);
        vol.put("low1", 1.0);
        vol.put("mid1", 5.0);
        vol.put("low2", 2.0);
        vol.put("high2", 8.0);
        vol.put("mid2", 4.0);

        Buckets b = CrashVolCalculations.terciles(vol); // edge = 6/3 = 2

        assertThat(b.low()).containsExactly("low1", "low2");
        assertThat(b.high()).containsExactly("high2", "high1");
    }

    // ── 급락 앵커 감지 (쿨다운) ─────────────────────────────────────────────────

    @Test
    @DisplayName("합성 지수 -8% 구간 1개 → 앵커 1개, 쿨다운으로 인접일 중복 안 잡힘")
    void detectAnchors_singleDropWithCooldown() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 60; i++) closes.add(100.0);
        // index 30,31,32 모두 트레일링 10일 -8% 돌파 (close[20]=close[21]=close[22]=100)
        closes.set(30, 92.0);
        closes.set(31, 92.0);
        closes.set(32, 92.0);
        // 33부터 회복 → 이후 트레일링 수익률은 양(+)이라 미돌파

        List<Integer> anchors = CrashVolCalculations.detectAnchorIndices(
                closes, -0.07, 10, 20);

        assertThat(anchors).containsExactly(30); // 31,32는 쿨다운(20)으로 병합 제외
    }

    @Test
    @DisplayName("쿨다운보다 멀리 떨어진 두 번째 급락은 별도 앵커로 잡힌다")
    void detectAnchors_secondDropBeyondCooldown() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 70; i++) closes.add(100.0);
        closes.set(30, 92.0); // 첫 앵커
        closes.set(55, 92.0); // 55-30=25 ≥ 쿨다운 20 → 별도 앵커

        List<Integer> anchors = CrashVolCalculations.detectAnchorIndices(
                closes, -0.07, 10, 20);

        assertThat(anchors).containsExactly(30, 55);
    }

    // ── 전방 수익률 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("전방 수익률: 앵커 종가 대비 D+N 종가 비율 − 1")
    void forwardReturn_exact() {
        List<Double> closes = List.of(100.0, 110.0, 120.0, 90.0);
        assertThat(CrashVolCalculations.forwardReturn(closes, 0, 2)).isCloseTo(0.20, within(1e-9));
        assertThat(CrashVolCalculations.forwardReturn(closes, 0, 3)).isCloseTo(-0.10, within(1e-9));
    }

    @Test
    @DisplayName("데이터가 모자란 호라이즌·0 진입가는 null")
    void forwardReturn_outOfRange() {
        List<Double> closes = List.of(100.0, 110.0, 120.0);
        assertThat(CrashVolCalculations.forwardReturn(closes, 2, 5)).isNull();
        assertThat(CrashVolCalculations.forwardReturn(List.of(0.0, 100.0), 0, 1)).isNull();
    }

    // ── 표본 접기 (핵심) ───────────────────────────────────────────────────────

    @Test
    @DisplayName("한 이벤트의 9종목 → 버킷 중앙값 1개로 접힘 (n=이벤트수 1, 종목수 9 아님)")
    void aggregate_foldsPerEvent_notPerStock() {
        List<LocalDate> dates = axis(25);
        List<Double> kospiCloses = flat(25);
        kospiCloses.set(15, kospiCloses.get(10) * 0.95); // KOSPI D+5 = -5%

        // 9종목: 변동성창(직전 3일)으로 저→고 순서가 결정되게 하고, D+5 전방수익률만 개별 지정
        Map<String, StockSeries> universe = new LinkedHashMap<>();
        double[] fwd = {0.10, 0.20, 0.30, 0.0, 0.0, 0.0, -0.10, -0.20, -0.30};
        for (int k = 0; k < 9; k++) {
            universe.put("s" + k, stock(dates, k, fwd[k]));
        }

        List<HorizonStat> stats = CrashVolCalculations.aggregate(List.of(10),
                new CrashVolScope(kospiCloses, dates, universe, W3, 3, List.of(5)));

        HorizonStat d5 = stats.get(0);
        assertThat(d5.horizon()).isEqualTo(5);
        assertThat(d5.events()).isEqualTo(1);          // 이벤트 수 (종목 9 아님)
        assertThat(d5.lowVol().n()).isEqualTo(1);
        assertThat(d5.highVol().n()).isEqualTo(1);
        // 저 버킷(s0,s1,s2) 전방수익률 [0.10,0.20,0.30]의 중앙값 = 0.20
        assertThat(d5.lowVol().median()).isCloseTo(0.20, within(1e-9));
        // 고 버킷(s6,s7,s8) [-0.10,-0.20,-0.30] 중앙값 = -0.20
        assertThat(d5.highVol().median()).isCloseTo(-0.20, within(1e-9));
        assertThat(d5.kospi().median()).isCloseTo(-0.05, within(1e-9));
        assertThat(d5.lowMinusHigh()).isCloseTo(0.40, within(1e-9));
    }

    @Test
    @DisplayName("저·고 버킷 커버리지 미달 이벤트는 통째로 제외 (Spillover MIN 커버리지 사상)")
    void aggregate_skipsLowCoverageEvent() {
        List<LocalDate> dates = axis(25);
        List<Double> kospiCloses = flat(25);
        // 6종목뿐 → terciles edge = 2 → 저·고 각 2종목 < minCoverage 3 → 이벤트 스킵
        Map<String, StockSeries> universe = new LinkedHashMap<>();
        for (int k = 0; k < 6; k++) universe.put("s" + k, stock(dates, k, 0.10));

        List<HorizonStat> stats = CrashVolCalculations.aggregate(List.of(10),
                new CrashVolScope(kospiCloses, dates, universe, W3, 3, List.of(5)));

        assertThat(stats.get(0).events()).isZero();
    }

    // ── 변동성 창 2종 (PRE = 급락 이전 / DURING = 급락 국면 포함) ────────────────

    @Test
    @DisplayName("PRE 창은 급락 구간(트레일링 10거래일)을 완전히 배제한다 — 슬라이스 경계")
    void volWindow_preSliceExcludesCrashSpan() {
        // close[i] = i 로 두면 슬라이스 내용이 곧 인덱스라 경계를 눈으로 확인할 수 있다
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 60; i++) closes.add((double) i);

        List<Double> pre = LowVolCrashBacktester.PRE_WINDOW.slice(closes, 40);
        List<Double> during = LowVolCrashBacktester.DURING_WINDOW.slice(closes, 40);

        assertThat(pre).hasSize(21);                       // 21종가 = 20개 로그수익률
        assertThat(pre.get(0)).isEqualTo(10.0);            // si-30
        assertThat(pre.get(pre.size() - 1)).isEqualTo(30.0); // si-10 → 급락 구간(31~40) 미포함
        assertThat(during).hasSize(21);
        assertThat(during.get(0)).isEqualTo(20.0);
        assertThat(during.get(during.size() - 1)).isEqualTo(40.0); // 앵커일 포함
    }

    @Test
    @DisplayName("급락 구간에만 큰 변동을 심으면 PRE는 낮고 DURING은 높다 — 두 정의는 서로 다른 값")
    void volWindow_preAndDuringMeasureDifferentThings() {
        List<Double> closes = flat(60);
        // 급락 이전 구간(PRE 대상)에는 아주 작은 흔들림만
        for (int i = 11; i <= 30; i++) if (i % 2 == 0) closes.set(i, 100.1);
        // 앵커(40) 직전 10거래일 = 급락 구간(index 31~40)에만 큰 변동
        for (int i = 31; i <= 40; i++) closes.set(i, i % 2 == 0 ? 90.0 : 110.0);

        double pre = CrashVolCalculations.logReturnStdev(
                LowVolCrashBacktester.PRE_WINDOW.slice(closes, 40));
        double during = CrashVolCalculations.logReturnStdev(
                LowVolCrashBacktester.DURING_WINDOW.slice(closes, 40));

        assertThat(pre).isGreaterThan(0.0).isLessThan(0.01); // 급락 전엔 조용했다
        assertThat(during).isGreaterThan(0.05);              // 급락 국면에서 크게 흔들렸다
        assertThat(pre).isNotEqualTo(during);
    }

    // ── 쿨다운 ≥ 최대 호라이즌 (전방창 중첩 제거) ───────────────────────────────

    @Test
    @DisplayName("쿨다운 기본값은 최대 호라이즌(60) 이상 — D+60 전방창이 겹치지 않는다")
    void cooldown_atLeastMaxHorizon_soForwardWindowsDoNotOverlap() {
        int maxHorizon = LowVolCrashBacktester.HORIZONS.stream()
                .mapToInt(Integer::intValue).max().orElseThrow();
        assertThat(LowVolCrashBacktester.MAX_HORIZON).isEqualTo(maxHorizon);
        assertThat(LowVolCrashBacktester.COOLDOWN_DAYS).isGreaterThanOrEqualTo(maxHorizon);

        List<Double> closes = flat(160);
        closes.set(30, 92.0);  // 첫 앵커
        closes.set(55, 92.0);  // 간격 25 < 60 → 쿨다운으로 제외 (전방창이 겹치므로)
        closes.set(95, 92.0);  // 간격 65 ≥ 60 → 별도 앵커

        List<Integer> anchors = CrashVolCalculations.detectAnchorIndices(
                closes, -0.07, 10, LowVolCrashBacktester.COOLDOWN_DAYS);

        assertThat(anchors).containsExactly(30, 95);
        for (int i = 1; i < anchors.size(); i++) {
            assertThat(anchors.get(i) - anchors.get(i - 1)).isGreaterThanOrEqualTo(maxHorizon);
        }
    }

    // ── 재현성: 앵커 탐지는 선언 기간(to)까지 ──────────────────────────────────

    @Test
    @DisplayName("to 이후(전방수익률용 여유 데이터)의 급락은 앵커로 잡히지 않는다")
    void anchorScope_clampedToDeclaredEnd() {
        List<LocalDate> dates = axis(80);
        List<Double> closes = flat(80);
        closes.set(70, 92.0);                 // to 이후 구간의 급락
        LocalDate to = AXIS0.plusDays(59);    // index 0~59까지가 선언 기간

        int scope = CrashVolCalculations.anchorScopeSize(dates, to);
        assertThat(scope).isEqualTo(60);

        // 클램프 적용: to 이후 급락은 앵커에서 제외
        assertThat(CrashVolCalculations.detectAnchorIndices(
                closes.subList(0, scope), -0.07, 10, 60)).isEmpty();
        // 클램프가 없었다면 잡혔을 것 (= 데이터가 쌓일수록 결과가 달라짐)
        assertThat(CrashVolCalculations.detectAnchorIndices(closes, -0.07, 10, 60))
                .containsExactly(70);
    }

    // ── 빈 표본 표기 (측정 못 함 ≠ 차이 0) ─────────────────────────────────────

    @Test
    @DisplayName("표본이 0이면 리포트에 '-'로 찍힌다 (+0.00%로 오독 금지)")
    void emptySample_rendersDash() {
        assertThat(ReportFormat.signedPct(Quantiles.EMPTY)).isEqualTo("-");
        assertThat(ReportFormat.signedPct(Quantiles.of(List.of(0.0)))).isEqualTo("+0.00%");

        HorizonStat empty = new HorizonStat(60, Quantiles.EMPTY, Quantiles.EMPTY,
                Quantiles.EMPTY, Quantiles.EMPTY, 0);
        assertThat(ReportFormat.lowMinusHighText(empty)).isEqualTo("-");

        HorizonStat measured = new HorizonStat(5, Quantiles.of(List.of(0.02)),
                Quantiles.EMPTY, Quantiles.of(List.of(-0.01)), Quantiles.of(List.of(-0.05)), 1);
        assertThat(ReportFormat.lowMinusHighText(measured)).isEqualTo("+3.00%");
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private static List<LocalDate> axis(int n) {
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < n; i++) dates.add(AXIS0.plusDays(i));
        return dates;
    }

    private static List<Double> flat(int n) {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < n; i++) closes.add(100.0);
        return closes;
    }

    /**
     * 종목 시계열: 앵커(index 10) 진입가 100 고정. 변동성창(직전 3일 = index 7~10)에서
     * index 8만 미세하게 흔들어 변동성 순위를 k로 통제한다. D+5(index 15)만 전방수익률 지정.
     */
    private static StockSeries stock(List<LocalDate> dates, int k, double d5Return) {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) closes.add(100.0);
        closes.set(8, 100.0 * (1.0 + 1e-4 * (k + 1))); // k가 클수록 변동성↑ → 고변동성 버킷
        closes.set(15, 100.0 * (1.0 + d5Return));      // 앵커(100) 대비 D+5 수익률
        Map<LocalDate, Integer> idx = new HashMap<>();
        for (int i = 0; i < dates.size(); i++) idx.put(dates.get(i), i);
        return new StockSeries(closes, idx);
    }
}
