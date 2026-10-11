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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
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
    private static final LocalDate FRI_0911 = LocalDate.of(2026, 9, 11);
    private static final LocalDate THU_0910 = LocalDate.of(2026, 9, 10);

    private final BalanceClient balanceClient = mock(BalanceClient.class);
    private final DailyEquityRepository equityRepo = mock(DailyEquityRepository.class);
    private final com.trading.NotificationService notifier =
            mock(com.trading.NotificationService.class);

    private DailyPnlRecorder sut() {
        return sutAt(15, 29);   // 실제 스케줄 시각 — 장 마감(15:30) 직전, 아직 장중
    }

    private DailyPnlRecorder sutAt(int hour, int minute) {
        return sutOn(MON_0914, hour, minute);
    }

    private DailyPnlRecorder sutOn(LocalDate date, int hour, int minute) {
        return sutWith(Clock.fixed(date.atTime(hour, minute).atZone(KST).toInstant(), KST));
    }

    private DailyPnlRecorder sutWith(Clock clock) {
        MarketCalendarService calendar =
                new MarketCalendarService(new MarketCalendarProperties(), clock);
        KisProperties kis = new KisProperties();
        kis.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        kis.setAppkey("k");
        kis.setSecretkey("s");
        kis.setAccountNo("50000000-01");
        return new DailyPnlRecorder(balanceClient, equityRepo, calendar, kis, notifier, clock);
    }

    /**
     * 테스트가 직접 전진시키는 시계. 실측 사고(15:29 예약이 15:30:21에 발화한 2026-09-08)를
     * 재현하려면 "게이트 통과 후 전송 직전 사이에 시간이 흐르는" 상황이 필요하다.
     * Java 25 인라인 Mockito 제약 때문에 Clock은 목이 아니라 실객체로 만든다.
     */
    private static final class SteppingClock extends Clock {
        private Instant now;
        SteppingClock(Instant start) { this.now = start; }
        void advance(Duration d)     { this.now = now.plus(d); }
        @Override public ZoneId getZone()            { return KST; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant()           { return now; }
    }

    /** 리포지토리 파생 쿼리(마감됨 + 미알림, 날짜 오름차순)를 목 위에서 그대로 흉내 낸다 */
    private void givenLedgerStore(List<DailyEquity> store) {
        when(equityRepo.findByEndEquityNotNullAndNotifiedAtNullOrderByTradeDateAsc())
                .thenAnswer(inv -> store.stream()
                        .filter(DailyEquity::isClosed)
                        .filter(d -> !d.isNotified())
                        .sorted(Comparator.comparing(DailyEquity::getTradeDate))
                        .toList());
    }

    private static DailyEquity closedLedger(LocalDate date, double start, double end) {
        DailyEquity e = DailyEquity.of(date, start, start);
        e.recordClose(end, end);
        return e;
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
        verify(notifier, never()).sendCritical(any());
    }

    @Test
    @DisplayName("마감이 기록되면 그날 손익을 알림으로 보낸다 — 금액·등락률·총자산 변화를 담는다")
    void sends_notification_on_close() {
        DailyEquity ledger = DailyEquity.of(MON_0914, 10_000_000, 9_000_000);
        when(equityRepo.findById(MON_0914)).thenReturn(Optional.of(ledger));
        givenBalance(10_120_000, 10_120_000);

        sut().recordCloseFor(MON_0914);

        org.mockito.ArgumentCaptor<String> msg = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifier).sendCritical(msg.capture());
        assertThat(msg.getValue())
                .contains("120,000")        // 순손익 절대값
                .contains("벌었습니다")
                .contains("1.20%");         // 120,000 / 10,000,000
    }

    @Test
    @DisplayName("알림 전송이 실패해도 원장 기록은 되돌아가지 않는다 — 알림은 곁다리다")
    void notification_failure_does_not_break_ledger() {
        DailyEquity ledger = DailyEquity.of(MON_0914, 10_000_000, 9_000_000);
        when(equityRepo.findById(MON_0914)).thenReturn(Optional.of(ledger));
        givenBalance(9_900_000, 9_900_000);
        org.mockito.Mockito.doThrow(new RuntimeException("telegram down"))
                .when(notifier).sendCritical(any());

        sut().recordCloseFor(MON_0914);

        verify(equityRepo).save(ledger);
        assertThat(ledger.isClosed()).isTrue();
        assertThat(ledger.getNetPnl()).isEqualTo(-100_000.0);
        // 보냈다고 찍지 않는다 — 다음 거래일 아침에 다시 시도되어야 한다
        assertThat(ledger.getNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("시작 기록이 있는 거래일 → 마감 총자산·예수금 기록, 순손익 = 마감 - 시작")
    void records_close_and_computes_net_pnl() {
        DailyEquity ledger = DailyEquity.of(MON_0914, 50_000_000, 49_000_000);
        when(equityRepo.findById(MON_0914)).thenReturn(Optional.of(ledger));
        givenBalance(49_650_000, 49_650_000);   // 전량 청산된 마감 — 총자산 = 예수금

        sut().recordCloseFor(MON_0914);

        // 원장 기록 1회 + 알림 보낸 사실 기록 1회
        verify(equityRepo, times(2)).save(ledger);
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
                balanceClient, equityRepo, calendar, new KisProperties(), notifier, clock);

        unconfigured.recordCloseFor(MON_0914);

        verify(balanceClient, never()).fetchBalance();
        verify(equityRepo, never()).save(any(DailyEquity.class));
    }

    // ── 전송 창 경계 (13_audit M-1) ──────────────────────────────────────────

    @Test
    @DisplayName("창을 넘기면 보내지 않고 notifiedAt도 비운다 — 잔고 조회가 15:30을 넘긴 날 (핵심 회귀)")
    void does_not_send_when_window_closed_and_leaves_notified_at_null() {
        SteppingClock clock = new SteppingClock(
                MON_0914.atTime(15, 29, 58).atZone(KST).toInstant());
        DailyEquity ledger = DailyEquity.of(MON_0914, 10_000_000, 9_000_000);
        when(equityRepo.findById(MON_0914)).thenReturn(Optional.of(ledger));
        // 게이트는 15:29:58에 통과하지만 잔고 조회(레이트리밋 ≥1초 + HTTP 최대 10초)가
        // 5초를 먹어 전송 시점엔 15:30:03이다 — 실측 2026-09-08과 같은 모양이다.
        when(balanceClient.fetchBalance()).thenAnswer(inv -> {
            clock.advance(Duration.ofSeconds(5));
            return new BalanceClient.BalanceSnapshot(10_120_000, 10_120_000, List.of());
        });

        List<String> warnings = captureWarnings(() -> sutWith(clock).recordCloseFor(MON_0914));

        verify(notifier, never()).sendCritical(any());
        assertThat(ledger.isClosed()).isTrue();          // 원장은 기록된다
        assertThat(ledger.getNotifiedAt()).isNull();     // 보냈다고 찍지 않는다 → 아침에 이월
        verify(equityRepo, times(1)).save(ledger);       // 원장 1회만 (알림 기록 없음)
        assertThat(warnings).anyMatch(m -> m.contains("보류"));  // 조용히 사라지지 않는다
    }

    @Test
    @DisplayName("창 안에서 보내면 보낸 시각을 원장에 찍는다 — 이월 여부를 가르는 유일한 근거")
    void marks_notified_at_when_sent_inside_window() {
        DailyEquity ledger = DailyEquity.of(MON_0914, 10_000_000, 9_000_000);
        when(equityRepo.findById(MON_0914)).thenReturn(Optional.of(ledger));
        givenBalance(10_120_000, 10_120_000);

        sut().recordCloseFor(MON_0914);

        verify(notifier).sendCritical(any());
        assertThat(ledger.getNotifiedAt()).isEqualTo(MON_0914.atTime(15, 29));
        verify(equityRepo, times(2)).save(ledger);       // 원장 1회 + 알림 기록 1회
    }

    // ── 다음 거래일 아침 이월 발송 ──────────────────────────────────────────

    @Test
    @DisplayName("다음 거래일 아침(09:05) — 못 보낸 마감분을 보내고 어느 날 것인지 밝힌다")
    void morning_catchup_sends_pending_and_marks_notified() {
        DailyEquity friday = closedLedger(FRI_0911, 10_000_000, 10_150_000);
        givenLedgerStore(new ArrayList<>(List.of(friday)));

        sutAt(9, 5).sendPendingFor(MON_0914);

        org.mockito.ArgumentCaptor<String> msg = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifier).sendCritical(msg.capture());
        assertThat(msg.getValue())
                .contains("2026-09-11")     // 어느 날 것인지 분명히
                .contains("150,000")
                .contains("벌었습니다");
        assertThat(friday.getNotifiedAt()).isEqualTo(MON_0914.atTime(9, 5));
        verify(equityRepo).save(friday);
    }

    @Test
    @DisplayName("이미 보낸 마감분은 다시 보내지 않는다 — 아침 이월을 두 번 돌려도 1회")
    void morning_catchup_does_not_resend() {
        DailyEquity friday = closedLedger(FRI_0911, 10_000_000, 10_150_000);
        givenLedgerStore(new ArrayList<>(List.of(friday)));

        sutAt(9, 5).sendPendingFor(MON_0914);
        sutAt(9, 5).sendPendingFor(MON_0914);

        verify(notifier, times(1)).sendCritical(any());
    }

    @Test
    @DisplayName("오늘 마감분은 이월 대상이 아니다 — 오늘 것은 그날 15:29 경로가 맡는다")
    void morning_catchup_ignores_today() {
        DailyEquity today = closedLedger(MON_0914, 10_000_000, 10_010_000);
        givenLedgerStore(new ArrayList<>(List.of(today)));

        sutAt(9, 5).sendPendingFor(MON_0914);

        verify(notifier, never()).sendCritical(any());
        assertThat(today.getNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("휴장일 아침에는 이월 발송도 돌지 않는다")
    void morning_catchup_skips_on_holiday() {
        DailyEquity friday = closedLedger(FRI_0911, 10_000_000, 10_150_000);
        givenLedgerStore(new ArrayList<>(List.of(friday)));

        sutOn(SUN_0913, 9, 5).sendPendingFor(SUN_0913);

        verify(notifier, never()).sendCritical(any());
        verify(equityRepo, never()).save(any(DailyEquity.class));
    }

    @Test
    @DisplayName("개장 전(08:50)에는 이월을 다음 기회로 미룬다 — 창 밖 전송은 조용히 사라진다")
    void morning_catchup_skips_before_open() {
        DailyEquity thursday = closedLedger(THU_0910, 10_000_000, 9_800_000);
        givenLedgerStore(new ArrayList<>(List.of(thursday)));

        sutAt(8, 50).sendPendingFor(MON_0914);

        verify(notifier, never()).sendCritical(any());
        verify(equityRepo, never()).save(any(DailyEquity.class));
        assertThat(thursday.getNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("여러 건 밀리면 최근 5건만 오래된 날짜부터 보낸다 — 잘라낸 건은 로그로 남긴다")
    void morning_catchup_sends_recent_five_oldest_first() {
        List<LocalDate> pendingDays = List.of(
                LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 9), THU_0910, FRI_0911);
        List<DailyEquity> store = new ArrayList<>();
        pendingDays.forEach(d -> store.add(closedLedger(d, 10_000_000, 10_010_000)));
        givenLedgerStore(store);

        List<String> warnings = captureWarnings(() -> sutAt(9, 5).sendPendingFor(MON_0914));

        org.mockito.ArgumentCaptor<String> msg = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifier, times(5)).sendCritical(msg.capture());
        List<String> sent = msg.getAllValues();
        assertThat(sent.get(0)).contains("2026-09-07");   // 오름차순 — 오래된 것부터
        assertThat(sent.get(4)).contains("2026-09-11");
        assertThat(sent).noneMatch(m -> m.contains("2026-09-03") || m.contains("2026-09-04"));
        assertThat(warnings).anyMatch(m -> m.contains("2026-09-03") && m.contains("2026-09-04"));
    }

    @Test
    @DisplayName("도장 저장이 실패해도 예외가 스케줄 메서드 밖으로 새지 않는다 — 알림은 곁다리다")
    void stamp_save_failure_does_not_escape() {
        DailyEquity ledger = DailyEquity.of(MON_0914, 10_000_000, 9_000_000);
        when(equityRepo.findById(MON_0914)).thenReturn(Optional.of(ledger));
        givenBalance(10_100_000, 10_100_000);
        // 원장 저장은 성공, 도장 저장(2회차)에서 DB가 죽는다
        when(equityRepo.save(ledger)).thenReturn(ledger)
                .thenThrow(new RuntimeException("DB down"));

        sut().recordCloseFor(MON_0914);   // 던지면 이 줄에서 테스트가 깨진다

        assertThat(ledger.isClosed()).isTrue();      // 원장은 이미 커밋됐다
    }

    @Test
    @DisplayName("이월 중 한 건이 실패해도 나머지는 계속 보낸다 — 루프가 통째로 죽지 않는다")
    void catchup_continues_after_one_failure() {
        List<DailyEquity> store = new ArrayList<>(List.of(
                closedLedger(THU_0910, 10_000_000, 10_050_000),
                closedLedger(FRI_0911, 10_050_000, 10_120_000)));
        givenLedgerStore(store);
        org.mockito.Mockito.doThrow(new RuntimeException("telegram down"))
                .doNothing()
                .when(notifier).sendCritical(any());

        sutOn(MON_0914, 9, 5).sendPendingFor(MON_0914);   // 던지면 깨진다

        verify(notifier, times(2)).sendCritical(any());   // 둘 다 시도했다
        assertThat(store.get(0).getNotifiedAt()).isNull();      // 실패분은 도장 없음 → 내일 재시도
        assertThat(store.get(1).getNotifiedAt()).isNotNull();   // 성공분만 도장
    }

    /**
     * 경고 로그를 실제로 남기는지 확인한다 — 이 기능의 결함이 "조용한 소실"이었으므로
     * 사람이 알아챌 수 있게 남기는 것 자체가 계약이다.
     */
    private static List<String> captureWarnings(Runnable action) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(DailyPnlRecorder.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
