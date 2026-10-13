package com.trading.strategy;

/**
 * "매집 흔적" 진입 규칙 한 칸 (흔적레이더 검증, BACKTEST-DESIGN §18 · `_workspace/51_quant` §0 사전 등록).
 *
 * <p>흔적레이더의 판별 규칙은 공개되지 않아 재현할 수 없다. 그래서 "가격은 아직 안 움직였는데 거래량이
 * 튄다"는 <b>생각</b>을 투명한 거래량 규칙 세 가족으로 바꿨다. 값은 사전 등록 후 바꾸지 않는다.
 *
 * <ul>
 *   <li>{@link Family#QUIET_SPIKE A} — 거래량(t) ≥ k × 직전 평균 거래량, |등락률(t)| ≤ 3%</li>
 *   <li>{@link Family#CLUSTER_BASE B} — 최근 10일 중 "거래량 ≥ 1.5배 · |등락률| ≤ 2%"인 날이 m일 이상
 *       + 20일 고저폭 ≤ x (바닥 다지기)</li>
 *   <li>{@link Family#OBV_DIVERGENCE C} — 20봉 OBV 최소제곱 기울기 &gt; 0, 20일 가격 변화 0~3%</li>
 * </ul>
 *
 * @param trendFilter     true면 종목 추세 조건(종가(t) &gt; 직전 120봉 종가 평균 — 돈치안과 같은 모양)을 건다
 * @param volumeAvgWindow 거래량 평균 창(기본 20). 사전 등록된 조건부 민감도(16·24)에서만 바꾼다
 */
public record AccumulationRule(Family family, double spikeMultiple, int clusterMinDays,
                               double maxBaseRange, boolean trendFilter, int volumeAvgWindow) {

    public enum Family { QUIET_SPIKE, CLUSTER_BASE, OBV_DIVERGENCE }

    // ── 사전 등록 고정 상수 (스윕하지 않는다) ─────────────────────────────────
    public static final int    DEFAULT_VOLUME_AVG_WINDOW = 20;
    public static final double QUIET_MAX_ABS_CHANGE      = 0.03;
    public static final int    CLUSTER_WINDOW            = 10;
    public static final double CLUSTER_VOLUME_MULTIPLE   = 1.5;
    public static final double CLUSTER_MAX_ABS_CHANGE    = 0.02;
    public static final int    RANGE_WINDOW              = 20;
    public static final int    OBV_WINDOW                = 20;
    public static final double OBV_MAX_PRICE_CHANGE      = 0.03;
    public static final int    TREND_MA                  = 120;
    /** 가격제한폭(±30%)으로는 불가능한 하루 변화 — 원주가의 액면분할·재상장 흔적으로 본다 */
    public static final double MAX_NORMAL_DAILY_MOVE     = 0.31;

    public AccumulationRule {
        if (family == null) throw new IllegalArgumentException("family 필수");
        if (volumeAvgWindow < 2) throw new IllegalArgumentException("거래량 평균 창은 2 이상: " + volumeAvgWindow);
    }

    public static AccumulationRule quietSpike(double k) {
        return new AccumulationRule(Family.QUIET_SPIKE, k, 0, 0, true, DEFAULT_VOLUME_AVG_WINDOW);
    }

    public static AccumulationRule clusterBase(int minDays, double maxRange) {
        return new AccumulationRule(Family.CLUSTER_BASE, 0, minDays, maxRange, true, DEFAULT_VOLUME_AVG_WINDOW);
    }

    public static AccumulationRule obvDivergence() {
        return new AccumulationRule(Family.OBV_DIVERGENCE, 0, 0, 0, true, DEFAULT_VOLUME_AVG_WINDOW);
    }

    /** NT 변형 — 종목 추세 조건만 뺀다 ("바닥 매집은 장기 평균선 아래에서 일어난다"는 읽기) */
    public AccumulationRule withoutTrend() {
        return new AccumulationRule(family, spikeMultiple, clusterMinDays, maxBaseRange, false, volumeAvgWindow);
    }

    public AccumulationRule withVolumeAvgWindow(int window) {
        return new AccumulationRule(family, spikeMultiple, clusterMinDays, maxBaseRange, trendFilter, window);
    }

    /**
     * 데이터 관문이 훑는 봉 수(t 포함) — 가장 긴 특징 창(B: 10일 × 각자의 거래량 평균 창)에 앞 봉 1개를 더한다.
     * 기본 창 20이면 31봉(t−30..t).
     */
    public int guardBars() {
        return Math.max(volumeAvgWindow + CLUSTER_WINDOW, Math.max(OBV_WINDOW + 1, RANGE_WINDOW)) + 1;
    }

    /** 판정에 필요한 최소 완성 봉 수 — 추세 조건이 있으면 121(직전 120봉 + 신호일) */
    public int requiredBars() {
        return trendFilter ? Math.max(TREND_MA + 1, guardBars()) : guardBars();
    }
}
