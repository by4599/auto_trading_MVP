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
 * </ul>
 */
@Component
@Profile("backtest")
public class ScaleInLabRouter {

    static final String LABEL = "돈치안 + P3 + 금액비중/분할매수";
    static final String SLUG = "DONCHIAN-P3";
    static final String STOP_LABEL = "돈치안 + 트레일 + 최대20일 + 0.25R·동시5 + 지수MA120 · 손절 폭 스윕";
    static final String STOP_SLUG = "DONCHIAN-SZ2-IDX120";

    private final CandidateLabRouter candidateRouter;
    private final ScaleInLab lab;
    private final String sensBase;
    private final StopWidthLab stopWidthLab;
    private final String stopWidthBase;

    public ScaleInLabRouter(CandidateLabRouter candidateRouter, ScaleInLab lab,
                            @Value("${backtest.scale-in-base:}") String sensBase,
                            StopWidthLab stopWidthLab,
                            @Value("${backtest.stop-width-base:}") String stopWidthBase) {
        this.candidateRouter = candidateRouter;
        this.lab = lab;
        this.sensBase = sensBase;
        this.stopWidthLab = stopWidthLab;
        this.stopWidthBase = stopWidthBase;
    }

    public boolean route(String mode) {
        if ("stop-width-lab".equalsIgnoreCase(mode)) {
            stopWidthLab.run(candidateRouter.stressScope(mode, STOP_LABEL, STOP_SLUG), StopWidthProfile.grid(), "본 격자");
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

    static StopWidthProfile findStopProfile(String code) {
        List<StopWidthProfile> grid = StopWidthProfile.grid();
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
