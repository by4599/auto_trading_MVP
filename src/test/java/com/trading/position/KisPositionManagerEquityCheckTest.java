package com.trading.position;

import com.trading.backtest.MutableClock;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.BalanceClient.BalanceSnapshot;
import com.trading.position.EquityCrossCheck.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.when;

/**
 * KisPositionManager가 잔고 스냅샷의 대조 판정을 Account로 옮기는지 — 폴백·DB·낡은 경로의 뜻은 그대로.
 * Java 25 Mockito 제약: 인터페이스(BalanceClient·리포지토리)만 목, 나머지는 실객체.
 */
@DisplayName("KisPositionManager — 잔고 대조 판정 전달")
class KisPositionManagerEquityCheckTest {

    private static final LocalDate TRADING_DAY = LocalDate.of(2026, 10, 12);   // 월요일
    private static final EquityCrossCheck MISMATCH =
            EquityCrossCheck.evaluate(10_890_158, OptionalDouble.of(10_088_806), List.of());

    private BalanceClient balanceClient;
    private PositionRepository positionRepository;
    private KisPositionManager sut;

    @BeforeEach
    void setUp() {
        balanceClient = mock(BalanceClient.class);
        positionRepository = mock(PositionRepository.class);
        DailyEquityRepository dailyEquityRepository = mock(DailyEquityRepository.class);
        when(dailyEquityRepository.findById(any(LocalDate.class)))
                .thenReturn(Optional.of(DailyEquity.of(LocalDate.now(), 10_088_806)));
        MutableClock clock = new MutableClock(ZonedDateTime.of(TRADING_DAY, LocalTime.of(10, 0),
                ZoneId.of("Asia/Seoul")).toInstant());   // 장중 — 잔고 API를 부른다
        sut = new KisPositionManager(balanceClient, positionRepository, dailyEquityRepository,
                new TradeResultTracker(mock(PortfolioStateRepository.class)),
                new MarketCalendarService(new MarketCalendarProperties(), clock));
    }

    @Test
    @DisplayName("불일치 판정이 Account에 실린다 — 총자산 값은 그대로")
    void mismatch_is_carried_to_the_account() {
        when(balanceClient.fetchBalance())
                .thenReturn(new BalanceSnapshot(10_890_158, 10_088_806, List.of(), MISMATCH));

        Account account = sut.snapshotAccount();

        assertThat(account.isEquityMismatch()).isTrue();
        assertThat(account.getEquityCheck()).isSameAs(MISMATCH);
        assertThat(account.getTotalAssetValue()).isEqualTo(10_890_158);
        assertThat(account.isFresh()).isTrue();
    }

    @Test
    @DisplayName("일치 판정도 그대로 실린다")
    void match_is_carried_to_the_account() {
        EquityCrossCheck match = EquityCrossCheck.evaluate(10_088_806, OptionalDouble.of(10_088_806), List.of());
        when(balanceClient.fetchBalance())
                .thenReturn(new BalanceSnapshot(10_088_806, 10_088_806, List.of(), match));

        Account account = sut.snapshotAccount();

        assertThat(account.getEquityCheck().verdict()).isEqualTo(Verdict.MATCH);
        assertThat(account.isEquityMismatch()).isFalse();
    }

    @Test
    @DisplayName("기존 3인자 스냅샷은 판정 불가 Account가 된다 — 예전 동작 그대로")
    void three_arg_snapshot_gives_an_unchecked_account() {
        when(balanceClient.fetchBalance()).thenReturn(new BalanceSnapshot(10_890_158, 10_088_806, List.of()));

        Account account = sut.snapshotAccount();

        assertThat(account.getEquityCheck().verdict()).isEqualTo(Verdict.UNCHECKED);
        assertThat(account.isEquityMismatch()).isFalse();
    }

    @Test
    @DisplayName("3초 캐시 안의 재조회도 같은 판정을 돌려준다")
    void cached_snapshot_keeps_the_verdict() {
        when(balanceClient.fetchBalance())
                .thenReturn(new BalanceSnapshot(10_890_158, 10_088_806, List.of(), MISMATCH));

        sut.snapshotAccount();
        Account cached = sut.snapshotAccount();

        assertThat(cached.isEquityMismatch()).isTrue();
    }

    @Test
    @DisplayName("성공 이력 없이 API가 실패하면 DB 폴백 — 낡음 + 판정 불가(뜻 그대로)")
    void db_fallback_is_stale_and_unchecked() {
        when(balanceClient.fetchBalance()).thenThrow(new IllegalStateException("KIS 장애"));
        when(positionRepository.findAll()).thenReturn(List.of());

        Account account = sut.snapshotAccount();

        assertThat(account.isFresh()).isFalse();
        assertThat(account.getEquityCheck().verdict()).isEqualTo(Verdict.UNCHECKED);
    }
}
