package com.trading.backtest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 검증 구간 전용 부가 통계 — 평균 현금 비중(안 쓴 돈 비율)과 분할 매수 사건 수.
 *
 * <p>Walk-Forward는 창마다 학습 런 뒤 검증 런을 돈다. 채점 대상은 검증 구간뿐이므로 라벨에
 * {@code -validate-}가 붙은 런에서만 표본을 모은다. 매매 판단에는 쓰이지 않는다(기록 전용) —
 * 모든 백테스트 모드에서 켜져 있어도 결과에 영향이 없다.
 */
@Component
@Profile("backtest")
public class ScaleInRunStats {

    static final String VALIDATE_MARK = "-validate-";

    private boolean validateRun;
    private double cashRatioSum;
    private int cashRatioDays;
    private final Map<String, Integer> counters = new LinkedHashMap<>();

    /** BacktestRunner가 런 시작 때 부른다 */
    public void beginRun(String label) {
        this.validateRun = label != null && label.contains(VALIDATE_MARK);
    }

    /** 일마감 표본 — 현금 ÷ 총자산 */
    public void sampleDayEnd(double cash, double equity) {
        if (!validateRun || equity <= 0) return;
        cashRatioSum += cash / equity;
        cashRatioDays++;
    }

    /** 검증 런에서만 센다 */
    public void count(String key) {
        if (validateRun) counters.merge(key, 1, Integer::sum);
    }

    /** 랩이 프로필마다 부른다 */
    public void clear() {
        cashRatioSum = 0;
        cashRatioDays = 0;
        counters.clear();
    }

    /** 검증 구간 평균 현금 비중. 표본이 없으면 NaN */
    public double averageCashRatio() {
        return cashRatioDays == 0 ? Double.NaN : cashRatioSum / cashRatioDays;
    }

    public int sampledDays() {
        return cashRatioDays;
    }

    public Map<String, Integer> counters() {
        return Map.copyOf(counters);
    }
}
