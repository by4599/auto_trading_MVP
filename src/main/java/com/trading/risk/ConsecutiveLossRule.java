package com.trading.risk;

import com.trading.position.Account;
import com.trading.position.TradeResultTracker;
import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

// CLAUDE.md: 연속 손실 3회 시 1시간 중지.
// consecutiveLossCount는 TradeResultTracker의 실현손익 스트릭 (F-5 해소).
@Component
public class ConsecutiveLossRule implements RiskRule {

    private static final Logger log = LoggerFactory.getLogger(ConsecutiveLossRule.class);

    private static final int    MAX_CONSECUTIVE_LOSSES = 3;
    private static final long   BLOCK_DURATION_MS      = 3_600_000L; // 1시간

    private final AtomicReference<Instant> blockedUntil = new AtomicReference<>(null);

    private final TradeResultTracker tradeResultTracker;

    public ConsecutiveLossRule(TradeResultTracker tradeResultTracker) {
        this.tradeResultTracker = tradeResultTracker;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();

        Instant until = blockedUntil.get();
        if (until != null && Instant.now().isBefore(until)) {
            long remaining = until.toEpochMilli() - Instant.now().toEpochMilli();
            return RiskResult.reject(String.format(
                    "연속 손실 %d회 — %d분 후 재개", MAX_CONSECUTIVE_LOSSES, remaining / 60_000));
        }

        int losses = account.getConsecutiveLossCount();
        if (losses >= MAX_CONSECUTIVE_LOSSES) {
            Instant resumeAt = Instant.now().plusMillis(BLOCK_DURATION_MS);
            blockedUntil.set(resumeAt);
            // 스트릭 리셋 없이는 1시간 뒤에도 count>=3이라 무기한 재차단된다
            tradeResultTracker.resetStreak();
            log.warn("[ConsecutiveLossRule] 연속 손실 {}회 달성 — 1시간 매수 중지 (스트릭 리셋)", losses);
            return RiskResult.reject(String.format("연속 손실 %d회 — 1시간 매수 중지", losses));
        }
        return RiskResult.pass();
    }
}
