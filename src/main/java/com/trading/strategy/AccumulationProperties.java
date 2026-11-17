package com.trading.strategy;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 매집 흔적 전략 스위치 — <b>백테스트 전용</b> 가변 홀더 (흔적레이더 검증, BACKTEST-DESIGN §18).
 *
 * <p>기본 OFF. {@code accumulation-lab} 모드만 칸마다 규칙을 바꿔 끼운다(ExitLabProperties와 같은 패턴).
 * {@code @Profile("backtest")}라 paper/real에는 빈 자체가 없다 — 이 전략은 검증 전이고 모의투자에서 돌지 않는다.
 * 켜져 있으면 {@code DailyBarSimulator}가 진입을 "다음 날 시가"로 판정한다(신호가 t일 완성 봉 기준이라서).
 */
@Component
@Profile("backtest")
public class AccumulationProperties {

    private static final AccumulationRule DEFAULT_RULE = AccumulationRule.quietSpike(3.0);

    private volatile boolean enabled = false;
    private volatile AccumulationRule rule = DEFAULT_RULE;

    public boolean isEnabled()              { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public AccumulationRule getRule() { return rule; }

    public void setRule(AccumulationRule rule) {
        if (rule == null) throw new IllegalArgumentException("rule 필수");
        this.rule = rule;
    }

    /** 랩 종료 시 복원 — 다른 프로필·모드로의 누출 방지 */
    public void resetDefaults() {
        this.enabled = false;
        this.rule = DEFAULT_RULE;
    }
}
