package com.trading.scheduler;

import com.trading.NotificationService;
import com.trading.control.DrillOperations;
import com.trading.market.MarketCalendarService;
import com.trading.position.PortfolioState;
import com.trading.position.PortfolioStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 예약 청산 리허설 — 사람이 그 시각에 버튼을 누르지 못해도 훈련이 진행되게 한다.
 *
 * 한 번의 리허설은 두 틱에 걸쳐 진행된다:
 *   1틱: 보유가 없으면 리허설용 매수를 접수하고 끝낸다 (체결은 3초 주기 체결확인이 반영)
 *   2틱: 보유가 확인되면 강제청산을 개시한다
 * 기다리며 스레드를 붙잡지 않으므로 체결이 늦어도 다음 틱이 이어받는다.
 *
 * 안전 경계:
 *   - 기본 꺼짐, 모의(paper) 프로필 전용, 예약일이 오늘일 때만 동작
 *   - 개시~기한 창 안에서만, 거래일에만
 *   - 매수·청산 각각 하루 1회 표시를 남겨 중복 발동을 막는다
 *   - 실제 조작은 DrillOperations 관문을 지난다 (리스크 룰이 막으면 그대로 멈춘다)
 */
@Component
@Profile("paper")
public class LiquidationDrillScheduler {

    private static final Logger log = LoggerFactory.getLogger(LiquidationDrillScheduler.class);

    private final DrillProperties props;
    private final DrillOperations drill;
    private final MarketCalendarService calendar;
    private final PortfolioStateRepository stateRepository;
    private final NotificationService notifier;
    private final Clock clock;

    public LiquidationDrillScheduler(DrillProperties props,
                                     DrillOperations drill,
                                     MarketCalendarService calendar,
                                     PortfolioStateRepository stateRepository,
                                     NotificationService notifier,
                                     Clock clock) {
        this.props = props;
        this.drill = drill;
        this.calendar = calendar;
        this.stateRepository = stateRepository;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void tick() {
        if (!props.isEnabled() || props.getDate() == null) return;

        LocalDate today = LocalDate.now(clock);
        if (!today.equals(props.getDate()))   return;
        if (!calendar.isTradingDay(today))    return;
        if (isDone(PortfolioState.KEY_DRILL_DONE_DATE, today)) return;

        LocalTime now = LocalTime.now(clock);
        if (now.isBefore(props.getAt()) || now.isAfter(props.getDeadline())) return;

        try {
            runOnce(today);
        } catch (RuntimeException e) {
            // 다음 틱이 다시 시도한다 — 예약 작업이 예외로 조용히 죽지 않게 한다
            log.error("[예약 리허설] 실행 중 예외 — 다음 틱에 재시도", e);
        }
    }

    private void runOnce(LocalDate today) {
        if (!drill.hasAnyPosition()) {
            if (!props.isBuyIfFlat()) {
                mark(PortfolioState.KEY_DRILL_DONE_DATE, today);
                notifier.sendCritical("[예약 리허설] 보유분이 없어 건너뜁니다 (매수 옵션 꺼짐)");
                return;
            }
            if (isDone(PortfolioState.KEY_DRILL_BUY_DATE, today)) {
                log.info("[예약 리허설] 매수는 이미 접수됨 — 체결 대기 중");
                return;
            }
            mark(PortfolioState.KEY_DRILL_BUY_DATE, today);   // 접수 전에 표시 — 중복 매수 차단
            DrillOperations.Outcome bought = drill.manualBuy();
            log.warn("[예약 리허설] 매수 시도 — {}", bought.message());
            notifier.sendCritical("[예약 리허설] 포지션 확보 " + (bought.success() ? "접수" : "실패")
                    + " — " + bought.message());
            return;
        }

        mark(PortfolioState.KEY_DRILL_DONE_DATE, today);      // 개시 전에 표시 — 중복 청산 차단
        DrillOperations.Outcome result = drill.liquidate();
        log.warn("[예약 리허설] 청산 개시 — {}", result.message());
        notifier.sendCritical("[예약 리허설] 강제청산 " + (result.success() ? "개시" : "실패")
                + " — " + result.message());
    }

    private boolean isDone(String key, LocalDate today) {
        return stateRepository.findById(key)
                .map(s -> (long) s.getStateValue() == yyyymmdd(today))
                .orElse(false);
    }

    private void mark(String key, LocalDate today) {
        stateRepository.save(PortfolioState.of(key, yyyymmdd(today)));
    }

    private long yyyymmdd(LocalDate d) {
        return d.getYear() * 10000L + d.getMonthValue() * 100L + d.getDayOfMonth();
    }
}
