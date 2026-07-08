package com.trading.position;

import com.trading.position.BalanceClient.BalanceSnapshot;
import com.trading.position.BalanceClient.Holding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KisPositionManagerTest {

    private BalanceClient balanceClient;
    private PositionRepository positionRepository;
    private DailyEquityRepository dailyEquityRepository;
    private KisPositionManager sut;

    @BeforeEach
    void setUp() {
        balanceClient = mock(BalanceClient.class);
        positionRepository = mock(PositionRepository.class);
        dailyEquityRepository = mock(DailyEquityRepository.class);
        sut = new KisPositionManager(balanceClient, positionRepository, dailyEquityRepository);
    }

    // ── F-1: 총자산은 예수금 포함 tot_evlu_amt ──────────────────────────────

    @Test
    void totalAssetValue_includes_cash_from_balance_api() {
        // 예수금 4,992만 + 삼성전자 1주(8만) = 총자산 5,000만
        when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(
                50_000_000, List.of(new Holding("005930", 1, 79_000, 80_000))));
        when(dailyEquityRepository.findById(any(LocalDate.class)))
                .thenReturn(Optional.of(DailyEquity.of(LocalDate.now(), 50_000_000)));

        Account account = sut.snapshotAccount();

        assertThat(account.getTotalAssetValue()).isEqualTo(50_000_000);
        assertThat(account.getPositionCount()).isEqualTo(1);
        // 현재가는 평단가가 아니라 KIS 실시간 현재가
        assertThat(account.getPositions().get(0).currentPrice()).isEqualTo(80_000);
    }

    // ── F-5: dailyPnl = (현재 - 당일시작) / 당일시작 ─────────────────────────

    @Test
    void dailyPnl_computed_against_day_start_equity() {
        // 당일 시작 5,000만 → 현재 4,850만 = -3.0%
        when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(48_500_000, List.of()));
        when(dailyEquityRepository.findById(any(LocalDate.class)))
                .thenReturn(Optional.of(DailyEquity.of(LocalDate.now(), 50_000_000)));

        Account account = sut.snapshotAccount();

        assertThat(account.getDailyPnlPercent()).isCloseTo(-0.03, within(1e-9));
    }

    @Test
    void first_snapshot_of_day_records_start_equity_and_pnl_is_zero() {
        when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(50_000_000, List.of()));
        when(dailyEquityRepository.findById(any(LocalDate.class))).thenReturn(Optional.empty());
        when(dailyEquityRepository.save(any(DailyEquity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Account account = sut.snapshotAccount();

        verify(dailyEquityRepository).save(any(DailyEquity.class));
        assertThat(account.getDailyPnlPercent()).isEqualTo(0.0);
    }

    // ── TTL 캐시: 연속 호출 시 잔고 API는 1회만 ──────────────────────────────

    @Test
    void consecutive_calls_within_ttl_hit_balance_api_once() {
        when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(50_000_000, List.of()));
        when(dailyEquityRepository.findById(any(LocalDate.class)))
                .thenReturn(Optional.of(DailyEquity.of(LocalDate.now(), 50_000_000)));

        sut.snapshotAccount();
        sut.snapshotAccount();
        sut.snapshotAccount();

        verify(balanceClient, times(1)).fetchBalance();
    }

    // ── 폴백 ─────────────────────────────────────────────────────────────────

    @Test
    void api_failure_within_ttl_still_returns_cached_value() {
        when(balanceClient.fetchBalance())
                .thenReturn(new BalanceSnapshot(50_000_000, List.of()))
                .thenThrow(new IllegalStateException("KIS 장애"));
        when(dailyEquityRepository.findById(any(LocalDate.class)))
                .thenReturn(Optional.of(DailyEquity.of(LocalDate.now(), 50_000_000)));

        Account first = sut.snapshotAccount();
        Account second = sut.snapshotAccount(); // 캐시 히트 — 예외가 밖으로 새지 않는다

        assertThat(first.getTotalAssetValue()).isEqualTo(50_000_000);
        assertThat(second.getTotalAssetValue()).isEqualTo(50_000_000);
    }

    @Test
    void api_failure_without_history_falls_back_to_db_with_zero_pnl() {
        when(balanceClient.fetchBalance()).thenThrow(new IllegalStateException("KIS 장애"));
        when(positionRepository.findAll()).thenReturn(List.of());

        Account account = sut.snapshotAccount();

        assertThat(account.getTotalAssetValue()).isEqualTo(0.0);
        assertThat(account.getDailyPnlPercent()).isEqualTo(0.0);
    }
}
