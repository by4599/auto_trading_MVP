package com.trading.risk;

import com.trading.NotificationService;
import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.PositionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 장중 잔고 연속 낡음 알림 (40_audit L-3, 2026-10-12 사고 대응).
 *
 * <p>잔고 조회만 계속 실패하면 운전 상태는 RUNNING인 채 신규 매수가 막히고({@link StaleAccountBuyGuardRule})
 * 청산·손절 감시도 판정을 건너뛴다 — 그런데 아무 알림이 없었다. 장중에 낡은 스냅샷이 {@link #THRESHOLD} 이상
 * 이어지면 한 번 알리고, 회복하면 한 번 알린다. 매매 경로는 건드리지 않는다(읽기 + 알림만).
 */
@Component
@Profile("paper")
public class StaleBalanceWatchdog {

    private static final Logger log = LoggerFactory.getLogger(StaleBalanceWatchdog.class);

    static final Duration THRESHOLD = Duration.ofMinutes(10);

    private final PositionManager positionManager;
    private final NotificationService notifier;
    private final MarketCalendarService marketCalendar;
    private final KisProperties kisProperties;
    private final Clock clock;

    private LocalDateTime staleSince;
    private boolean alerted;

    public StaleBalanceWatchdog(PositionManager positionManager,
                                NotificationService notifier,
                                MarketCalendarService marketCalendar,
                                KisProperties kisProperties,
                                Clock clock) {
        this.positionManager = positionManager;
        this.notifier = notifier;
        this.marketCalendar = marketCalendar;
        this.kisProperties = kisProperties;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void check() {
        if (!kisProperties.isConfigured()) return;
        LocalDateTime now = LocalDateTime.now(clock);
        if (!marketCalendar.isDuringMarketHours(now)) {   // 장 밖의 낡음은 세지 않는다 (알림도 장중에만 나간다)
            reset();
            return;
        }
        boolean fresh;
        try {
            fresh = positionManager.snapshotAccount().isFresh();
        } catch (Exception e) {
            fresh = false;
        }
        observe(fresh, now);
    }

    synchronized void observe(boolean fresh, LocalDateTime now) {
        if (fresh) {
            if (alerted) {
                log.info("[StaleBalance] 잔고 조회 회복 — 낡음 시작 {}", staleSince);
                notifier.sendCritical("✅ 잔고 조회가 회복됐습니다 — 신규 매수·손절·청산 감시가 다시 정상 동작합니다");
            }
            reset();
            return;
        }
        if (staleSince == null) {
            staleSince = now;
            return;
        }
        if (!alerted && !Duration.between(staleSince, now).minus(THRESHOLD).isNegative()) {
            alerted = true;
            log.error("[StaleBalance] 잔고 스냅샷이 {}부터 계속 낡음 — 알림 발송", staleSince);
            notifier.sendCritical(String.format(
                    "⚠️ 잔고 조회가 %d분 넘게 실패 중입니다 (%s부터) — 신규 매수가 막히고 손절·청산 감시가 멈춰 있습니다. "
                            + "계좌·API 상태를 확인하세요", THRESHOLD.toMinutes(), staleSince.toLocalTime().withNano(0)));
        }
    }

    private synchronized void reset() {
        staleSince = null;
        alerted = false;
    }
}
