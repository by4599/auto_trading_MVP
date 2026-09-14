package com.trading.scheduler;

import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.BalanceClient;
import com.trading.position.DailyEquity;
import com.trading.position.DailyEquityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 일별 순손익 원장 마감 기록.
 *
 * Java 25 인라인 Mockito 제약에 따라 인터페이스(BalanceClient·DailyEquityRepository)만
 * 목으로 만들고, MarketCalendarService·KisProperties는 실객체로 조립한다.
 *
 * 날짜 사실관계 (시스템 도구로 검증): 2026-09-14 월 / 2026-09-13 일
 */
@DisplayName("DailyPnlRecorder — 장 마감 후 총자산·예수금 기록")
class DailyPnlRecorderTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate MON_0914 = LocalDate.of(2026, 9, 14);
    private static final LocalDate SUN_0913 = LocalDate.of(2026, 9, 13);

    private final BalanceClient balanceClient = mock(BalanceClient.class);
    private final DailyEquityRepository equityRepo = mock(DailyEquityRepository.class);

    private DailyPnlRecorder sut() {
        return sutAt(15, 29);   // 실제 스케줄 시각 — 장 마감(15:30) 직전, 아직 장중
    }

    private DailyPnlRecorder sutAt(int hour, int minute) {
        Clock clock = Clock.fixed(MON_0914.atTime(hour, minute).atZone(KST).toInstant(), KST);
        MarketCalendarService calendar =
                new MarketCalendarService(new MarketCalendarProperties(), clock);
        KisProperties kis = new KisProperties();
        kis.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        kis.setAppkey("k");
        kis.setSecretkey("s");
        kis.setAccountNo("50000000-01");
        return new DailyPnlRecorder(balanceClient, equityRepo, calendar, kis, clock);
    }

    private void givenBalance(double totalAssetValue, double deposit) {
        when(balanceClient.fetchBalance())
                .thenReturn(new BalanceClient.BalanceSnapshot(totalAssetValue, deposit, List.of()));
    }

    @Test
    @DisplayName("장 마감 뒤(15:40)에는 잔고를 부르지 않는다 — 장외 실패가 다음 아침 SAFE_MODE를 부른다")
    void never_calls_balance_after_market_close() {
        sutAt(15, 40).recordCloseFor(MON_0914);

        verify(balanceClient, never()).fetchBalance();
        verify(equityRepo, never()).save(any());
    }

    @Test
    @DisplayName("시작 기록이 있는 거래일 → 마감 총자산·예수금 기록, 순손익 = 마감 - 시작")
    void records_close_and_computes_net_pnl() {
        DailyEquity ledger = DailyEquity.of(MON_0914, 50_000_000, 49_000_000);
        when(equityRepo.findById(MON_0914)).thenReturn(Optional.of(ledger));
        givenBalance(49_650_000, 49_650_000);   // 전량 청산된 마감 — 총자산 = 예수금

        sut().recordCloseFor(MON_0914);

        verify(equityRepo).save(ledger);
        assertThat(ledger.isClosed()).isTrue();
        assertThat(ledger.getEndEquity()).isEqualTo(49_650_000.0);
        assertThat(ledger.getEndDeposit()).isEqualTo(49_650_000.0);
        assertThat(ledger.getNetPnl()).isEqualTo(-350_000.0);
        assertThat(ledger.getCashDelta()).isEqualTo(650_000.0);
    }

    @Test
    @DisplayName("휴장일(일요일) → 잔고를 부르지도, 기록하지도 않는다")
    void skips_on_holiday() {
        sut().recordCloseFor(SUN_0913);

        verify(balanceClient, never()).fetchBalance();
        verify(equityRepo, never()).save(any(DailyEquity.class));
    }

    @Test
    @DisplayName("시작 기록이 없는 날(앱이 꺼져 있던 날) → 추측해 채우지 않는다")
    void skips_when_start_equity_missing() {
        when(equityRepo.findById(MON_0914)).thenReturn(Optional.empty());

        sut().recordCloseFor(MON_0914);

        verify(balanceClient, never()).fetchBalance();
        verify(equityRepo, never()).save(any(DailyEquity.class));
    }

    @Test
    @DisplayName("이미 마감이 찍힌 날 → 다시 부르지 않고 값도 덮어쓰지 않는다")
    void does_not_overwrite_closed_day() {
        DailyEquity ledger = DailyEquity.of(MON_0914, 50_000_000, 49_000_000);
        ledger.recordClose(49_650_000, 49_650_000);
        when(equityRepo.findById(MON_0914)).thenReturn(Optional.of(ledger));

        sut().recordCloseFor(MON_0914);

        verify(balanceClient, never()).fetchBalance();
        verify(equityRepo, never()).save(any(DailyEquity.class));
        assertThat(ledger.getEndEquity()).isEqualTo(49_650_000.0);
    }

    @Test
    @DisplayName("잔고 조회 실패 → 마감 기록 보류 (예외를 밖으로 내보내지 않는다)")
    void holds_record_when_balance_api_fails() {
        when(equityRepo.findById(MON_0914))
                .thenReturn(Optional.of(DailyEquity.of(MON_0914, 50_000_000, 49_000_000)));
        when(balanceClient.fetchBalance()).thenThrow(new IllegalStateException("KIS 장애"));

        sut().recordCloseFor(MON_0914);

        verify(equityRepo, never()).save(any(DailyEquity.class));
    }

    @Test
    @DisplayName("총자산 0 이하 → 잘못된 값으로 원장을 더럽히지 않는다")
    void holds_record_when_total_asset_is_not_positive() {
        when(equityRepo.findById(MON_0914))
                .thenReturn(Optional.of(DailyEquity.of(MON_0914, 50_000_000, 49_000_000)));
        givenBalance(0, 0);

        sut().recordCloseFor(MON_0914);

        verify(equityRepo, never()).save(any(DailyEquity.class));
    }

    @Test
    @DisplayName("KIS 자격증명 미설정 → 아무 일도 하지 않는다")
    void skips_when_kis_not_configured() {
        Clock clock = Clock.fixed(MON_0914.atTime(15, 40).atZone(KST).toInstant(), KST);
        MarketCalendarService calendar =
                new MarketCalendarService(new MarketCalendarProperties(), clock);
        DailyPnlRecorder unconfigured = new DailyPnlRecorder(
                balanceClient, equityRepo, calendar, new KisProperties(), clock);

        unconfigured.recordCloseFor(MON_0914);

        verify(balanceClient, never()).fetchBalance();
        verify(equityRepo, never()).save(any(DailyEquity.class));
    }
}
