package com.trading.risk;

import com.trading.bucket.BucketProperties;
import com.trading.bucket.SleeveStateStore;
import com.trading.bucket.StrategyBucket;
import com.trading.position.Account;
import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 칸 잠금 룰 (ADR-001 §2.2 슬리브별 낙폭 상한) — 낙폭 상한에 닿아 잠긴 칸의 <b>신규 매수만</b> 거부한다.
 *
 * <p>매도는 절대 막지 않는다 — 잠긴 칸의 정리 매도·손절·타임컷이 이 룰에 걸리면 손실이 커진다
 * (2026-09-12 GlobalEquityStopRule이 매도까지 막던 사고와 같은 함정). 다른 칸은 그대로 산다.
 * 잠금은 {@code SleeveDrawdownMonitor}가 걸고, 사람만 푼다(REST). RiskEngine은 수정하지 않는다 —
 * {@code @Component}만으로 자동 주입된다.
 *
 * <p>잠금 상태를 못 읽으면 매수를 보류한다(fail-closed) — 잠긴 칸이 DB 오류 한 번으로 다시 사기 시작하면 안 된다.
 * 칸 나누기가 꺼져 있으면(백테스트 등) 무조건 통과한다. 사유의 "칸 손실 상한"은 진단 화면이 룰을 알아보는 고유 문구다.
 */
@Component
@Profile("!backtest")
public class SleeveLockRule implements RiskRule {

    private static final Logger log = LoggerFactory.getLogger(SleeveLockRule.class);

    static final String LOCKED = "칸 손실 상한 도달로 잠김";
    static final String UNREADABLE = "칸 손실 상한 잠금 상태를 확인하지 못함 — 신규 매수 보류";

    private final BucketProperties buckets;
    private final SleeveStateStore store;

    public SleeveLockRule(BucketProperties buckets, SleeveStateStore store) {
        this.buckets = buckets;
        this.store = store;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!buckets.isEnabled() || !signal.isBuy()) return RiskResult.pass();

        StrategyBucket bucket = StrategyBucket.orDefault(signal.getBucket());
        SleeveStateStore.LockState lock;
        try {
            lock = store.lockState(bucket);
        } catch (RuntimeException e) {
            log.warn("[칸 잠금] {} 잠금 상태 조회 실패 — 매수 보류: {}", bucket, e.toString());
            return RiskResult.reject(UNREADABLE + ": " + bucket);
        }
        if (!lock.locked()) return RiskResult.pass();

        return RiskResult.reject(String.format(
                "%s: %s — 낙폭 %.1f%% (한도 %.0f%%), 사람이 해제할 때까지 신규 매수 금지",
                LOCKED, bucket, lock.drawdown() * 100, lock.limit() * 100));
    }
}
