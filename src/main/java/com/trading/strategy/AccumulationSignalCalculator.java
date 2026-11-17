package com.trading.strategy;

import com.trading.market.Candle;

import java.util.List;

import static com.trading.strategy.AccumulationRule.CLUSTER_MAX_ABS_CHANGE;
import static com.trading.strategy.AccumulationRule.CLUSTER_VOLUME_MULTIPLE;
import static com.trading.strategy.AccumulationRule.CLUSTER_WINDOW;
import static com.trading.strategy.AccumulationRule.MAX_NORMAL_DAILY_MOVE;
import static com.trading.strategy.AccumulationRule.OBV_MAX_PRICE_CHANGE;
import static com.trading.strategy.AccumulationRule.OBV_WINDOW;
import static com.trading.strategy.AccumulationRule.QUIET_MAX_ABS_CHANGE;
import static com.trading.strategy.AccumulationRule.RANGE_WINDOW;
import static com.trading.strategy.AccumulationRule.TREND_MA;

/**
 * 매집 흔적 신호 판정 — 순수 계산 (흔적레이더 검증, BACKTEST-DESIGN §18 · `_workspace/51_quant` §0.3).
 *
 * <p>입력은 <b>신호일 t까지의 완성 봉</b>뿐이다. 거래량은 장이 끝나야 다 보이므로 진입은 호출부가
 * t+1일 시가에 한다(장중 판정은 §15.8 거래량 필터와 같은 선견편향).
 *
 * <p>"이하" 한도는 부동소수 오차로 정확한 경계값(예: 정확히 +3.00%)을 떨어뜨리지 않도록 {@link #EPS}만큼 너그럽다.
 */
public final class AccumulationSignalCalculator {

    private static final double EPS = 1e-9;

    private AccumulationSignalCalculator() {
    }

    /**
     * @param bars 완성 일봉, 과거→최신. <b>마지막 봉이 신호일 t</b>이며 t 이후 정보는 들어 있으면 안 된다
     * @return 신호일 t에 매집 흔적이 성립하면 true
     */
    public static boolean fires(List<Candle> bars, AccumulationRule rule) {
        if (bars == null || bars.size() < rule.requiredBars()) return false;
        int t = bars.size() - 1;
        if (!passesDataGuard(bars, t, rule.guardBars())) return false;
        if (rule.trendFilter() && !aboveTrend(bars, t)) return false;
        return switch (rule.family()) {
            case QUIET_SPIKE    -> quietSpike(bars, t, rule);
            case CLUSTER_BASE   -> clusterBase(bars, t, rule);
            case OBV_DIVERGENCE -> obvDivergence(bars, t);
        };
    }

    // ── 공통 관문 · 추세 ──────────────────────────────────────────────────────

    /**
     * 최근 guardBars봉(t−guardBars+1..t)에 거래량 0(거래정지)이 없고, 그 사이 하루 변화가 전부
     * 가격제한폭 안(|변화| ≤ 31%)이어야 한다 — 원주가의 액면분할이 가짜 거래량 급증으로 잡히는 것을 막는다.
     */
    static boolean passesDataGuard(List<Candle> bars, int t, int guardBars) {
        int from = t - guardBars + 1;
        for (int i = from; i <= t; i++) {
            if (bars.get(i).volume() <= 0) return false;
            if (i > from && Math.abs(change(bars, i)) > MAX_NORMAL_DAILY_MOVE) return false;
        }
        return true;
    }

    /** 종가(t) > 직전 120봉(t−120..t−1) 종가 평균 — 돈치안의 추세 조건과 같은 모양 */
    static boolean aboveTrend(List<Candle> bars, int t) {
        double sum = 0;
        for (int i = t - TREND_MA; i < t; i++) sum += bars.get(i).close();
        return bars.get(t).close() > sum / TREND_MA;
    }

    // ── A. 조용한 거래량 급증 ────────────────────────────────────────────────

    private static boolean quietSpike(List<Candle> bars, int t, AccumulationRule rule) {
        return isVolumeSpike(bars, t, rule.spikeMultiple(), rule.volumeAvgWindow())
                && atMost(Math.abs(change(bars, t)), QUIET_MAX_ABS_CHANGE);
    }

    // ── B. 매집일 누적 + 바닥 다지기 ─────────────────────────────────────────

    private static boolean clusterBase(List<Candle> bars, int t, AccumulationRule rule) {
        int days = 0;
        for (int i = t - CLUSTER_WINDOW + 1; i <= t; i++) {
            boolean spike = isVolumeSpike(bars, i, CLUSTER_VOLUME_MULTIPLE, rule.volumeAvgWindow());
            if (spike && atMost(Math.abs(change(bars, i)), CLUSTER_MAX_ABS_CHANGE)) days++;
        }
        return days >= rule.clusterMinDays() && atMost(baseRange(bars, t), rule.maxBaseRange());
    }

    /** 20봉(t−19..t) 최고 고가 ÷ 최저 저가 − 1. 저가가 0 이하면 판정 불가로 무한대 */
    static double baseRange(List<Candle> bars, int t) {
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        for (int i = t - RANGE_WINDOW + 1; i <= t; i++) {
            high = Math.max(high, bars.get(i).high());
            low = Math.min(low, bars.get(i).low());
        }
        return low > 0 ? high / low - 1 : Double.POSITIVE_INFINITY;
    }

    // ── C. OBV 다이버전스 ────────────────────────────────────────────────────

    private static boolean obvDivergence(List<Candle> bars, int t) {
        double priceChange = bars.get(t).close() / bars.get(t - OBV_WINDOW).close() - 1;
        if (priceChange < -EPS || !atMost(priceChange, OBV_MAX_PRICE_CHANGE)) return false;
        return obvSlope(bars, t) > 0;
    }

    /** OBV(거래량 × 부호(종가 변화)의 누적합)를 t−19..t 20점으로 만들고 최소제곱 기울기를 구한다 */
    static double obvSlope(List<Candle> bars, int t) {
        double[] obv = new double[OBV_WINDOW];
        double running = 0;
        for (int k = 0; k < OBV_WINDOW; k++) {
            int i = t - OBV_WINDOW + 1 + k;
            running += Math.signum(bars.get(i).close() - bars.get(i - 1).close()) * bars.get(i).volume();
            obv[k] = running;
        }
        double xMean = (OBV_WINDOW - 1) / 2.0;
        double yMean = 0;
        for (double y : obv) yMean += y;
        yMean /= OBV_WINDOW;
        double num = 0;
        double den = 0;
        for (int k = 0; k < OBV_WINDOW; k++) {
            num += (k - xMean) * (obv[k] - yMean);
            den += (k - xMean) * (k - xMean);
        }
        return num / den;
    }

    // ── 공용 ─────────────────────────────────────────────────────────────────

    /**
     * 거래량(i) ≥ mult × 직전 window봉(i−window..i−1) 평균. 그날 자신은 평균에 넣지 않는다.
     * 나눗셈 오차를 피하려고 {@code 거래량 × window ≥ mult × 합}으로 비교한다. 평균이 0이면 거짓.
     */
    static boolean isVolumeSpike(List<Candle> bars, int i, double mult, int window) {
        double sum = 0;
        for (int j = i - window; j < i; j++) sum += bars.get(j).volume();
        if (sum <= 0) return false;
        return (double) bars.get(i).volume() * window >= mult * sum;
    }

    private static double change(List<Candle> bars, int i) {
        return bars.get(i).close() / bars.get(i - 1).close() - 1;
    }

    private static boolean atMost(double value, double limit) {
        return value <= limit + EPS;
    }
}
