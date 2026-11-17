package com.trading.position;

import com.trading.NotificationService;
import com.trading.backtest.MutableClock;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.risk.RiskLimitsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 전고점은 장중에만 올린다 (감사 H-1(b), 2026-10-01).
 *
 * <p>다일 보유분이 밤을 넘기면 장외에도 신선한 잔고 스냅샷이 생긴다. 관측된 전고점 오염 2건은 둘 다
 * 장 밖이었고(09-11 17:17 등, CLAUDE.md 결함 6), 오염된 전고점은 다음 날 09:00 RiskMonitor의 MDD 강제청산으로
 * 이어진다(09-10~09-21 7거래일 정지). 실객체 ShadowPortfolio + 실객체 캘리브레이터, 인터페이스만 목.
 */
@DisplayName("ShadowPortfolioTicker — 전고점은 장중에만 갱신한다")
class ShadowPortfolioTickerTest {

    private static final LocalDate THU = LocalDate.of(2026, 10, 1);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 2);
    private static final LocalDate SAT = LocalDate.of(2026, 10, 3);

    private MutableClock clock;
    private PositionManager positionManager;
    private PortfolioStateRepository stateRepository;
    private ShadowPortfolio shadow;
    private ShadowPortfolioTicker sut;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.EPOCH);
        clock.setTo(THU, LocalTime.of(10, 0));
        positionManager = mock(PositionManager.class);
        stateRepository = mock(PortfolioStateRepository.class);
        DailyEquityRepository dailyEquity = mock(DailyEquityRepository.class);
        when(dailyEquity.findMaxStartEquity()).thenReturn(10_000_000.0);   // 상한 11,500,000 — 아래 값은 전부 범위 안
        MarketCalendarService calendar = new MarketCalendarService(new MarketCalendarProperties(), clock);
        shadow = new ShadowPortfolio(positionManager, stateRepository,
                new EvidenceBasedPeakEquityCalibrator(dailyEquity, stateRepository, new RiskLimitsProperties(),
                        mock(NotificationService.class), clock, calendar));
        sut = new ShadowPortfolioTicker(shadow, calendar);
    }

    private void totalAssetIs(double value) {
        when(positionManager.snapshotAccount()).thenReturn(new Account(value, 0.0, 0, List.of()));
    }

    @Test
    @DisplayName("장중에는 기존대로 — 더 높은 신선 총자산이면 전고점을 올린다 (마감 시각 15:30 포함)")
    void raises_peak_during_market_hours() {
        totalAssetIs(9_900_000);
        sut.tick();
        assertThat(shadow.getPeakEquity()).isEqualTo(9_900_000);

        clock.setTo(THU, LocalTime.of(15, 30));      // 경계 포함 — RiskMonitor의 장 시간 판정과 같다
        totalAssetIs(9_950_000);
        sut.tick();
        assertThat(shadow.getPeakEquity()).isEqualTo(9_950_000);
    }

    @Test
    @DisplayName("장 밖에는 더 높은 신선 총자산이 와도 전고점 불변 · 저장 0건 → 다음 개장 첫 틱부터 다시 갱신")
    void never_raises_peak_outside_market_hours() {
        totalAssetIs(9_900_000);
        sut.tick();                                   // 10:00 장중 — 9,900,000 확립
        clearInvocations(stateRepository);

        totalAssetIs(10_890_158);                     // 실측 오염값(결함 6) — 상한 아래라 예전엔 그대로 올라갔다
        for (LocalTime t : List.of(LocalTime.of(15, 30, 1), LocalTime.of(17, 17), LocalTime.of(23, 59, 59))) {
            clock.setTo(THU, t);
            sut.tick();
        }
        for (LocalTime t : List.of(LocalTime.of(8, 30), LocalTime.of(8, 59, 59))) {
            clock.setTo(FRI, t);                      // 평일 자동 기동 ~ 개장 1초 전
            sut.tick();
        }
        assertThat(shadow.getPeakEquity()).isEqualTo(9_900_000);
        verify(stateRepository, never()).save(any());

        clock.setTo(FRI, LocalTime.of(9, 0));         // 개장 첫 틱 — 잠근 게 아니라 장 밖만 건너뛴 것
        totalAssetIs(9_950_000);
        sut.tick();
        assertThat(shadow.getPeakEquity()).isEqualTo(9_950_000);
    }

    @Test
    @DisplayName("휴장일에는 낮이어도 올리지 않는다")
    void never_raises_peak_on_holiday() {
        clock.setTo(SAT, LocalTime.of(10, 0));
        totalAssetIs(10_400_000);

        sut.tick();

        assertThat(shadow.getPeakEquity()).isZero();
        verify(stateRepository, never()).save(any());
    }
}
