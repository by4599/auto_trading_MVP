package com.trading.strategy;

import com.trading.bucket.StrategyBucket;
import com.trading.market.Candle;
import com.trading.signal.Signal;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 눌림목 반등 모멘텀 스캘핑 (방식3, 2026-07-20 — 검증 없이 모의투자 직결, 사용자 판단).
 *
 * 틱마다 관찰되는 현재가를 종목별 롤링 창(windowSize)에 쌓아 최근 고점·저점을 구하고,
 * 고점 대비 pullbackPct 이상 눌린 뒤 그 저점 대비 reboundPct 이상 반등하면 매수한다.
 * 단순화: 창 안의 최댓값·최솟값 발생 순서(고점이 저점보다 먼저인지)는 강제하지 않는다
 * — 미검증 실험이라는 전제하에 계산을 단순하게 유지했다.
 *
 * 청산은 기존 ATR 손절/15:15 타임컷에 더해 StopLossMonitor의 목표 익절(takeProfitPct)이
 * MIX 버킷 포지션에 한해 추가로 작동한다.
 *
 * @Profile({"paper","backtest"}) — BACKTEST-DESIGN §13 소급 검증을 위해 backtest 프로필도
 * 허용한다. B-3(VB) 결정성은 {@link ScalpingProperties#isEnabled()} 스위치로 보호한다
 * (기본 false — VB/MA돌파만 백테스트할 때는 이 전략이 신호를 내지 않는다).
 * 롤링 창은 인메모리라 앱 재시작 시 초기화된다 — 리스크 1차 방어선이 아니라 진입 신호일 뿐.
 */
@Component
@Profile({"paper", "backtest"})
public class ScalpingStrategy implements Strategy {

    private final ScalpingProperties properties;
    private final Map<String, Deque<Double>> windows = new ConcurrentHashMap<>();

    public ScalpingStrategy(ScalpingProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "SCALPING_MOMENTUM";
    }

    @Override
    public List<Signal> evaluate(String stockCode, List<Candle> candles) {
        if (!properties.isEnabled() || candles.isEmpty()) return List.of();

        double current = candles.get(candles.size() - 1).getClose();
        Deque<Double> window = windows.computeIfAbsent(stockCode, k -> new ArrayDeque<>());
        window.addLast(current);
        while (window.size() > properties.getWindowSize()) {
            window.removeFirst();
        }
        if (window.size() < properties.getWindowSize()) return List.of();

        double high = window.stream().mapToDouble(Double::doubleValue).max().orElse(current);
        double low = window.stream().mapToDouble(Double::doubleValue).min().orElse(current);
        if (high <= 0 || low <= 0) return List.of();

        boolean pulledBack = low <= high * (1 - properties.getPullbackPct());
        boolean rebounded = current >= low * (1 + properties.getReboundPct());

        if (pulledBack && rebounded) {
            return List.of(Signal.buy(stockCode, getName(), StrategyBucket.MIX));
        }
        return List.of();
    }
}
