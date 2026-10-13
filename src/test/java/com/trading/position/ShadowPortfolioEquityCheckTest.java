package com.trading.position;

import ch.qos.logback.classic.Level;
import com.trading.NotificationService;
import com.trading.backtest.MutableClock;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.risk.RiskLimitsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 장중 전고점 튐 방어 — 잔고 대조가 불일치면 최고 기록으로 인정하지 않는다 (결함 6, 2026-10-11).
 *
 * <p>관측된 오염 2건은 장 밖이었고 그 길은 10-01에 막혔다(ShadowPortfolioTicker). 이 시험은 장중에 튀는 경우다.
 * paper 낙폭 한도 8%에서는 전고점이 +5.11%(10,604,158원)만 튀어도 매 개장 강제청산이 나는데, 기존 상한
 * (실측 최대 10,088,806 × 1.15 = 11,602,127원)은 그 값을 통과시킨다.
 */
@DisplayName("ShadowPortfolio — 잔고 대조 불일치면 전고점 갱신 거부")
class ShadowPortfolioEquityCheckTest {

    private static final double PEAK = 10_088_806;            // 운영 DB 교정값(실측 최고)
    private static final double SMALLEST_DANGER = 10_604_158;  // 8% 한도를 부르는 최소 오염(+5.11%)
    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);   // 월요일

    private PositionManager positionManager;
    private PortfolioStateRepository stateRepository;
    private MutableClock clock;
    private ShadowPortfolio sut;

    @BeforeEach
    void setUp() {
        positionManager = mock(PositionManager.class);
        stateRepository = mock(PortfolioStateRepository.class);
        DailyEquityRepository dailyEquityRepository = mock(DailyEquityRepository.class);
        when(dailyEquityRepository.findMaxStartEquity()).thenReturn(PEAK);
        clock = new MutableClock(ZonedDateTime.of(DAY, LocalTime.of(10, 0), ZoneId.of("Asia/Seoul")).toInstant());
        MarketCalendarService calendar = new MarketCalendarService(new MarketCalendarProperties(), clock);
        EvidenceBasedPeakEquityCalibrator calibrator = new EvidenceBasedPeakEquityCalibrator(dailyEquityRepository,
                stateRepository, new RiskLimitsProperties(), mock(NotificationService.class), clock, calendar);
        when(stateRepository.findById(PortfolioState.KEY_PEAK_EQUITY))
                .thenReturn(Optional.of(PortfolioState.of(PortfolioState.KEY_PEAK_EQUITY, PEAK)));
        sut = new ShadowPortfolio(positionManager, stateRepository, calibrator, clock);
        sut.restore();
    }

    /** 증권사 총자산은 튀었고 계산값(현금 = 실측 최고, 보유 없음)은 그대로인 스냅샷 */
    static Account mismatched(double brokerTotal) {
        return new Account(brokerTotal, 0.0, 0, List.of()).withEquityCheck(
                EquityCrossCheck.evaluate(brokerTotal, OptionalDouble.of(PEAK), List.of()));
    }

    private void tickWith(Account account) {
        when(positionManager.snapshotAccount()).thenReturn(account);
        sut.tick();
    }

    private void at(int hour, int minute) {
        clock.setTo(DAY, LocalTime.of(hour, minute));
    }

    @Test
    @DisplayName("불일치면 전고점을 올리지 않는다 — 메모리값·저장값 모두 그대로")
    void mismatch_does_not_raise_or_persist() {
        tickWith(mismatched(SMALLEST_DANGER));

        assertThat(sut.getPeakEquity()).isEqualTo(PEAK);
        verify(stateRepository, never()).save(any());
    }

    @Test
    @DisplayName("판정 불가(기존 4인자 Account)면 예전처럼 갱신·저장한다")
    void unchecked_raises_as_before() {
        tickWith(new Account(10_200_000, 0.0, 0, List.of()));

        assertThat(sut.getPeakEquity()).isEqualTo(10_200_000);
        verify(stateRepository).save(any(PortfolioState.class));
    }

    @Test
    @DisplayName("일치면 예전처럼 갱신한다")
    void match_raises_as_before() {
        Account matched = new Account(10_200_000, 0.0, 0, List.of()).withEquityCheck(
                EquityCrossCheck.evaluate(10_200_000, OptionalDouble.of(10_200_000), List.of()));

        tickWith(matched);

        assertThat(sut.getPeakEquity()).isEqualTo(10_200_000);
    }

    @Nested
    @DisplayName("거부 WARN 로그")
    class RejectionLog {

        @Test
        @DisplayName("증권사값·계산값·차이를 남긴다")
        void warn_has_broker_computed_and_diff() {
            try (LogCapture logs = LogCapture.of(ShadowPortfolio.class)) {
                tickWith(mismatched(SMALLEST_DANGER));

                List<String> warn = logs.messages(Level.WARN);
                assertThat(warn).hasSize(1);
                assertThat(warn.get(0)).contains("불일치", "10604158", "10088806", "+4.86%");
            }
        }

        @Test
        @DisplayName("이어지면 처음 1회 + 10분에 최대 1회 — 생략 건수를 적는다")
        void warn_is_throttled_with_skipped_count() {
            try (LogCapture logs = LogCapture.of(ShadowPortfolio.class)) {
                tickWith(mismatched(SMALLEST_DANGER));   // 10:00 기록
                at(10, 1);
                sut.tick();                               // 생략 1
                at(10, 5);
                sut.tick();                               // 생략 2
                at(10, 10);
                sut.tick();                               // 기록 (2회 생략)

                List<String> warn = logs.messages(Level.WARN);
                assertThat(warn).hasSize(2);
                assertThat(warn.get(1)).contains("2회 생략");
            }
        }

        @Test
        @DisplayName("전고점보다 낮은 불일치는 거부할 것도 없다 — 로그 없음")
        void mismatch_below_peak_is_silent() {
            try (LogCapture logs = LogCapture.of(ShadowPortfolio.class)) {
                tickWith(mismatched(10_000_000));

                assertThat(logs.messages(Level.WARN)).isEmpty();
            }
        }

        @Test
        @DisplayName("낡은 스냅샷은 신선도 관문이 먼저 거른다 — 거부 로그 없음")
        void stale_mismatch_is_filtered_by_freshness_first() {
            try (LogCapture logs = LogCapture.of(ShadowPortfolio.class)) {
                tickWith(mismatched(SMALLEST_DANGER).asStale());

                assertThat(logs.messages(Level.WARN)).isEmpty();
                assertThat(sut.getPeakEquity()).isEqualTo(PEAK);
            }
        }
    }

    @Test
    @DisplayName("백테스트 조립(무검증 + 4인자 Account)의 갱신 순서는 예전과 같다")
    void backtest_assembly_tick_sequence_is_unchanged() {
        ShadowPortfolio backtest = new ShadowPortfolio(
                positionManager, mock(PortfolioStateRepository.class), new NoOpPeakEquityCalibrator(), clock);
        double[] equities = {10_000_000, 9_500_000, 10_300_000, 50_000_000, 49_000_000};
        double[] expected = {10_000_000, 10_000_000, 10_300_000, 50_000_000, 50_000_000};

        for (int i = 0; i < equities.length; i++) {
            when(positionManager.snapshotAccount()).thenReturn(new Account(equities[i], 0.0, 0, List.of()));
            backtest.tick();
            assertThat(backtest.getPeakEquity()).isEqualTo(expected[i]);
        }
    }

    @Nested
    @DisplayName("스프링 배선 — 시계를 받는 생성자로 만들어진다")
    class Wiring {

        @Test
        @DisplayName("paper·backtest 모두 컨테이너가 시계 생성자로 조립하고, 그 시계로 로그 간격을 잰다")
        void container_builds_it_with_the_clock_bean() {
            for (String profile : List.of("paper", "backtest")) {
                try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
                     LogCapture logs = LogCapture.of(ShadowPortfolio.class)) {
                    ctx.getEnvironment().setActiveProfiles(profile);
                    ctx.registerBean(PositionManager.class, () -> positionManager);
                    ctx.registerBean(PortfolioStateRepository.class, () -> mock(PortfolioStateRepository.class));
                    ctx.registerBean(PeakEquityCalibrator.class, NoOpPeakEquityCalibrator::new);
                    ctx.registerBean(Clock.class, () -> clock);
                    ctx.register(ShadowPortfolio.class);
                    ctx.refresh();
                    ShadowPortfolio bean = ctx.getBean(ShadowPortfolio.class);

                    at(10, 0);
                    when(positionManager.snapshotAccount()).thenReturn(mismatched(SMALLEST_DANGER));
                    bean.tick();                  // 기록
                    at(10, 20);
                    bean.tick();                  // 주입된 시계로 20분 뒤 → 다시 기록 (시스템 시계였다면 참았다)

                    assertThat(logs.messages(Level.WARN)).as(profile).hasSize(2);
                }
            }
        }
    }
}
