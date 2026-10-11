package com.trading.bucket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 칸(슬리브)별 낙폭 상한 — ADR-001 §2.2 개정(2026-08-07, 사용자 승인).
 *
 * <p>A동(TREND) 자체 −12% / B동(VB·EVENT·MIX) 자체 −20%에 닿으면 <b>그 칸만</b> 신규 매수를 멈추고
 * 보유를 정리한다. 계좌 단일 경보(누적 MDD 가드)만 두면 미검증 B동 때문에 검증된 A동까지 청산되기 때문이다.
 * A동 12%는 검증에서 관측된 최악(13.1%) 바로 아래 — 검증 범위를 벗어나면 스스로 멈추게 하려는 값이다.
 *
 * <p>값이 0 이하·1 이상·숫자 아님이면 기동을 막는다 — 한도가 조용히 꺼진 채(예: NaN이면 비교가 늘 거짓)
 * 도는 것보다 뜨지 않는 편이 안전하다(39_impl NaN 차단과 같은 원칙).
 */
@Component
public class SleeveDrawdownProperties {

    private final double aSleeveLimit;
    private final double bSleeveLimit;

    public SleeveDrawdownProperties(
            @Value("${trading.bucket.sleeve-drawdown.a-sleeve-limit:0.12}") double aSleeveLimit,
            @Value("${trading.bucket.sleeve-drawdown.b-sleeve-limit:0.20}") double bSleeveLimit) {
        this.aSleeveLimit = requireRatio("a-sleeve-limit", aSleeveLimit);
        this.bSleeveLimit = requireRatio("b-sleeve-limit", bSleeveLimit);
    }

    /** A동(TREND)이면 A 한도, 나머지(이름표 없음 = VB 포함)는 B 한도 */
    public double limitOf(StrategyBucket bucket) {
        return StrategyBucket.orDefault(bucket) == StrategyBucket.TREND ? aSleeveLimit : bSleeveLimit;
    }

    private static double requireRatio(String key, double value) {
        if (!Double.isFinite(value) || value <= 0 || value >= 1) {
            throw new IllegalArgumentException(String.format(
                    "trading.bucket.sleeve-drawdown.%s는 0과 1 사이 비율이어야 한다: %s", key, value));
        }
        return value;
    }
}
