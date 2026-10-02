package com.trading.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class TradingStatusManager {

    private static final Logger log = LoggerFactory.getLogger(TradingStatusManager.class);

    private final AtomicReference<TradingMode> mode = new AtomicReference<>(TradingMode.RUNNING);

    /**
     * 전환 이력 기록기. null일 수 있다 — 단위 테스트(직접 생성)와 backtest 프로필
     * (빈 자체를 만들지 않는다)에서는 이력을 남기지 않는다.
     * <b>판정에는 절대 쓰지 않는다</b> — 있으면 남기고, 없으면 그냥 안 남긴다.
     */
    private final ModeTransitionRecorder recorder;

    /** 단위 테스트·이력 없이 쓰는 경우 */
    public TradingStatusManager() {
        this.recorder = null;
    }

    /** 테스트에서 기록기를 직접 끼울 때 */
    public TradingStatusManager(ModeTransitionRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * 스프링 주입 경로. {@code ObjectProvider}를 쓰는 이유는 <b>기록기 빈이 없을 수도
     * 있기 때문</b>이다 — backtest 프로필에는 만들지 않는다. 빈이 없으면 없는 대로
     * 기동해야지, 이력 기능 때문에 애플리케이션이 못 뜨면 안 된다.
     */
    @Autowired
    public TradingStatusManager(ObjectProvider<ModeTransitionRecorder> recorderProvider) {
        this.recorder = recorderProvider.getIfAvailable();
    }

    public TradingMode getCurrentMode() {
        return mode.get();
    }

    public void changeMode(TradingMode newMode) {
        TradingMode previous = mode.getAndSet(newMode);
        if (previous != newMode) {
            log.warn("[TradingStatusManager] 모드 전환: {} → {}", previous, newMode);
            recordQuietly(previous, newMode);
        }
    }

    /**
     * 이력 기록 (2026-09-22 추가). <b>모드는 이미 위에서 바뀐 뒤</b>라, 여기서 무슨 일이 나도
     * 전환은 되돌아가지 않는다.
     *
     * <p>try/catch가 여기에도 있는 이유: 기록기 안쪽에도 catch가 있지만, 그것마저 새는
     * 경우(기록기 빈이 프록시 단계에서 터지는 등)까지 받아내기 위한 두 번째 그물이다.
     * 실제 INSERT는 {@code ModeTransitionWriter}({@code REQUIRES_NEW})가 독립 트랜잭션으로
     * 처리하므로 <b>여기서 실패해도 바깥 트랜잭션은 오염되지 않는다</b> (감사 24_audit M-1).
     *
     * <p>사유(reason)는 null로 넘긴다 — {@code changeMode}가 사유를 받지 않는다.
     * 사유를 채우려면 청산·SAFE_MODE 판정의 심장인 호출부 9곳을 고쳐야 해서 일부러 두었다.
     */
    private void recordQuietly(TradingMode previous, TradingMode newMode) {
        if (recorder == null) return;
        try {
            recorder.record(previous, newMode, null);
        } catch (Exception e) {
            log.warn("[TradingStatusManager] 전환 이력 기록 실패 (전환 자체는 정상) — {} → {}: {}",
                    previous, newMode, e.toString());
        }
    }
}
