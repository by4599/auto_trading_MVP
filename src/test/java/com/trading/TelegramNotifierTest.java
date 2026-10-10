package com.trading;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.trading.backtest.MutableClock;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 텔레그램 알림 — 판정(토큰·장중 창)은 호출 스레드에서, HTTP는 전용 대기열에서.
 *
 * 왜 중요한가: 호출부 대부분이 스케줄러 스레드 1개 위의 손절·강제청산 감시다. 전송이 그 자리에서
 * 최대 8초(connect 3 + read 5) 걸리면 그동안 감시가 멈춘다(BACKLOG [2026-09-21]).
 * 실제 HTTP 대신 가짜 전송기를 끼우고, 시장 달력은 실객체 + 움직이는 시계로 쓴다.
 *
 * 날짜 사실관계 (시스템 도구로 검증): 2026-08-20 목요일(거래일).
 * 각 테스트 10초 상한 — 전송이 동기로 되돌아가면 무한 대기 대신 실패로 드러나게 한다.
 */
@DisplayName("TelegramNotifier — 감시 스레드를 막지 않는 텔레그램 전송")
@Timeout(10)
class TelegramNotifierTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate THU_0820 = LocalDate.of(2026, 8, 20);

    private final TelegramProperties props = new TelegramProperties();
    private final MutableClock clock = new MutableClock(THU_0820.atTime(10, 0).atZone(KST).toInstant());
    private final MarketCalendarService calendar =
            new MarketCalendarService(new MarketCalendarProperties(), clock);   // 휴장일 비움 = 토·일만 휴장
    private final List<TelegramNotifier> created = new ArrayList<>();

    @BeforeEach
    void configure() {
        props.setBotToken("test-token");
        props.setChatId("42");
    }

    @AfterEach
    void closeAll() {
        created.forEach(TelegramNotifier::shutdownSender);
    }

    private TelegramNotifier notifier(Consumer<String> poster) {
        TelegramNotifier notifier = new TelegramNotifier(props, calendar, poster);
        created.add(notifier);
        return notifier;
    }

    /** 테스트가 영원히 걸리지 않게 상한을 둔 대기 — 끼어들면(interrupt) 바로 돌아온다 */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("전송이 막혀 있어도 sendCritical은 즉시 돌아온다 — 손절·청산 감시 스레드를 붙잡지 않는다")
    void send_returns_while_http_is_blocked() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<String> posterThread = new AtomicReference<>();
        TelegramNotifier sut = notifier(text -> {
            posterThread.set(Thread.currentThread().getName());
            awaitQuietly(release);
            delivered.countDown();
        });

        AtomicReference<String> callerThread = new AtomicReference<>();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            callerThread.set(Thread.currentThread().getName());
            sut.sendCritical("🚨 [RiskMonitor] 강제청산을 개시합니다");
        });

        release.countDown();
        assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(posterThread.get()).isNotEqualTo(callerThread.get());
    }

    @Test
    @DisplayName("받은 순서대로 보낸다 — 체결 알림(send)과 경보(sendCritical)가 한 줄로 선다")
    void delivers_in_order() {
        List<String> posted = new CopyOnWriteArrayList<>();
        TelegramNotifier sut = notifier(posted::add);

        sut.sendCritical("🚨 강제청산 개시");
        sut.send("[체결완료] SELL 005930 3주");
        sut.sendCritical("✅ 강제청산 완수");
        sut.shutdownSender();   // 남은 것을 마저 보내고 닫는다

        assertThat(posted).containsExactly("🚨 강제청산 개시", "[체결완료] SELL 005930 3주", "✅ 강제청산 완수");
    }

    @Test
    @DisplayName("장중 창은 '받은 시각' 기준 — 15:29:59에 받은 건 늦게 전달돼도 나가고, 15:31에 받은 건 대기열에도 안 들어간다")
    void market_hours_gate_is_checked_when_the_message_arrives() {
        clock.setTo(THU_0820, LocalTime.of(15, 29, 59));
        CountDownLatch release = new CountDownLatch(1);
        List<String> posted = new CopyOnWriteArrayList<>();
        TelegramNotifier sut = notifier(text -> {
            awaitQuietly(release);
            posted.add(text);
        });

        sut.send("창 안에서 받은 알림");
        clock.setTo(THU_0820, LocalTime.of(15, 31));
        sut.send("창 밖에서 받은 알림");
        release.countDown();
        sut.shutdownSender();

        assertThat(posted).containsExactly("창 안에서 받은 알림");
    }

    @Test
    @DisplayName("토큰이 비어 있으면 아무것도 보내지 않는다 (개발 환경 graceful degradation 유지)")
    void blank_token_sends_nothing() {
        props.setBotToken("");
        List<String> posted = new CopyOnWriteArrayList<>();
        TelegramNotifier sut = notifier(posted::add);

        sut.sendCritical("보내면 안 되는 알림");
        sut.shutdownSender();

        assertThat(posted).isEmpty();
    }

    @Test
    @DisplayName("한 건이 실패해도 예외가 호출자에게 새지 않고, 다음 건은 나간다")
    void failure_is_swallowed_and_next_message_goes_out() {
        List<String> posted = new CopyOnWriteArrayList<>();
        TelegramNotifier sut = notifier(text -> {
            if (text.equals("boom")) throw new IllegalStateException("텔레그램 500");
            posted.add(text);
        });

        assertThatCode(() -> {
            sut.send("boom");
            sut.send("다음");
        }).doesNotThrowAnyException();
        sut.shutdownSender();

        assertThat(posted).containsExactly("다음");
    }

    @Test
    @DisplayName("전송 실패 로그에 봇 토큰이 남지 않는다 — 예외 메시지 속 요청 URL의 토큰을 가린다 (42_audit M-2)")
    void failure_log_masks_the_bot_token() {
        String token = "123456789:AAEhBP0av28yP8Eu-xyz_ABCdef";
        Logger logger = (Logger) LoggerFactory.getLogger(TelegramNotifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            TelegramNotifier sut = notifier(text -> {
                throw new IllegalStateException("I/O error on POST request for \"https://api.telegram.org/bot"
                        + token + "/sendMessage\": Read timed out",
                        new IllegalStateException("POST https://api.telegram.org/bot" + token + "/sendMessage"));
            });
            sut.send("알림");
            sut.shutdownSender();
        } finally {
            logger.detachAppender(appender);
        }

        List<String> logged = appender.list.stream().map(TelegramNotifierTest::fullText).toList();
        assertThat(logged).isNotEmpty();
        assertThat(logged).noneMatch(line -> line.contains(token));
        assertThat(logged).anyMatch(line -> line.contains("bot***"));
    }

    /** 로그 한 건에 남는 글자 전부 — 형식화된 메시지와 예외(원인 사슬 포함) 메시지 */
    private static String fullText(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(event.getFormattedMessage());
        for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            sb.append('\n').append(t.getClassName()).append(": ").append(t.getMessage());
        }
        return sb.toString();
    }

    @Test
    @DisplayName("텔레그램이 응답하지 않으면 대기열 상한까지만 쌓고 나머지는 버린다 — 메모리를 지킨다")
    void queue_is_bounded_while_telegram_is_stuck() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        List<String> posted = new CopyOnWriteArrayList<>();
        TelegramNotifier sut = notifier(text -> {
            firstStarted.countDown();
            awaitQuietly(release);
            posted.add(text);
        });

        sut.send("진행 중");
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        int overflow = 5;
        IntStream.rangeClosed(1, TelegramNotifier.QUEUE_CAPACITY + overflow).forEach(i -> sut.send("대기 " + i));
        release.countDown();
        sut.shutdownSender();

        assertThat(posted).hasSize(1 + TelegramNotifier.QUEUE_CAPACITY);
        assertThat(posted.get(0)).isEqualTo("진행 중");
        assertThat(posted.get(posted.size() - 1)).isEqualTo("대기 " + TelegramNotifier.QUEUE_CAPACITY);
    }
}
