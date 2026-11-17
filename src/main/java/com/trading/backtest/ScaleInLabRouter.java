package com.trading.backtest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * scale-in 모드 라우팅 (2026-11-12, 사용자 요청 연구). 창은 약세장 stress(6.5년·24창), 유니버스는 §14.1 후보 54종목 —
 * donchian-stress-sizing과 같다. 모드:
 * <ul>
 *   <li>{@code scale-in-anchor} — 회귀 앵커 SZ0·SZ2만 (기본값 무영향 확인)</li>
 *   <li>{@code scale-in-lab} — 본 격자 15칸 + SZ2 비교행</li>
 *   <li>{@code scale-in-sens} — {@code --backtest.scale-in-base=<코드>} 프로필의 ±20%·비용 민감도</li>
 *   <li>{@code stop-width-lab} — 손절 폭 스윕 15칸(56_quant) / {@code stop-width-sens} — {@code --backtest.stop-width-base=<코드>}</li>
 *   <li>{@code bear-rs-anchor} — D0′ 앵커만 / {@code bear-rs-lab} — 하락장 상대강도 예외 14칸 × 진입일 가정 2 (55_quant)</li>
 * </ul>
 */
@Component
@Profile("backtest")
public class ScaleInLabRouter {

    static final String LABEL = "돈치안 + P3 + 금액비중/분할매수";
    static final String SLUG = "DONCHIAN-P3";
    static final String STOP_LABEL = "돈치안 + 트레일 + 최대20일 + 0.25R·동시5 + 지수MA120 · 손절 폭 스윕";
    static final String STOP_SLUG = "DONCHIAN-SZ2-IDX120";
    static final String BEAR_LABEL = "A동 D0′(돈치안 · −15% 손절 · 0.25R · 1주 상한 1R×3 · 지수MA120) · 하락장 상대강도 예외";
    static final String BEAR_SLUG = "DONCHIAN-F15-MIN1CAP3";

    private final CandidateLabRouter candidateRouter;
    private final ScaleInLab lab;
    private final String sensBase;
    private final StopWidthLab stopWidthLab;
    private final String stopWidthBase;
    private final BearRsLab bearRsLab;

    public ScaleInLabRouter(CandidateLabRouter candidateRouter, ScaleInLab lab,
                            @Value("${backtest.scale-in-base:}") String sensBase,
                            StopWidthLab stopWidthLab,
                            @Value("${backtest.stop-width-base:}") String stopWidthBase,
                            BearRsLab bearRsLab) {
        this.candidateRouter = candidateRouter;
        this.lab = lab;
        this.sensBase = sensBase;
        this.stopWidthLab = stopWidthLab;
        this.stopWidthBase = stopWidthBase;
        this.bearRsLab = bearRsLab;
    }

    public boolean route(String mode) {
        if ("bear-rs-anchor".equalsIgnoreCase(mode) || "bear-rs-lab".equalsIgnoreCase(mode)) {   // 55_quant
            runBearRs(mode, "bear-rs-anchor".equalsIgnoreCase(mode) ? BearRsProfile.anchor() : BearRsProfile.grid());
            return true;
        }
        if ("stop-width-lab".equalsIgnoreCase(mode)) {
            stopWidthLab.run(candidateRouter.stressScope(mode, STOP_LABEL, STOP_SLUG), StopWidthProfile.grid(), "본 격자");
            return true;
        }
        if ("stop-pct-lab".equalsIgnoreCase(mode)) {
            stopWidthLab.run(candidateRouter.stressScope(mode, STOP_LABEL + " · 고정% 손절", STOP_SLUG + "-PCT"),
                    StopWidthProfile.pctGrid(), "고정% 격자");
            return true;
        }
        if ("stop-size-lab".equalsIgnoreCase(mode)) {   // 59_quant — −15% 손절에서 1R 비율 · 1주 강제 매수
            List<StopWidthProfile> f15 = List.of(findStopProfile("F15-S"), findStopProfile("F15-X"));
            runSizing(mode, f15, 0.0025, false, "R025");
            runSizing(mode, f15, 0.0050, false, "R050");
            runSizing(mode, f15, 0.0025, true, "R025-MIN1");
            runSizing(mode, f15, 0.0050, true, "R050-MIN1");
            stopWidthLab.useSizing(StopWidthLab.SZ2_RISK_FRACTION, false);
            return true;
        }
        if ("stop-size-cap-lab".equalsIgnoreCase(mode)) {   // 61_quant — 1주 강제 매수 상한 배수
            List<StopWidthProfile> f15 = List.of(findStopProfile("F15-S"), findStopProfile("F15-X"));
            for (double cap : List.of(2.0, 3.0, 5.0)) {
                stopWidthLab.useSizing(0.0025, true, cap);
                String tag = String.format("R025-MIN1-CAP%.0f", cap);
                stopWidthLab.run(candidateRouter.stressScope(mode, STOP_LABEL + " · 사이징 " + tag, STOP_SLUG + "-SIZE-" + tag),
                        f15, "사이징 " + tag);
            }
            stopWidthLab.useSizing(StopWidthLab.SZ2_RISK_FRACTION, false);
            return true;
        }
        if ("stop-size-sens".equalsIgnoreCase(mode)) {  // 59_quant §3 — 0.25R + 1주 강제 매수의 민감도·비용
            stopWidthLab.useSizing(0.0025, true);
            for (String code : List.of("F15-S", "F15-X")) {
                stopWidthLab.run(candidateRouter.stressScope(mode, STOP_LABEL + " · R025-MIN1 민감도 " + code,
                        STOP_SLUG + "-SIZE-R025-MIN1-SENS-" + code), StopWidthProfile.sensitivity(findStopProfile(code)), "민감도");
            }
            stopWidthLab.useSizing(StopWidthLab.SZ2_RISK_FRACTION, false);
            return true;
        }
        if ("stop-width-sens".equalsIgnoreCase(mode)) {
            StopWidthProfile base = findStopProfile(stopWidthBase);
            stopWidthLab.run(candidateRouter.stressScope(mode, STOP_LABEL + " · 민감도 " + base.code(),
                    STOP_SLUG + "-SENS-" + base.code()), StopWidthProfile.sensitivity(base), "민감도");
            return true;
        }
        if ("scale-in-anchor".equalsIgnoreCase(mode)) {
            lab.runAnchors(candidateRouter.stressScope(mode, LABEL, SLUG));
            return true;
        }
        if ("scale-in-lab".equalsIgnoreCase(mode)) {
            lab.runMain(candidateRouter.stressScope(mode, LABEL, SLUG));
            return true;
        }
        if ("scale-in-sens".equalsIgnoreCase(mode)) {
            ScaleInProfile base = findProfile(sensBase);
            lab.runSensitivity(candidateRouter.stressScope(mode, LABEL, SLUG), base);
            return true;
        }
        return false;
    }

    private void runBearRs(String mode, List<BearRsProfile> profiles) {
        for (String code : List.of("F15-S", "F15-X")) {
            String tag = (profiles.size() == 1 ? "ANCHOR-" : "") + code;
            bearRsLab.run(candidateRouter.stressScope(mode, BEAR_LABEL + " · " + tag, BEAR_SLUG + "-" + tag),
                    findStopProfile(code), profiles);
        }
    }

    private void runSizing(String mode, List<StopWidthProfile> profiles, double riskFraction, boolean minOneShare, String tag) {
        stopWidthLab.useSizing(riskFraction, minOneShare);
        stopWidthLab.run(candidateRouter.stressScope(mode, STOP_LABEL + " · 사이징 " + tag, STOP_SLUG + "-SIZE-" + tag),
                profiles, "사이징 " + tag);
    }

    static StopWidthProfile findStopProfile(String code) {
        List<StopWidthProfile> grid = new java.util.ArrayList<>(StopWidthProfile.grid());
        grid.addAll(StopWidthProfile.pctGrid());
        return grid.stream().filter(p -> p.code().equalsIgnoreCase(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "--backtest.stop-width-base 코드를 격자에서 찾지 못함: '" + code + "' — 가능: "
                                + grid.stream().map(StopWidthProfile::code).toList()));
    }

    static ScaleInProfile findProfile(String code) {
        List<ScaleInProfile> grid = ScaleInProfile.mainGrid();
        return grid.stream().filter(p -> p.code().equalsIgnoreCase(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "--backtest.scale-in-base 코드를 본 격자에서 찾지 못함: '" + code + "' — 가능: "
                                + grid.stream().map(ScaleInProfile::code).toList()));
    }
}
