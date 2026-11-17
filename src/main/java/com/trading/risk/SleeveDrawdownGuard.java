package com.trading.risk;

import com.trading.NotificationService;
import com.trading.bucket.SleeveDrawdownProperties;
import com.trading.bucket.SleeveEquity;
import com.trading.bucket.SleeveStateStore;
import com.trading.bucket.StrategyBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;

/**
 * 칸 낙폭 판정의 핵심 (ADR-001 §2.2 개정 2026-08-07) — 최고 기록, 연속 확인, 잠금, 사람만 하는 해제.
 *
 * <ul>
 *   <li><b>최고 기록</b>: 칸마다 portfolio_state에 영속(재시작 후 유지), 잠기지 않은 동안 단조 증가.
 *       처음에는 배분금. 1분 간격 <b>연속 2회</b> 확인된 값(둘 중 낮은 값)으로만 올린다 — 한 번 튄 값이
 *       최고 기록을 영구히 오염시키면 그 뒤로 가짜 낙폭이 생긴다(CLAUDE.md 결함 6, 계좌 전고점 오염 사고).</li>
 *   <li><b>배분금 변경</b>: 설정이 바뀌면 최고 기록도 같은 금액만큼 옮긴다 — 배분금을 줄였다고 칸이 잠기면 안 된다.</li>
 *   <li><b>발동</b>: 낙폭 = (최고 − 현재)/최고가 한도에 닿는 회차가 <b>연속 2번</b>이면 잠근다(ADR "도달 시" → &gt;=).
 *       보류된 회차는 연속을 끊는다 — 하루 건너 두 번은 연속이 아니다.</li>
 *   <li><b>해제</b>: 사람만(REST). 최고 기록을 지금 칸 자산으로 다시 잡는다 — 안 그러면 풀자마자 다시 잠긴다.</li>
 * </ul>
 * 감시기 스레드와 조회·해제 API 스레드가 함께 부르므로 공개 메서드는 전부 {@code synchronized}다.
 */
@Component
@Profile("paper")
public class SleeveDrawdownGuard {

    private static final Logger log = LoggerFactory.getLogger(SleeveDrawdownGuard.class);

    /** 한도 초과를 몇 회 연속 봐야 잠그나 (감시 주기 1분) */
    public static final int CONFIRMATIONS = 2;

    public enum Outcome { WITHIN_LIMIT, BREACH_UNCONFIRMED, LOCKED_NOW, ALREADY_LOCKED }

    public record Decision(Outcome outcome, double peak, double drawdown, double limit) {}

    private final SleeveStateStore store;
    private final SleeveDrawdownProperties limits;
    private final NotificationService notifier;
    private final Clock clock;

    private final Map<StrategyBucket, Integer> breachStreak = new EnumMap<>(StrategyBucket.class);
    private final Map<StrategyBucket, Double> lastEquity = new EnumMap<>(StrategyBucket.class);

    public SleeveDrawdownGuard(SleeveStateStore store, SleeveDrawdownProperties limits,
                               NotificationService notifier, Clock clock) {
        this.store = store;
        this.limits = limits;
        this.notifier = notifier;
        this.clock = clock;
    }

    public synchronized boolean isLocked(StrategyBucket bucket) {
        return store.isLocked(bucket);
    }

    public synchronized Decision evaluate(SleeveEquity sleeve) {
        StrategyBucket bucket = sleeve.bucket();
        double limit = limits.limitOf(bucket);
        if (store.isLocked(bucket)) {
            return new Decision(Outcome.ALREADY_LOCKED, Double.NaN, Double.NaN, limit);
        }

        double equity = sleeve.equity();
        double peak = confirmedPeak(bucket, sleeve.allocation(), equity);
        double drawdown = (peak - equity) / peak;
        if (drawdown < limit) {
            breachStreak.remove(bucket);
            return new Decision(Outcome.WITHIN_LIMIT, peak, drawdown, limit);
        }

        int streak = breachStreak.merge(bucket, 1, Integer::sum);
        if (streak < CONFIRMATIONS) {
            log.warn("[칸 낙폭] {} 한도 초과 {}회째(확인 대기) — 최고 {}원 → 지금 {}원, 낙폭 {}% (한도 {}%)",
                    bucket, streak, Math.round(peak), Math.round(equity), pct(drawdown), pct(limit));
            return new Decision(Outcome.BREACH_UNCONFIRMED, peak, drawdown, limit);
        }
        lock(sleeve, peak, drawdown, limit);
        return new Decision(Outcome.LOCKED_NOW, peak, drawdown, limit);
    }

    /** 조회 화면용 — 지금 쓰일 최고 기록(배분금 변경 보정 포함). 저장하지 않는다 */
    public synchronized double displayPeak(StrategyBucket bucket, double allocation) {
        return rebased(bucket, store.peak(bucket).orElse(null), allocation, false).value();
    }

    /** 보류·오류 회차 — 그 칸의 연속 확인을 처음부터 다시 */
    public synchronized void resetStreak(StrategyBucket bucket) {
        breachStreak.remove(bucket);
        lastEquity.remove(bucket);
    }

    /** 장 밖·운전 모드·낡은 잔고로 회차 전체를 건너뛸 때 */
    public synchronized void resetAllStreaks() {
        breachStreak.clear();
        lastEquity.clear();
    }

    /**
     * 사람의 해제. 잠겨 있지 않으면 아무것도 바꾸지 않고 false.
     * @param current 지금 칸 자산 — 새 최고 기록이 된다
     */
    public synchronized boolean unlock(SleeveEquity current, String reason) {
        StrategyBucket bucket = current.bucket();
        if (!store.isLocked(bucket)) return false;

        store.unlock(bucket, new SleeveStateStore.Peak(current.equity(), current.allocation()));
        resetStreak(bucket);
        log.warn("[칸 낙폭] {} 칸 잠금 해제(사람) — 최고 기록을 지금 칸 자산 {}원으로 다시 잡음. 사유: {}",
                bucket, Math.round(current.equity()), reason);
        notifyQuietly(SleeveDrawdownMessages.unlocked(current, limits.limitOf(bucket), reason));
        return true;
    }

    // ── 내부 ─────────────────────────────────────────────────────────────────

    /** 저장된 최고 기록(없으면 배분금) → 배분금 변경 보정 → 연속 2회 확인된 값으로만 올리고 저장 */
    private double confirmedPeak(StrategyBucket bucket, double allocation, double equity) {
        SleeveStateStore.Peak stored = store.peak(bucket).orElse(null);
        SleeveStateStore.Peak peak = rebased(bucket, stored, allocation, true);
        Double previous = lastEquity.put(bucket, equity);
        if (previous != null && Math.min(previous, equity) > peak.value()) {
            double confirmed = Math.min(previous, equity);
            log.info("[칸 낙폭] {} 최고 기록 갱신 {} → {}원 (연속 2회 확인)",
                    bucket, Math.round(peak.value()), Math.round(confirmed));
            peak = new SleeveStateStore.Peak(confirmed, allocation);
        }
        if (!peak.equals(stored)) store.savePeak(bucket, peak);
        return peak.value();
    }

    /**
     * 처음이면 배분금에서 시작. 배분금이 바뀌었으면 최고 기록을 같은 금액만큼 옮긴다.
     * @param forget true면 옮길 때 직전 관측값도 버린다(옛 배분금 기준 값이라 비교할 수 없다)
     */
    private SleeveStateStore.Peak rebased(StrategyBucket bucket, SleeveStateStore.Peak stored,
                                          double allocation, boolean forget) {
        if (stored == null || !(stored.value() > 0)) {
            return new SleeveStateStore.Peak(allocation, allocation);
        }
        if (Double.isNaN(stored.basis()) || stored.basis() == allocation) {
            return new SleeveStateStore.Peak(stored.value(), allocation);
        }
        double shifted = stored.value() + (allocation - stored.basis());
        if (forget) {
            lastEquity.remove(bucket);
            log.warn("[칸 낙폭] {} 배분금 {} → {}원 변경 — 최고 기록도 {} → {}원으로 옮긴다",
                    bucket, Math.round(stored.basis()), Math.round(allocation),
                    Math.round(stored.value()), Math.round(shifted));
        }
        return new SleeveStateStore.Peak(shifted > 0 ? shifted : allocation, allocation);
    }

    /**
     * 잠금 저장 → 알림. 저장이 실패하면 예외가 올라가 매도도 하지 않는다 — 감시기가 그 칸의 연속 확인을
     * 처음부터 다시 세므로 다음 2회 연속 초과에서 다시 잠근다(잠금 없이 팔면 잠금 룰이 매수를 못 막는다).
     */
    private void lock(SleeveEquity sleeve, double peak, double drawdown, double limit) {
        StrategyBucket bucket = sleeve.bucket();
        store.lock(bucket, new SleeveStateStore.LockState(true, clock.instant(), drawdown, sleeve.equity(), limit));
        resetStreak(bucket);
        log.error("[칸 낙폭] {} 칸 잠금 — 최고 {}원 → 지금 {}원, 낙폭 {}% ≥ 한도 {}% ({}회 연속). 신규 매수 중지 + 보유 정리",
                bucket, Math.round(peak), Math.round(sleeve.equity()), pct(drawdown), pct(limit), CONFIRMATIONS);
        notifyQuietly(SleeveDrawdownMessages.locked(sleeve, peak, drawdown, limit));
    }

    /** 알림 실패가 매도를 막으면 안 된다 */
    private void notifyQuietly(String message) {
        try {
            notifier.sendCritical(message);
        } catch (RuntimeException e) {
            log.warn("[칸 낙폭] 텔레그램 알림 실패 — 계속 진행: {}", e.toString());
        }
    }

    private static String pct(double ratio) {
        return String.format("%.2f", ratio * 100);
    }
}
