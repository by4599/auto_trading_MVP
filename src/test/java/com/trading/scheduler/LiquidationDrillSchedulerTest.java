package com.trading.scheduler;

import com.trading.NotificationService;
import com.trading.control.DrillOperations;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.PortfolioState;
import com.trading.position.PortfolioStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 예약 청산 리허설 스케줄러 단위 테스트.
 *
 * 인터페이스(DrillOperations·PortfolioStateRepository·NotificationService)만 목으로 만들고
 * 달력·설정은 실객체로 조립한다 (Java 25 인라인 목 제약 회피 — 프로젝트 공통 패턴).
 * 2026-08-20은 목요일, 2026-08-22는 토요일이다.
 */
@DisplayName("LiquidationDrillScheduler — 예약 청산 리허설")
class LiquidationDrillSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String DRILL_DAY = "2026-08-20";

    private final Map<String, PortfolioState> store = new HashMap<>();
    private final DrillOperations drill = mock(DrillOperations.class);
    private final NotificationService notifier = mock(NotificationService.class);

    private LiquidationDrillScheduler scheduler(DrillProperties props, String nowIso) {
        PortfolioStateRepository repo = mock(PortfolioStateRepository.class);
        when(repo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        when(repo.save(any(PortfolioState.class))).thenAnswer(inv -> {
            PortfolioState s = inv.getArgument(0);
            store.put(s.getStateKey(), s);
            return s;
        });
        Clock clock = Clock.fixed(LocalDateTime.parse(nowIso).atZone(KST).toInstant(), KST);
        return new LiquidationDrillScheduler(props, drill,
                new MarketCalendarService(new MarketCalendarProperties(), clock), repo, notifier, clock);
    }

    private DrillProperties props(boolean enabled, String date) {
        return new DrillProperties(enabled, date, "10:00", "14:00", true);
    }

    @Test
    @DisplayName("꺼져 있으면 아무것도 하지 않는다 (기본값)")
    void disabled_does_nothing() {
        scheduler(props(false, DRILL_DAY), DRILL_DAY + "T10:30:00").tick();

        verifyNoInteractions(drill);
    }

    @Test
    @DisplayName("예약일을 비워두면 아무것도 하지 않는다")
    void no_date_does_nothing() {
        scheduler(props(true, ""), DRILL_DAY + "T10:30:00").tick();

        verifyNoInteractions(drill);
    }

    @Test
    @DisplayName("예약일이 오늘이 아니면 하지 않는다")
    void other_day_does_nothing() {
        scheduler(props(true, "2026-08-21"), DRILL_DAY + "T10:30:00").tick();

        verifyNoInteractions(drill);
    }

    @Test
    @DisplayName("거래일이 아니면 하지 않는다 (토요일)")
    void non_trading_day_does_nothing() {
        scheduler(props(true, "2026-08-22"), "2026-08-22T10:30:00").tick();

        verifyNoInteractions(drill);
    }

    @Test
    @DisplayName("개시 시각 전이면 하지 않는다")
    void before_start_time_does_nothing() {
        scheduler(props(true, DRILL_DAY), DRILL_DAY + "T09:30:00").tick();

        verifyNoInteractions(drill);
    }

    @Test
    @DisplayName("기한을 넘기면 하지 않는다 (타임컷과 겹치지 않게)")
    void after_deadline_does_nothing() {
        scheduler(props(true, DRILL_DAY), DRILL_DAY + "T14:30:00").tick();

        verifyNoInteractions(drill);
    }

    @Test
    @DisplayName("보유가 없으면 먼저 매수만 하고 청산하지 않는다")
    void buys_first_when_flat() {
        when(drill.hasAnyPosition()).thenReturn(false);
        when(drill.manualBuy()).thenReturn(new DrillOperations.Outcome(true, "접수"));

        scheduler(props(true, DRILL_DAY), DRILL_DAY + "T10:30:00").tick();

        verify(drill).manualBuy();
        verify(drill, never()).liquidate();
    }

    @Test
    @DisplayName("체결이 늦어도 매수를 두 번 내지 않는다")
    void does_not_buy_twice_while_waiting_for_fill() {
        when(drill.hasAnyPosition()).thenReturn(false);
        when(drill.manualBuy()).thenReturn(new DrillOperations.Outcome(true, "접수"));

        scheduler(props(true, DRILL_DAY), DRILL_DAY + "T10:30:00").tick();
        scheduler(props(true, DRILL_DAY), DRILL_DAY + "T10:31:00").tick();

        verify(drill, times(1)).manualBuy();
    }

    @Test
    @DisplayName("보유가 있으면 청산을 개시한다")
    void liquidates_when_holding() {
        when(drill.hasAnyPosition()).thenReturn(true);
        when(drill.liquidate()).thenReturn(new DrillOperations.Outcome(true, "개시"));

        scheduler(props(true, DRILL_DAY), DRILL_DAY + "T10:30:00").tick();

        verify(drill).liquidate();
        verify(drill, never()).manualBuy();
        assertThat(store).containsKey(PortfolioState.KEY_DRILL_DONE_DATE);
    }

    @Test
    @DisplayName("같은 날 두 번 청산하지 않는다")
    void does_not_liquidate_twice_in_a_day() {
        when(drill.hasAnyPosition()).thenReturn(true);
        when(drill.liquidate()).thenReturn(new DrillOperations.Outcome(true, "개시"));

        scheduler(props(true, DRILL_DAY), DRILL_DAY + "T10:30:00").tick();
        scheduler(props(true, DRILL_DAY), DRILL_DAY + "T10:31:00").tick();

        verify(drill, times(1)).liquidate();
    }

    @Test
    @DisplayName("조작이 예외를 던져도 스케줄러는 죽지 않는다 (다음 틱 재시도)")
    void survives_exception_from_drill() {
        when(drill.hasAnyPosition()).thenThrow(new IllegalStateException("일시 장애"));

        scheduler(props(true, DRILL_DAY), DRILL_DAY + "T10:30:00").tick();   // 예외가 밖으로 나오면 실패

        verify(drill).hasAnyPosition();
    }
}
