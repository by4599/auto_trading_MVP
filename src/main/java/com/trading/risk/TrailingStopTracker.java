package com.trading.risk;

import com.trading.strategy.FilterProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 트레일링 스톱 필터 상태 (설계 문서 §3.3) — +armProfit 도달 후 고점 대비
 * trail 하락 시 청산. 기본 OFF.
 *
 * 고점은 인메모리 추적 — 앱 재시작 시 소실되며, 그 경우 기존 ATR 손절선이
 * 방어선으로 남는다 (트레일링은 수익 보존 보조 장치이지 1차 방어선이 아니다).
 */
@Component
public class TrailingStopTracker {

    private final FilterProperties filters;
    private final Map<String, Double> highSinceEntry = new ConcurrentHashMap<>();

    public TrailingStopTracker(FilterProperties filters) {
        this.filters = filters;
    }

    public void updateHigh(String stockCode, double price) {
        highSinceEntry.merge(stockCode, price, Math::max);
    }

    /** 청산(전량 매도) 시 호출 — 다음 진입의 고점 추적을 오염시키지 않는다 */
    public void clear(String stockCode) {
        highSinceEntry.remove(stockCode);
    }

    public void clearAll() {
        highSinceEntry.clear();
    }

    /**
     * 트레일링 청산 판정. 필터 OFF면 항상 empty.
     * @return 청산해야 하면 트레일 가격(고점 × (1−trail)), 아니면 empty
     */
    public java.util.OptionalDouble exitPrice(String stockCode, double currentPrice, double entryPrice) {
        FilterProperties.TrailingStop cfg = filters.getTrailingStop();
        if (!cfg.isEnabled()) return java.util.OptionalDouble.empty();

        Double high = highSinceEntry.get(stockCode);
        if (high == null || entryPrice <= 0) return java.util.OptionalDouble.empty();
        if (high < entryPrice * (1 + cfg.getArmProfitPct())) return java.util.OptionalDouble.empty();

        double trailLevel = high * (1 - cfg.getTrailPct());
        if (currentPrice <= trailLevel) {
            return java.util.OptionalDouble.of(trailLevel);
        }
        return java.util.OptionalDouble.empty();
    }
}
