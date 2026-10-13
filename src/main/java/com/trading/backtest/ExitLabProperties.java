package com.trading.backtest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Exit Lab(BACKTEST-DESIGN §14) 출구 프로필 스위치 — 백테스트 전용 mutable holder.
 *
 * 손익비 재설계 실험에서 오케스트레이터가 프로필마다 값을 세팅한다(필터 A/B와 동일 패턴).
 * 기본값은 기존 동작과 동일하게 둔다: 타임컷 ON, 최대 보유 무제한(=0). 따라서 exit-lab
 * 모드가 아닌 다른 백테스트 실행(full/ma-breakout/scalping/events/smoke)은 영향받지 않는다.
 *
 * @Profile("backtest") — paper/real에는 존재하지 않는다(라이브는 15:15 타임컷 고정).
 */
@Component
@Profile("backtest")
public class ExitLabProperties {

    /** true면 15:15 전량매도(당일 청산). false면 포지션을 다음 거래일로 이월. */
    private volatile boolean timecutEnabled = true;

    /** 0=무제한. >0이면 진입 후 N거래일 경과 시 종가 강제청산(무한 보유 방지·타임 손절). */
    private volatile int maxHoldDays = 0;

    /** 0=기존 동작(ATR 손절선). >0이면 손절 레벨 = 진입가 × (1−stopPct) 고정% 손절 (§17). */
    private volatile double stopPct = 0;

    /** 0=목표 익절 없음. >0이면 목표 = 진입가 × (1+targetPct) 도달 시 익절 (§17). */
    private volatile double targetPct = 0;

    public boolean isTimecutEnabled() { return timecutEnabled; }
    public void setTimecutEnabled(boolean timecutEnabled) { this.timecutEnabled = timecutEnabled; }

    public int getMaxHoldDays() { return maxHoldDays; }
    public void setMaxHoldDays(int maxHoldDays) { this.maxHoldDays = maxHoldDays; }

    public double getStopPct() { return stopPct; }
    public void setStopPct(double stopPct) { this.stopPct = stopPct; }

    public double getTargetPct() { return targetPct; }
    public void setTargetPct(double targetPct) { this.targetPct = targetPct; }

    /** 고정% 브래킷 출구를 쓰는 실행인가 — false면 손절 판정이 기존 ATR 경로 그대로다 */
    public boolean isBracketStop() { return stopPct > 0; }

    /** 프로필 스윕 사이 기본값 복원 — 다른 모드/런으로의 누출 방지 */
    public void resetDefaults() {
        this.timecutEnabled = true;
        this.maxHoldDays = 0;
        this.stopPct = 0;
        this.targetPct = 0;
    }
}
