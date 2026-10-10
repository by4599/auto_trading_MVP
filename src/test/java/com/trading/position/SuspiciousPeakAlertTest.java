package com.trading.position;

import com.trading.NotificationService;
import com.trading.backtest.MutableClock;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.PeakEquityCalibrator.SuspiciousPeakReason;
import com.trading.risk.RiskLimitsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "의심스러운 고점 거부" 텔레그램 — 하루 최대 1회, 잔고 불일치와 기존 상한 거부(isImplausible)가 같은 몫을 쓴다.
 * 2026-09-11 상한 거부(17,047,935원)는 WARN 로그 3줄뿐이라 아무도 몰랐다. 전송은 감시 스레드 밖에서 한다.
 */
@DisplayName("의심스러운 고점 거부 경고 — 하루 1회 · 쉬운 말 · 감시 스레드 밖 전송")
class SuspiciousPeakAlertTest {

    private static final double PEAK = 10_088_806;
    private static final double SMALLEST_DANGER = 10_604_158;
    private static final double SEP_11_SPIKE = 17_047_935;     // 상한(11,602,127원) 위 — 실측 관측값
    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);   // 월요일
    private static final String REJECT = "최고 기록으로 인정하지 않았습니다";

    private PositionManager positionManager;
    private NotificationService notifier;
    private MutableClock clock;
    private EvidenceBasedPeakEquityCalibrator calibrator;
    private ShadowPortfolio sut;

    @BeforeEach
    void setUp() {
        positionManager = mock(PositionManager.class);
        notifier = mock(NotificationService.class);
        PortfolioStateRepository stateRepository = mock(PortfolioStateRepository.class);
        DailyEquityRepository dailyEquityRepository = mock(DailyEquityRepository.class);
        when(dailyEquityRepository.findMaxStartEquity()).thenReturn(PEAK);
        clock = new MutableClock(ZonedDateTime.of(DAY, LocalTime.of(10, 0), ZoneId.of("Asia/Seoul")).toInstant());
        calibrator = new EvidenceBasedPeakEquityCalibrator(dailyEquityRepository, stateRepository,
                new RiskLimitsProperties(), notifier, clock,
                new MarketCalendarService(new MarketCalendarProperties(), clock));
        when(stateRepository.findById(PortfolioState.KEY_PEAK_EQUITY))
                .thenReturn(Optional.of(PortfolioState.of(PortfolioState.KEY_PEAK_EQUITY, PEAK)));
        sut = new ShadowPortfolio(positionManager, stateRepository, calibrator, clock);
        sut.restore();
    }

    private void tickWith(Account account) {
        when(positionManager.snapshotAccount()).thenReturn(account);
        sut.tick();
    }

    private void at(LocalDate date, int hour, int minute) {
        clock.setTo(date, LocalTime.of(hour, minute));
    }

    @Test
    @DisplayName("잔고 불일치 거부는 하루에 한 번만 알린다")
    void mismatch_alerts_once_per_day() {
        tickWith(ShadowPortfolioEquityCheckTest.mismatched(SMALLEST_DANGER));
        at(DAY, 10, 20);
        sut.tick();
        at(DAY, 14, 0);
        sut.tick();

        verify(notifier, timeout(2_000).times(1)).sendCritical(contains(REJECT));
        verify(notifier, after(200).times(1)).sendCritical(contains(REJECT));
    }

    @Test
    @DisplayName("다음 거래일에는 다시 한 번 알린다")
    void next_day_alerts_again() {
        tickWith(ShadowPortfolioEquityCheckTest.mismatched(SMALLEST_DANGER));
        at(DAY.plusDays(1), 9, 30);
        sut.tick();

        verify(notifier, timeout(2_000).times(2)).sendCritical(contains(REJECT));
    }

    @Test
    @DisplayName("문구는 쉬운 말 — 원래 숫자는 로그에 남겼다고 알리고, 증권사값·계산값을 함께 보여준다")
    void mismatch_message_is_plain_korean() {
        tickWith(ShadowPortfolioEquityCheckTest.mismatched(SMALLEST_DANGER));

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(notifier, timeout(2_000).times(1)).sendCritical(msg.capture());
        assertThat(msg.getValue())
                .contains("잔고 숫자가 서로 맞지 않아", REJECT, "원래 숫자는 로그에 남겼습니다")
                .contains("10,604,158", "10,088,806");
    }

    @Test
    @DisplayName("기존 상한 거부(9-11 17,047,935원 같은 값)도 같은 경고로 알린다 — WARN 3줄로 묻히지 않게")
    void implausible_rejection_alerts_too() {
        tickWith(new Account(SEP_11_SPIKE, 0.0, 0, List.of()));

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(notifier, timeout(2_000).times(1)).sendCritical(msg.capture());
        assertThat(msg.getValue()).contains(REJECT, "17,047,935");
        assertThat(sut.getPeakEquity()).isEqualTo(PEAK);
    }

    @Test
    @DisplayName("같은 날 불일치 거부 뒤 상한 거부가 와도 한 번뿐 — 두 거부가 하루 몫을 함께 쓴다")
    void mismatch_and_implausible_share_the_daily_quota() {
        tickWith(ShadowPortfolioEquityCheckTest.mismatched(SMALLEST_DANGER));
        at(DAY, 11, 0);
        tickWith(new Account(SEP_11_SPIKE, 0.0, 0, List.of()));

        verify(notifier, timeout(2_000).times(1)).sendCritical(contains(REJECT));
        verify(notifier, after(200).times(1)).sendCritical(contains(REJECT));
    }

    @Test
    @DisplayName("장 밖에서는 보내지 않고 하루 몫도 쓰지 않는다 — 텔레그램은 장 밖이면 버리기 때문")
    void out_of_hours_keeps_the_daily_quota() {
        at(DAY, 8, 45);
        calibrator.notifySuspiciousPeakRejected(SuspiciousPeakReason.BALANCE_MISMATCH, SMALLEST_DANGER, PEAK,
                ShadowPortfolioEquityCheckTest.mismatched(SMALLEST_DANGER).getEquityCheck());
        verify(notifier, after(200).never()).sendCritical(anyString());

        at(DAY, 10, 0);
        tickWith(ShadowPortfolioEquityCheckTest.mismatched(SMALLEST_DANGER));
        verify(notifier, timeout(2_000).times(1)).sendCritical(contains(REJECT));
    }

    @Test
    @DisplayName("전송은 감시 스레드가 아니라 전용 스레드에서 — 1초 루프를 붙잡지 않는다")
    void send_runs_off_the_caller_thread() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        AtomicReference<String> senderThread = new AtomicReference<>();
        doAnswer(inv -> {
            senderThread.set(Thread.currentThread().getName());
            sent.countDown();
            return null;
        }).when(notifier).sendCritical(contains(REJECT));

        tickWith(ShadowPortfolioEquityCheckTest.mismatched(SMALLEST_DANGER));

        assertThat(sent.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(senderThread.get()).isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("백테스트 구현체(무검증)는 거부해도 아무것도 보내지 않는다")
    void backtest_calibrator_never_alerts() {
        ShadowPortfolio backtest = new ShadowPortfolio(
                positionManager, mock(PortfolioStateRepository.class), new NoOpPeakEquityCalibrator(), clock);
        when(positionManager.snapshotAccount()).thenReturn(ShadowPortfolioEquityCheckTest.mismatched(SMALLEST_DANGER));

        backtest.tick();

        assertThat(backtest.getPeakEquity()).isZero();
        verify(notifier, after(200).never()).sendCritical(anyString());
    }
}
