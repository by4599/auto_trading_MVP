package com.trading.position;

import com.trading.backtest.MutableClock;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.BalanceClient.BalanceSnapshot;
import com.trading.position.BalanceClient.Holding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KisPositionManagerTest {

    /** 2026-09-30(수) — 휴장일이 아니다. 시각만 옮겨 장중/장외를 가른다 */
    private static final LocalDate TRADING_DAY = LocalDate.of(2026, 9, 30);

    private BalanceClient balanceClient;
    private PositionRepository positionRepository;
    private DailyEquityRepository dailyEquityRepository;
    private PortfolioStateRepository portfolioStateRepository;
    private MutableClock clock;
    private KisPositionManager sut;

    @BeforeEach
    void setUp() {
        balanceClient = mock(BalanceClient.class);
        positionRepository = mock(PositionRepository.class);
        dailyEquityRepository = mock(DailyEquityRepository.class);
        portfolioStateRepository = mock(PortfolioStateRepository.class);
        // 기본은 장중(10:00) — 기존 테스트는 전부 장중 동작을 검증한다
        clock = new MutableClock(ZonedDateTime.of(TRADING_DAY, LocalTime.of(10, 0), ZoneId.of("Asia/Seoul"))
                .toInstant());
        sut = new KisPositionManager(balanceClient, positionRepository, dailyEquityRepository,
                new TradeResultTracker(portfolioStateRepository),
                new MarketCalendarService(new MarketCalendarProperties(), clock));
    }

    // ── F-1: 총자산은 예수금 포함 tot_evlu_amt ──────────────────────────────

    @Test
    void totalAssetValue_includes_cash_from_balance_api() {
        // 예수금 4,992만 + 삼성전자 1주(8만) = 총자산 5,000만
        when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(
                50_000_000, 49_920_000, List.of(new Holding("005930", 1, 79_000, 80_000))));
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
        when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(48_500_000, 0, List.of()));
        when(dailyEquityRepository.findById(any(LocalDate.class)))
                .thenReturn(Optional.of(DailyEquity.of(LocalDate.now(), 50_000_000)));

        Account account = sut.snapshotAccount();

        assertThat(account.getDailyPnlPercent()).isCloseTo(-0.03, within(1e-9));
    }

    @Test
    void first_snapshot_of_day_records_start_equity_and_pnl_is_zero() {
        when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(50_000_000, 0, List.of()));
        when(dailyEquityRepository.findById(any(LocalDate.class))).thenReturn(Optional.empty());
        when(dailyEquityRepository.save(any(DailyEquity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Account account = sut.snapshotAccount();

        verify(dailyEquityRepository).save(any(DailyEquity.class));
        assertThat(account.getDailyPnlPercent()).isEqualTo(0.0);
    }

    /** 일별 순손익 원장의 "시작" 쪽 — 예수금(현금)도 같은 행에 실려야 마감과 짝이 맞는다 */
    @Test
    void first_snapshot_of_day_also_records_start_deposit() {
        when(balanceClient.fetchBalance())
                .thenReturn(new BalanceSnapshot(50_000_000, 49_920_000, List.of()));
        when(dailyEquityRepository.findById(any(LocalDate.class))).thenReturn(Optional.empty());
        when(dailyEquityRepository.save(any(DailyEquity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        sut.snapshotAccount();

        ArgumentCaptor<DailyEquity> captor = ArgumentCaptor.forClass(DailyEquity.class);
        verify(dailyEquityRepository).save(captor.capture());
        assertThat(captor.getValue().getStartEquity()).isEqualTo(50_000_000.0);
        assertThat(captor.getValue().getStartDeposit()).isEqualTo(49_920_000.0);
        assertThat(captor.getValue().isClosed()).isFalse();   // 마감은 아직 안 찍혔다
    }

    // ── F-5: consecutiveLossCount는 TradeResultTracker(portfolio_state)에서 ──

    @Test
    void consecutive_loss_count_wired_from_portfolio_state() {
        when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(50_000_000, 0, List.of()));
        when(dailyEquityRepository.findById(any(LocalDate.class)))
                .thenReturn(Optional.of(DailyEquity.of(LocalDate.now(), 50_000_000)));
        when(portfolioStateRepository.findById(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT))
                .thenReturn(Optional.of(PortfolioState.of(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT, 2)));

        Account account = sut.snapshotAccount();

        assertThat(account.getConsecutiveLossCount()).isEqualTo(2);
    }

    // ── TTL 캐시: 연속 호출 시 잔고 API는 1회만 ──────────────────────────────

    @Test
    void consecutive_calls_within_ttl_hit_balance_api_once() {
        when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(50_000_000, 0, List.of()));
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
                .thenReturn(new BalanceSnapshot(50_000_000, 0, List.of()))
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

    // ── 장외 잔고 폴링 중단 (2026-09-30) ──────────────────────────────────────

    /**
     * 장외 잔고 호출은 순수 낭비인데, 그 실패가 KisApiClient의 연속 실패 카운터에 쌓여
     * 아침을 SAFE_MODE 근처에서 시작시킨다 (2026-09-22 16시 한 시간에 잔고 실패 135건).
     * 근거: _workspace/25_ops_safemode-flapping-diagnosis.md §6
     */
    @Nested
    @DisplayName("장외 + 보유 0이면 잔고 API를 부르지 않는다")
    class OutsideMarketHoursTest {

        private void moveTo(int hour, int minute) {
            clock.setTo(TRADING_DAY, LocalTime.of(hour, minute));
        }

        @Test
        @DisplayName("장외 + 보유 0 → 잔고 KIS 호출 0회, 스냅샷은 낡음(isFresh=false)")
        void outside_market_hours_without_holdings_skips_balance_api() {
            moveTo(20, 0);
            when(positionRepository.count()).thenReturn(0L);
            when(positionRepository.findAll()).thenReturn(List.of());

            Account account = sut.snapshotAccount();

            verify(balanceClient, never()).fetchBalance();
            assertThat(account.isFresh()).isFalse();   // 청산·손절·전고점 판정이 건너뛰도록
        }

        @Test
        @DisplayName("장외 + 보유 0 → 마지막 스냅샷을 그대로(낡은 값으로) 돌려준다")
        void outside_market_hours_returns_last_snapshot_as_stale() {
            when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(9_800_000, 9_800_000, List.of()));
            when(dailyEquityRepository.findById(any(LocalDate.class)))
                    .thenReturn(Optional.of(DailyEquity.of(TRADING_DAY, 9_800_000)));
            when(positionRepository.count()).thenReturn(0L);

            Account duringMarket = sut.snapshotAccount();   // 장중 1회 — 캐시를 채운다
            moveTo(20, 0);
            Account afterClose = sut.snapshotAccount();

            assertThat(duringMarket.isFresh()).isTrue();
            assertThat(afterClose.isFresh()).isFalse();
            assertThat(afterClose.getTotalAssetValue()).isEqualTo(9_800_000);  // 마지막 값 그대로
            verify(balanceClient, times(1)).fetchBalance();                    // 장외에 추가 호출 없음
        }

        @Test
        @DisplayName("장외 + 보유 있음 → 계속 호출한다 (다일 보유 칸이 켜지면 밤샘 감시가 필요하다)")
        void outside_market_hours_with_holdings_still_calls_balance_api() {
            moveTo(20, 0);
            when(positionRepository.count()).thenReturn(1L);
            when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(
                    9_800_000, 9_720_000, List.of(new Holding("005930", 1, 79_000, 80_000))));
            when(dailyEquityRepository.findById(any(LocalDate.class)))
                    .thenReturn(Optional.of(DailyEquity.of(TRADING_DAY, 9_800_000)));

            Account account = sut.snapshotAccount();

            verify(balanceClient, times(1)).fetchBalance();
            assertThat(account.isFresh()).isTrue();
        }

        @Test
        @DisplayName("장외 + DB는 보유 0인데 브로커가 마지막으로 알려준 보유가 있으면 계속 호출한다")
        void outside_market_hours_trusts_last_broker_holdings_too() {
            when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(
                    9_800_000, 9_720_000, List.of(new Holding("005930", 1, 79_000, 80_000))));
            when(dailyEquityRepository.findById(any(LocalDate.class)))
                    .thenReturn(Optional.of(DailyEquity.of(TRADING_DAY, 9_800_000)));
            when(positionRepository.count()).thenReturn(0L);   // DB는 비어 있다(desync)

            sut.snapshotAccount();   // 장중 1회 — 보유 1종목이 캐시에 남는다
            moveTo(20, 0);
            Account afterClose = sut.snapshotAccount();

            // 스킵되지 않았다 = 3초 TTL 캐시 경로로 갔다 → 낡음 표시가 붙지 않는다
            assertThat(afterClose.isFresh()).isTrue();
        }

        @Test
        @DisplayName("장중 + 보유 0 → 기존과 동일하게 호출한다")
        void during_market_hours_calls_balance_api_even_without_holdings() {
            moveTo(10, 30);
            when(positionRepository.count()).thenReturn(0L);
            when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(9_800_000, 9_800_000, List.of()));
            when(dailyEquityRepository.findById(any(LocalDate.class)))
                    .thenReturn(Optional.of(DailyEquity.of(TRADING_DAY, 9_800_000)));

            Account account = sut.snapshotAccount();

            verify(balanceClient, times(1)).fetchBalance();
            assertThat(account.isFresh()).isTrue();
        }

        @Test
        @DisplayName("휴장일(토요일)은 시각이 장중이어도 장외다 — 호출하지 않는다")
        void holiday_is_outside_market_hours_even_at_ten_am() {
            clock.setTo(LocalDate.of(2026, 10, 3), LocalTime.of(10, 0));   // 토요일
            when(positionRepository.count()).thenReturn(0L);
            when(positionRepository.findAll()).thenReturn(List.of());

            Account account = sut.snapshotAccount();

            verify(balanceClient, never()).fetchBalance();
            assertThat(account.isFresh()).isFalse();
        }
    }
}
