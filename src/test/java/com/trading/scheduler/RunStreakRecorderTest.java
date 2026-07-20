package com.trading.scheduler;

import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.PortfolioState;
import com.trading.position.PortfolioStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * 연속 무중단 가동일수 기록 (릴리즈 검증 항목 "모의투자 5거래일 연속 실행").
 *
 * 날짜 사실관계 (요일은 시스템 도구로 검증):
 *   2026-07-14 화 / 2026-07-17 금 / 2026-07-18 토 / 2026-07-20 월
 */
@DisplayName("RunStreakRecorder — 연속 무중단 거래일 기록")
class RunStreakRecorderTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate MON_0720 = LocalDate.of(2026, 7, 20);

    private final PortfolioStateRepository stateRepo = mock(PortfolioStateRepository.class);

    /** appStartedAt은 recorder 생성 시각(Clock) — 기동 시각을 Clock으로 주입한다. */
    private RunStreakRecorder recorderStartedAt(LocalDateTime startedAt) {
        Clock clock = Clock.fixed(startedAt.atZone(KST).toInstant(), KST);
        MarketCalendarService calendar = new MarketCalendarService(new MarketCalendarProperties(), clock);
        return new RunStreakRecorder(stateRepo, calendar, clock);
    }

    private void givenState(Integer streakDays, Double lastDateKey) {
        when(stateRepo.findById(PortfolioState.KEY_RUN_STREAK_DAYS)).thenReturn(
                Optional.ofNullable(streakDays == null ? null
                        : PortfolioState.of(PortfolioState.KEY_RUN_STREAK_DAYS, streakDays)));
        when(stateRepo.findById(PortfolioState.KEY_RUN_STREAK_LAST_DATE)).thenReturn(
                Optional.ofNullable(lastDateKey == null ? null
                        : PortfolioState.of(PortfolioState.KEY_RUN_STREAK_LAST_DATE, lastDateKey)));
    }

    private Map<String, Double> capturedSaves() {
        ArgumentCaptor<PortfolioState> captor = ArgumentCaptor.forClass(PortfolioState.class);
        verify(stateRepo, times(2)).save(captor.capture());
        return captor.getAllValues().stream()
                .collect(Collectors.toMap(PortfolioState::getStateKey, PortfolioState::getStateValue));
    }

    @Test
    @DisplayName("첫 기록 + 개장 전 기동 → 연속 1일")
    void first_record_starts_streak_at_one() {
        givenState(null, null);
        RunStreakRecorder sut = recorderStartedAt(MON_0720.atTime(8, 30));

        sut.recordFor(MON_0720);

        Map<String, Double> saved = capturedSaves();
        assertThat(saved.get(PortfolioState.KEY_RUN_STREAK_DAYS)).isEqualTo(1.0);
        assertThat(saved.get(PortfolioState.KEY_RUN_STREAK_LAST_DATE)).isEqualTo(20260720.0);
    }

    @Test
    @DisplayName("금요일까지 4일 기록 → 주말 건너뛰고 월요일에 5일 (목표 달성)")
    void weekend_gap_does_not_break_streak() {
        givenState(4, 20260717.0);  // 마지막 기록 = 금요일
        RunStreakRecorder sut = recorderStartedAt(MON_0720.atTime(8, 30));

        sut.recordFor(MON_0720);

        assertThat(capturedSaves().get(PortfolioState.KEY_RUN_STREAK_DAYS)).isEqualTo(5.0);
    }

    @Test
    @DisplayName("기록 공백(마지막 기록이 전전 거래일) → 1일로 리셋")
    void gap_in_records_resets_streak_to_one() {
        givenState(2, 20260714.0);  // 마지막 기록 = 화요일 (수·목·금 공백)
        RunStreakRecorder sut = recorderStartedAt(MON_0720.atTime(8, 30));

        sut.recordFor(MON_0720);

        assertThat(capturedSaves().get(PortfolioState.KEY_RUN_STREAK_DAYS)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("개장(09:00) 이후 기동 → 오늘 미인정, 0으로 리셋")
    void late_start_resets_streak_to_zero() {
        givenState(4, 20260717.0);
        RunStreakRecorder sut = recorderStartedAt(MON_0720.atTime(9, 30));

        sut.recordFor(MON_0720);

        assertThat(capturedSaves().get(PortfolioState.KEY_RUN_STREAK_DAYS)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("휴장일(토요일) → 기록하지 않음")
    void holiday_records_nothing() {
        RunStreakRecorder sut = recorderStartedAt(LocalDate.of(2026, 7, 18).atTime(8, 30));

        sut.recordFor(LocalDate.of(2026, 7, 18));

        verify(stateRepo, never()).save(any());
    }

    @Test
    @DisplayName("같은 날 중복 실행 → 두 번째는 기록하지 않음")
    void duplicate_run_same_day_is_ignored() {
        givenState(3, 20260720.0);  // 오늘 이미 기록됨
        RunStreakRecorder sut = recorderStartedAt(MON_0720.atTime(8, 30));

        sut.recordFor(MON_0720);

        verify(stateRepo, never()).save(any());
    }
}
