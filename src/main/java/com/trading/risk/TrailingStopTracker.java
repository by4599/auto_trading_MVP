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

    /**
     * 이월(다일 보유) 포지션의 "대기 트레일 손절선" — 저장된 고점(오늘 고가 반영 전 =
     * 어제까지의 고점)만으로 계산한다. 백테스트(BACKTEST-DESIGN §14)에서 이월 포지션의
     * 트레일 청산을 선견 없이 모델링하려는 용도: 오늘 고가로 스톱을 올린 뒤 그 스톱을
     * 같은 날 저가로 때리는 순환(look-ahead)을 막기 위해, 고가 갱신 전에 이 레벨로 먼저 판정한다.
     * 현재가는 인자로 받지 않는다 — 트리거(갭/저가 관통)는 호출부가 당일 시가·저가로 판정한다.
     *
     * @return 트레일이 무장된 상태면 손절 레벨(고점 × (1−trail)), 아니면 empty
     */
    public java.util.OptionalDouble exitLevelFromPriorHigh(String stockCode, double entryPrice) {
        FilterProperties.TrailingStop cfg = filters.getTrailingStop();
        if (!cfg.isEnabled()) return java.util.OptionalDouble.empty();

        Double high = highSinceEntry.get(stockCode);
        if (high == null || entryPrice <= 0) return java.util.OptionalDouble.empty();
        if (high < entryPrice * (1 + cfg.getArmProfitPct())) return java.util.OptionalDouble.empty();

        return java.util.OptionalDouble.of(high * (1 - cfg.getTrailPct()));
    }
}
