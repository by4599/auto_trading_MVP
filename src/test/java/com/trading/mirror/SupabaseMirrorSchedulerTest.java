package com.trading.mirror;

import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.Account;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResultRepository;
import com.trading.risk.TradingStatusManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 미러 전송 주기 스케줄러 — "매매에 영향을 주지 않는다"가 이 클래스의 계약이다.
 *
 * 날짜 사실관계 (시스템 도구로 검증): 2026-08-20 목요일(거래일).
 */
@DisplayName("SupabaseMirrorScheduler — 장중에만 전송, 실패는 삼킨다")
class SupabaseMirrorSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate THU_0820 = LocalDate.of(2026, 8, 20);

    private final PositionManager       positionManager = mock(PositionManager.class);
    private final MirrorPublisher       publisher       = mock(MirrorPublisher.class);
    private final SupabaseProperties    props           = new SupabaseProperties();

    private SupabaseMirrorScheduler schedulerAt(LocalDateTime now) {
        Clock clock = Clock.fixed(now.atZone(KST).toInstant(), KST);
        MirrorSnapshotAssembler assembler = new MirrorSnapshotAssembler(
                positionManager, mock(PositionRepository.class), mock(TradeResultRepository.class),
                mock(PortfolioStateRepository.class), new TradingStatusManager(), props, clock);
        MarketCalendarService calendar = new MarketCalendarService(new MarketCalendarProperties(), clock);
        return new SupabaseMirrorScheduler(assembler, publisher, calendar, props);
    }

    private void configure() {
        props.setUrl("https://example.supabase.co");
        props.setKey("secret-key-for-test");
    }

    private void withEmptyAccount() {
        when(positionManager.snapshotAccount()).thenReturn(new Account(0, 0, 0, List.of()));
    }

    @Test
    @DisplayName("거래일 장중에는 스냅샷을 전송한다")
    void publishesDuringMarketHours() {
        configure();
        withEmptyAccount();

        schedulerAt(THU_0820.atTime(10, 0)).push();

        verify(publisher, times(1)).publish(any());
    }

    @Test
    @DisplayName("장외 시간에는 전송하지 않는다 — 미러 때문에 잔고 API가 새로 호출되면 안 된다")
    void skipsOutsideMarketHours() {
        configure();
        withEmptyAccount();

        schedulerAt(THU_0820.atTime(23, 0)).push();

        verify(publisher, never()).publish(any());
        verify(positionManager, never()).snapshotAccount();
    }

    @Test
    @DisplayName("supabase.url·key 미설정이면 아무 일도 하지 않는다")
    void skipsWhenNotConfigured() {
        withEmptyAccount();

        schedulerAt(THU_0820.atTime(10, 0)).push();

        verify(publisher, never()).publish(any());
        verify(positionManager, never()).snapshotAccount();
    }

    @Test
    @DisplayName("스냅샷 조립이 실패해도 예외가 매매 스케줄로 새어나가지 않는다")
    void swallowsAssemblyFailure() {
        configure();
        when(positionManager.snapshotAccount()).thenThrow(new IllegalStateException("잔고 조회 실패"));

        assertThatCode(() -> schedulerAt(THU_0820.atTime(10, 0)).push())
                .doesNotThrowAnyException();
        verify(publisher, never()).publish(any());
    }
}
