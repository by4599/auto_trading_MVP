package com.trading.control;

import com.trading.mirror.MirrorPublisher;
import com.trading.mirror.MirrorSnapshotAssembler;
import com.trading.mirror.SupabaseProperties;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 미러 수동 전송 창구 — 실패해도 예외 대신 안내 메시지를 돌려준다.
 *
 * 날짜 사실관계 (시스템 도구로 검증): 2026-08-20 목요일.
 */
@DisplayName("MirrorController — 연결 확인용 수동 전송")
class MirrorControllerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Clock clock = Clock.fixed(
            LocalDate.of(2026, 8, 20).atTime(22, 0).atZone(KST).toInstant(), KST);

    private final PositionManager positionManager = mock(PositionManager.class);
    private final MirrorPublisher publisher       = mock(MirrorPublisher.class);

    private MirrorController controller() {
        MirrorSnapshotAssembler assembler = new MirrorSnapshotAssembler(
                positionManager, mock(PositionRepository.class), mock(TradeResultRepository.class),
                mock(PortfolioStateRepository.class), new TradingStatusManager(),
                new SupabaseProperties(), clock);
        return new MirrorController(assembler, publisher);
    }

    @Test
    @DisplayName("전송에 성공하면 success=true와 보유 종목 수를 돌려준다")
    void reportsSuccess() {
        when(positionManager.snapshotAccount()).thenReturn(new Account(1_000_000, 0, 0,
                List.of(new Account.PositionSnapshot("005930", 10, 70_000, 71_000))));
        when(publisher.publish(any())).thenReturn(true);

        Map<String, Object> result = controller().push();

        assertThat(result).containsEntry("success", true).containsEntry("holdings", 1);
        assertThat((String) result.get("updatedAt")).startsWith("2026-08-20T22:00");
    }

    @Test
    @DisplayName("설정이 없어 전송하지 못하면 success=false와 확인 안내를 돌려준다")
    void reportsSkip() {
        when(positionManager.snapshotAccount()).thenReturn(new Account(0, 0, 0, List.of()));
        when(publisher.publish(any())).thenReturn(false);

        Map<String, Object> result = controller().push();

        assertThat(result).containsEntry("success", false);
        assertThat((String) result.get("message")).contains("SUPABASE_URL");
    }

    @Test
    @DisplayName("스냅샷 조립이 실패해도 예외 대신 안내 메시지를 돌려준다")
    void reportsAssemblyFailure() {
        when(positionManager.snapshotAccount()).thenThrow(new IllegalStateException("잔고 조회 실패"));

        Map<String, Object> result = controller().push();

        assertThat(result).containsEntry("success", false);
        assertThat((String) result.get("message")).contains("잔고 조회 실패");
    }
}
