package com.trading.risk;

import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OpportunityCostLogger {

    private static final Logger log = LoggerFactory.getLogger(OpportunityCostLogger.class);

    /**
     * 차단 이력 기록기. null일 수 있다 — 단위 테스트(직접 생성)와 backtest 프로필
     * (빈 자체를 만들지 않는다)에서는 DB에 남기지 않는다. <b>판정에는 절대 쓰지 않는다.</b>
     */
    private final RiskBlockRecorder recorder;

    /** 단위 테스트·기록 없이 쓰는 경우 */
    public OpportunityCostLogger() {
        this.recorder = null;
    }

    /** 테스트에서 기록기를 직접 끼울 때 */
    public OpportunityCostLogger(RiskBlockRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * 스프링 주입 경로. backtest 프로필에는 기록기 빈이 없으므로 {@code ObjectProvider}로
     * 받아 있으면 쓰고 없으면 그냥 로그만 남긴다 ({@code TradingStatusManager}와 같은 이유).
     */
    @Autowired
    public OpportunityCostLogger(ObjectProvider<RiskBlockRecorder> recorderProvider) {
        this.recorder = recorderProvider.getIfAvailable();
    }

    // ADR 2.6: 드롭된 신호는 침묵 처리하지 않고 무조건 기록한다.
    public void logDropped(Signal signal, String reason) {
        log.warn("[기회비용] 신호 드롭 — stockCode={}, strategy={}, reason={}",
                signal.getStockCode(), signal.getStrategyName(), reason);
        recordQuietly(signal, reason);
    }

    /**
     * DB 이력 추가 (2026-09-22). <b>위의 로그 출력은 그대로 두고 덧붙이기만 한다</b> —
     * 로그가 정본이고 DB는 화면에서 되짚기 위한 사본이다.
     * 기록 실패는 삼킨다 (기록기 안쪽에도 catch가 있지만 한 겹 더 둔다).
     */
    private void recordQuietly(Signal signal, String reason) {
        if (recorder == null) return;
        try {
            recorder.record(signal, reason);
        } catch (Exception e) {
            log.warn("[기회비용] 차단 이력 기록 실패 (매매 판정에는 영향 없음) — stockCode={}: {}",
                    signal.getStockCode(), e.toString());
        }
    }
}
