package com.trading.backtest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 금액 비중 진입 + 분할 매수(피라미딩) 스위치 — 백테스트 전용 mutable holder (2026-11-12, 사용자 요청 연구).
 *
 * <p>기본값 {@code enabled=false}이면 {@link DailyBarSimulator}가 이 클래스를 전혀 보지 않고 기존 R 사이징
 * 경로({@code OrderEngine.execute})를 그대로 탄다 — 기존 모드·회귀 앵커가 바뀌지 않는다.
 * paper/real에는 존재하지 않는다(@Profile("backtest")).
 *
 * <p>켜면: 종목당 목표 = 진입 시점 총자산 × {@link #targetWeight}, 이를 {@link #tranches}등분해
 * 1차는 돌파 시, n차는 1차 체결가 + (n−1)×{@link #stepAtr}×ATR 도달 시 산다. 하락 시 추가 매수 없음.
 */
@Component
@Profile("backtest")
public class ScaleInProperties {

    /** 손절 기준가 — 추가 매수 뒤 손절선을 어디에 두는가 */
    public enum StopBasis {
        /** 1차 체결가 − 손절폭 (추가 매수해도 손절선 불변) */
        FIRST_ENTRY,
        /** 평균단가 − 손절폭 (추가 매수마다 손절선이 올라간다) */
        AVERAGE
    }

    /** 지수 하락 추세일 때의 취급 */
    public enum IndexGate {
        /** 리스크 룰(IndexTrendRule)에 맡긴다 — 켜져 있으면 1차·추가 모두 막고, 꺼져 있으면 아무것도 안 막는다 */
        RULE,
        /** 하락 추세면 1차분(목표의 1/3)만 허용, 추가 매수 금지. IndexTrendRule은 꺼 둔 채 쓴다 */
        FIRST_TRANCHE_ONLY
    }

    /** C100(전량 1회)에서 하락 추세일 때 허용하는 비율 — "1차분(1/3)만" */
    static final int FIRST_TRANCHE_DIVISOR = 3;

    private volatile boolean enabled = false;
    private volatile double targetWeight = 0.20;
    private volatile int tranches = 3;
    private volatile double stepAtr = 0.5;
    private volatile StopBasis stopBasis = StopBasis.FIRST_ENTRY;
    private volatile IndexGate indexGate = IndexGate.RULE;
    private volatile int indexMaPeriod = 120;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getTargetWeight() { return targetWeight; }
    public void setTargetWeight(double targetWeight) { this.targetWeight = targetWeight; }

    public int getTranches() { return tranches; }
    public void setTranches(int tranches) { this.tranches = tranches; }

    public double getStepAtr() { return stepAtr; }
    public void setStepAtr(double stepAtr) { this.stepAtr = stepAtr; }

    public StopBasis getStopBasis() { return stopBasis; }
    public void setStopBasis(StopBasis stopBasis) { this.stopBasis = stopBasis; }

    public IndexGate getIndexGate() { return indexGate; }
    public void setIndexGate(IndexGate indexGate) { this.indexGate = indexGate; }

    public int getIndexMaPeriod() { return indexMaPeriod; }
    public void setIndexMaPeriod(int indexMaPeriod) { this.indexMaPeriod = indexMaPeriod; }

    /** 프로필 사이 기본값 복원 — 다른 모드/프로필로의 누출 방지 */
    public void resetDefaults() {
        this.enabled = false;
        this.targetWeight = 0.20;
        this.tranches = 3;
        this.stepAtr = 0.5;
        this.stopBasis = StopBasis.FIRST_ENTRY;
        this.indexGate = IndexGate.RULE;
        this.indexMaPeriod = 120;
    }
}
