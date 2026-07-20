package com.trading.scheduler;

import com.trading.market.MarketCalendarService;
import com.trading.position.PortfolioState;
import com.trading.position.PortfolioStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 모의투자 "연속 5거래일 무중단 실행" 검증 기록 장치 (릴리즈 체크리스트 검증 항목).
 *
 * 판정 규칙 — 거래일마다 15:25 KST(타임컷 15:15·신규매수 차단 15:20 이후)에 1회:
 *   - 앱이 그 시각까지 살아 있고(스케줄이 돌았다는 사실 자체),
 *   - 당일 개장 시각 이전부터 켜져 있었으면(appStartedAt ≤ 개장) → 그날은 "무중단 가동일"
 *   - 직전 거래일에도 기록이 있으면 연속일수 +1, 공백이 있으면 1부터 다시
 *   - 개장 후에 켜졌으면 그날은 인정하지 않고 0으로 리셋
 *
 * 기록은 portfolio_state(재시작 생존)에 저장되고, 현재 연속일수는
 * GET /api/trading/run-streak 로 조회한다. 15:25에 앱이 꺼져 있으면 기록이
 * 남지 않으므로 다음 기록 시 자동으로 연속이 끊긴다 — 별도 감시 불필요.
 */
@Component
@Profile("paper")
public class RunStreakRecorder {

    private static final Logger log = LoggerFactory.getLogger(RunStreakRecorder.class);

    public static final int GOAL_DAYS = 5;

    private final PortfolioStateRepository stateRepository;
    private final MarketCalendarService calendar;
    private final Clock clock;
    private final LocalDateTime appStartedAt;

    public RunStreakRecorder(PortfolioStateRepository stateRepository,
                             MarketCalendarService calendar,
                             Clock clock) {
        this.stateRepository = stateRepository;
        this.calendar = calendar;
        this.clock = clock;
        this.appStartedAt = LocalDateTime.now(clock);  // 빈 생성 시각 ≈ 앱 기동 시각
    }

    @Scheduled(cron = "0 25 15 * * MON-FRI", zone = "Asia/Seoul")
    public void record() {
        recordFor(LocalDate.now(clock));
    }

    @Transactional
    void recordFor(LocalDate today) {
        if (calendar.isHoliday(today)) {
            return;  // cron은 KRX 특정 휴장일(설날 등)을 모른다 — 방어 가드
        }

        double todayKey = toKey(today);
        Double lastDate = read(PortfolioState.KEY_RUN_STREAK_LAST_DATE);
        if (lastDate != null && lastDate == todayKey) {
            return;  // 같은 날 중복 실행 방지
        }

        int streak = judgeStreak(today, lastDate);
        stateRepository.save(PortfolioState.of(PortfolioState.KEY_RUN_STREAK_DAYS, streak));
        stateRepository.save(PortfolioState.of(PortfolioState.KEY_RUN_STREAK_LAST_DATE, todayKey));

        if (streak >= GOAL_DAYS) {
            log.info("[가동기록] 🎉 연속 무중단 {}거래일 달성 — 릴리즈 검증 항목 충족", streak);
        } else {
            log.info("[가동기록] 연속 무중단 {}거래일 (목표 {}일, 기동 {})",
                    streak, GOAL_DAYS, appStartedAt);
        }
    }

    private int judgeStreak(LocalDate today, Double lastDate) {
        boolean upSinceOpen = !appStartedAt.isAfter(today.atTime(calendar.openTime(today)));
        if (!upSinceOpen) {
            log.warn("[가동기록] 개장({}) 이후 기동({}) — 오늘은 무중단 미인정, 연속 기록 리셋",
                    calendar.openTime(today), appStartedAt);
            return 0;
        }
        boolean continues = lastDate != null && lastDate == toKey(previousTradingDay(today));
        if (!continues) return 1;
        return (int) readOrZero(PortfolioState.KEY_RUN_STREAK_DAYS) + 1;
    }

    private LocalDate previousTradingDay(LocalDate date) {
        LocalDate d = date.minusDays(1);
        while (calendar.isHoliday(d)) d = d.minusDays(1);
        return d;
    }

    private static double toKey(LocalDate d) {
        return d.getYear() * 10_000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    private Double read(String key) {
        return stateRepository.findById(key).map(PortfolioState::getStateValue).orElse(null);
    }

    private double readOrZero(String key) {
        Double v = read(key);
        return v == null ? 0.0 : v;
    }
}
