package com.trading.order;

import com.trading.backtest.MutableClock;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static com.trading.order.OrderCancelClient.CancelOutcome.FAILED;
import static com.trading.order.OrderCancelClient.CancelOutcome.MARKET_CLOSED;
import static com.trading.order.OrderCancelClient.CancelOutcome.NO_OPEN_QTY;
import static com.trading.order.OrderCancelClient.CancelOutcome.SENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 취소 재시도 포기 조건 (2026-09-22 사고 회귀 테스트).
 *
 * 그날 15:24에 낸 주문의 취소가 "모의투자 장종료 입니다."로 거부됐는데 포기 조건이 없어
 * 자정까지 5,983회 재시도했다. 아래 테스트가 그 루프를 고정한다.
 *
 * 목킹은 인터페이스(OrderCancelClient)만 — MarketCalendarService는 실객체 + MutableClock으로
 * 시간을 직접 움직인다 (Java 25 인라인 Mockito 제약, CLAUDE.md 컨벤션).
 */
@DisplayName("CancelRetryGate — 성공할 수 없는 취소는 재시도하지 않는다")
class CancelRetryGateTest {

    private static final String ORD_NO = "0000035161";   // 2026-09-22 실제 주문번호

    private static final LocalDate TUE_2026_09_22 = LocalDate.of(2026, 9, 22);
    private static final LocalDate WED_2026_09_23 = LocalDate.of(2026, 9, 23);
    private static final LocalDate THU_2026_09_24 = LocalDate.of(2026, 9, 24);  // 추석 휴장

    private OrderCancelClient cancelClient;
    private MutableClock clock;
    private CancelRetryGate gate;

    @BeforeEach
    void setUp() {
        cancelClient = mock(OrderCancelClient.class);
        clock = new MutableClock(Instant.parse("2026-09-22T01:00:00Z"));

        MarketCalendarProperties props = new MarketCalendarProperties();
        props.setHolidays(List.of(THU_2026_09_24));
        gate = new CancelRetryGate(cancelClient, new MarketCalendarService(props, clock), clock);
    }

    @Test
    @DisplayName("장중이면 취소를 시도하고 결과를 그대로 돌려준다")
    void attempts_during_market_hours() {
        clock.setTo(TUE_2026_09_22, LocalTime.of(10, 0));
        when(cancelClient.cancelAll(ORD_NO)).thenReturn(SENT);

        assertThat(gate.attemptCancel(ORD_NO)).contains(SENT);
        verify(cancelClient, times(1)).cancelAll(ORD_NO);
    }

    @Test
    @DisplayName("장외에는 취소 호출을 아예 하지 않는다 — 거부될 게 확실한 호출로 실패 카운터를 쌓지 않는다")
    void does_not_call_outside_market_hours() {
        clock.setTo(TUE_2026_09_22, LocalTime.of(15, 34));   // 사고 당시 첫 취소 시도 시각

        assertThat(gate.attemptCancel(ORD_NO)).isEmpty();
        verify(cancelClient, never()).cancelAll(anyString());
    }

    @Test
    @DisplayName("휴장일에는 취소 호출을 하지 않는다")
    void does_not_call_on_holiday() {
        clock.setTo(THU_2026_09_24, LocalTime.of(10, 0));

        assertThat(gate.attemptCancel(ORD_NO)).isEmpty();
        verify(cancelClient, never()).cancelAll(anyString());
    }

    /**
     * 핵심 회귀 — 달력이 장중이라고 해도 KIS가 "장종료"라고 답하면 그날은 끝이다.
     * 폴링을 여러 번 돌려도 취소 호출은 1회여야 한다(사고 당시 5,983회).
     */
    @Test
    @DisplayName("장종료 거부 뒤에는 같은 날 다시 시도하지 않는다 — 폴 20회에 호출 1회")
    void market_closed_is_not_retried_same_day() {
        clock.setTo(TUE_2026_09_22, LocalTime.of(10, 0));
        when(cancelClient.cancelAll(ORD_NO)).thenReturn(MARKET_CLOSED);

        assertThat(gate.attemptCancel(ORD_NO)).contains(MARKET_CLOSED);
        for (int poll = 0; poll < 19; poll++) {
            assertThat(gate.attemptCancel(ORD_NO)).isEmpty();
        }

        verify(cancelClient, times(1)).cancelAll(ORD_NO);
    }

    @Test
    @DisplayName("장종료 로그는 1회만 남는다 — 시간당 750줄이 문제의 일부였다")
    void market_closed_logs_once() {
        clock.setTo(TUE_2026_09_22, LocalTime.of(10, 0));
        when(cancelClient.cancelAll(ORD_NO)).thenReturn(MARKET_CLOSED);

        List<String> warns = captureWarnLogs(() -> {
            for (int poll = 0; poll < 20; poll++) {
                gate.attemptCancel(ORD_NO);
            }
        });

        assertThat(warns).hasSize(1);
        assertThat(warns.getFirst()).contains("장종료").contains(ORD_NO);
    }

    @Test
    @DisplayName("다음 개장 후에는 취소를 다시 시도한다")
    void retries_after_next_open() {
        clock.setTo(TUE_2026_09_22, LocalTime.of(10, 0));
        when(cancelClient.cancelAll(ORD_NO)).thenReturn(MARKET_CLOSED);
        gate.attemptCancel(ORD_NO);

        clock.setTo(TUE_2026_09_22, LocalTime.of(23, 59));         // 같은 날 밤 — 여전히 시도 안 함
        assertThat(gate.attemptCancel(ORD_NO)).isEmpty();

        clock.setTo(WED_2026_09_23, LocalTime.of(8, 59));          // 개장 전 — 아직 시도 안 함
        assertThat(gate.attemptCancel(ORD_NO)).isEmpty();
        verify(cancelClient, times(1)).cancelAll(ORD_NO);

        clock.setTo(WED_2026_09_23, LocalTime.of(9, 0));           // 개장 — 다시 시도한다
        when(cancelClient.cancelAll(ORD_NO)).thenReturn(NO_OPEN_QTY);
        assertThat(gate.attemptCancel(ORD_NO)).contains(NO_OPEN_QTY);
        verify(cancelClient, times(2)).cancelAll(ORD_NO);
    }

    @Test
    @DisplayName("일시적 실패(FAILED)는 억제하지 않는다 — 재시도로 성공할 수 있다")
    void failed_keeps_retrying() {
        clock.setTo(TUE_2026_09_22, LocalTime.of(10, 0));
        when(cancelClient.cancelAll(ORD_NO)).thenReturn(FAILED);

        for (int poll = 0; poll < 3; poll++) {
            assertThat(gate.attemptCancel(ORD_NO)).contains(FAILED);
        }

        verify(cancelClient, times(3)).cancelAll(ORD_NO);
    }

    @Test
    @DisplayName("억제는 주문별이다 — 다른 주문의 취소는 막지 않는다")
    void suppression_is_per_order() {
        clock.setTo(TUE_2026_09_22, LocalTime.of(10, 0));
        when(cancelClient.cancelAll(ORD_NO)).thenReturn(MARKET_CLOSED);
        when(cancelClient.cancelAll("0000099999")).thenReturn(SENT);

        gate.attemptCancel(ORD_NO);
        gate.attemptCancel(ORD_NO);

        assertThat(gate.attemptCancel("0000099999")).contains(SENT);
        verify(cancelClient, times(1)).cancelAll(ORD_NO);
        verify(cancelClient, times(1)).cancelAll("0000099999");
    }

    /** 로그가 실제로 1회만 남는지 확인한다 — 소음 자체가 사고의 일부였다 */
    private static List<String> captureWarnLogs(Runnable action) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(CancelRetryGate.class);
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

    /** Optional 반환 계약 고정 — 빈 값 = "시도하지 않았다"(호출 측은 상태를 건드리지 않는다) */
    @Test
    @DisplayName("시도하지 않으면 빈 Optional — 호출 측이 주문 상태를 바꿀 근거가 없다")
    void empty_means_not_attempted() {
        clock.setTo(TUE_2026_09_22, LocalTime.of(18, 0));

        Optional<OrderCancelClient.CancelOutcome> outcome = gate.attemptCancel(ORD_NO);

        assertThat(outcome).isEmpty();
    }
}
