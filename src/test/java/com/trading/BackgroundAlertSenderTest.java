package com.trading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 감시 스레드를 막지 않는 알림 대기열 — 텔레그램 전송 전부가 이 위로 지나간다(BACKLOG [2026-09-21]).
 *
 * 실제 HTTP 대신 {@code Consumer<String>} 가짜 전달기로 "막힘·순서·상한·종료"만 검증한다.
 * 각 테스트 10초 상한 — 동시성 결함이 무한 대기로 숨지 않고 실패로 드러나게 한다.
 */
@DisplayName("BackgroundAlertSender — 즉시 반환 · 순서 유지 · 대기열 상한 · 종료 정리")
@Timeout(10)
class BackgroundAlertSenderTest {

    private static final Duration SHORT_CLOSE_WAIT = Duration.ofMillis(200);

    private final List<BackgroundAlertSender> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(BackgroundAlertSender::close);
    }

    private BackgroundAlertSender sender(Consumer<String> delivery, int capacity, Duration closeWait) {
        BackgroundAlertSender sender = new BackgroundAlertSender(delivery, "test-sender", capacity, closeWait);
        opened.add(sender);
        return sender;
    }

    /** 테스트가 영원히 걸리지 않게 상한을 둔 대기 — 끼어들면(interrupt) 바로 돌아온다 */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String[] numbered(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "알림 " + i).toArray(String[]::new);
    }

    @Test
    @DisplayName("전달이 막혀 있어도 send는 즉시 true로 돌아오고, 전달은 다른(데몬) 스레드에서 일어난다")
    void send_does_not_wait_for_delivery() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<Thread> deliveryThread = new AtomicReference<>();
        BackgroundAlertSender sut = sender(message -> {
            deliveryThread.set(Thread.currentThread());
            awaitQuietly(release);
            delivered.countDown();
        }, 8, SHORT_CLOSE_WAIT);

        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicBoolean accepted = new AtomicBoolean();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            callerThread.set(Thread.currentThread());
            accepted.set(sut.send("🚨 강제청산 개시"));
        });

        assertThat(accepted).isTrue();
        release.countDown();
        assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(deliveryThread.get()).isNotSameAs(callerThread.get());
        assertThat(deliveryThread.get().isDaemon()).isTrue();   // 남은 전송이 앱 종료를 붙잡지 않는다
    }

    @Test
    @DisplayName("받은 순서대로 전달한다 — 일꾼 스레드 1개가 선입선출로 처리")
    void preserves_order() {
        List<String> delivered = new CopyOnWriteArrayList<>();
        BackgroundAlertSender sut = sender(delivered::add, 64, Duration.ofSeconds(3));

        for (String message : numbered(50)) sut.send(message);
        sut.close();

        assertThat(delivered).containsExactly(numbered(50));
    }

    @Test
    @DisplayName("대기열 상한을 넘으면 false를 돌려주고 버린다 — 진행 중 1건 + 대기 N건까지만 받는다")
    void drops_when_queue_is_full() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        List<String> delivered = new CopyOnWriteArrayList<>();
        BackgroundAlertSender sut = sender(message -> {
            firstStarted.countDown();
            awaitQuietly(release);
            delivered.add(message);
        }, 2, Duration.ofSeconds(3));

        assertThat(sut.send("진행 중")).isTrue();
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(sut.send("대기 1")).isTrue();
        assertThat(sut.send("대기 2")).isTrue();
        assertThat(sut.send("넘침")).isFalse();

        release.countDown();
        sut.close();
        assertThat(delivered).containsExactly("진행 중", "대기 1", "대기 2");
    }

    @Test
    @DisplayName("close는 받아 둔 알림을 마저 보낸 뒤에 돌아온다")
    void close_drains_pending_messages() {
        List<String> delivered = new CopyOnWriteArrayList<>();
        BackgroundAlertSender sut = sender(message -> {
            sleepQuietly(20);
            delivered.add(message);
        }, 16, Duration.ofSeconds(3));

        for (String message : numbered(5)) sut.send(message);
        sut.close();

        assertThat(delivered).containsExactly(numbered(5));
    }

    @Test
    @DisplayName("close는 막힌 전달을 무한정 기다리지 않는다 — 대기 시간이 지나면 끊고(interrupt) 돌아온다")
    void close_is_bounded_and_interrupts_stuck_delivery() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        BackgroundAlertSender sut = sender(message -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();   // 텔레그램이 응답하지 않는 상황
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        }, 8, SHORT_CLOSE_WAIT);
        sut.send("막힘");
        sut.send("뒤에서 대기");
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        assertTimeoutPreemptively(Duration.ofSeconds(2), sut::close);

        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("닫힌 뒤의 send는 예외 없이 false — 종료 중 알림이 매매 흐름을 깨지 않는다")
    void send_after_close_is_rejected_quietly() {
        List<String> delivered = new CopyOnWriteArrayList<>();
        BackgroundAlertSender sut = sender(delivered::add, 8, SHORT_CLOSE_WAIT);
        sut.close();

        AtomicBoolean accepted = new AtomicBoolean(true);
        assertThatCode(() -> accepted.set(sut.send("종료 후"))).doesNotThrowAnyException();

        assertThat(accepted).isFalse();
        assertThat(delivered).isEmpty();
    }

    @Test
    @DisplayName("전달 실패는 삼키고, 일꾼은 살아남아 다음 건을 보낸다")
    void delivery_failure_does_not_stop_the_worker() {
        List<String> delivered = new CopyOnWriteArrayList<>();
        BackgroundAlertSender sut = sender(message -> {
            if (message.equals("boom")) throw new IllegalStateException("텔레그램 500");
            delivered.add(message);
        }, 8, Duration.ofSeconds(3));

        sut.send("boom");
        sut.send("다음");
        sut.close();

        assertThat(delivered).containsExactly("다음");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
