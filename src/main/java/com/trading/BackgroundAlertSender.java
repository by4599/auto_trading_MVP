package com.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 호출자를 막지 않고 메시지를 순서대로 내보내는 단일 스레드 대기열 (감사 M-2, 2026-09-21 →
 * 2026-10-10 일반화해 텔레그램 전송 전체로 확대, BACKLOG [2026-09-21]).
 *
 * <p>왜 필요한가: {@code @Scheduled} 기본 스케줄러는 스레드 1개이고 그 위에 {@code RiskMonitor}
 * (MDD 강제청산)·{@code StopLossMonitor}(손절)가 함께 돈다. 그 자리에서 동기 HTTP(connect 3s +
 * read 5s)를 하면 <b>손절 감시가 최대 8초 멈춘다.</b> 그래서 전송만 전용 스레드로 떼어 낸다.
 * <b>풀 크기를 늘리는 방식은 쓰지 않는다</b> — 직렬이라 안전했던 감시들이 서로 동시에 돌기 시작한다.
 *
 * <p>보장 4가지:
 * <ol>
 *   <li><b>즉시 반환</b> — {@link #send}는 대기열에 넣고 바로 돌아온다. 전달이 막혀도 기다리지 않는다</li>
 *   <li><b>순서 유지</b> — 일꾼 1개가 선입선출로 처리한다. 한가해도 일꾼을 반납하지 않는다
 *       (반납을 허용하면 일꾼을 다시 만드는 순간 동시에 들어온 두 건이 뒤바뀔 수 있다)</li>
 *   <li><b>대기열 상한</b> — 넘치면 본문과 함께 WARN을 남기고 버린다(상대가 죽어 있을 때 메모리 보호)</li>
 *   <li><b>종료 정리</b> — {@link #close}는 새 메시지를 막고, 받은 것은 정해진 시간 안에서 마저 보낸 뒤
 *       남은 것은 끊고 버린다. 일꾼은 데몬이라 앱 종료를 붙잡지 않는다</li>
 * </ol>
 *
 * <p>스프링 빈이 아니다 — 쓰는 쪽이 직접 만들고 {@link #close()}로 닫는다. 빈으로 두면 다른 곳이
 * 무심코 끌어다 쓰면서 경계가 흐려진다. 사용처: {@link TelegramNotifier}(모든 텔레그램 전송) ·
 * {@code EvidenceBasedPeakEquityCalibrator}(전고점 알림 — 알림 구현체가 무엇이든 감시 스레드를 지키는 이중 안전장치).
 */
public final class BackgroundAlertSender implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BackgroundAlertSender.class);

    /** 기본 대기열 상한 — 넘치면 버리고 로그만 남긴다(알림이 메모리를 먹지 않게) */
    public static final int DEFAULT_QUEUE_CAPACITY = 32;

    /** 종료 시 남은 메시지를 마저 보내며 기다리는 최대 시간 — 텔레그램 한 건의 최악(8초)보다 짧게 끊는다 */
    static final Duration DEFAULT_CLOSE_WAIT = Duration.ofSeconds(3);

    private final Consumer<String> delivery;
    private final String threadName;
    private final Duration closeWait;
    private final ThreadPoolExecutor executor;

    public BackgroundAlertSender(Consumer<String> delivery, String threadName) {
        this(delivery, threadName, DEFAULT_QUEUE_CAPACITY);
    }

    public BackgroundAlertSender(Consumer<String> delivery, String threadName, int queueCapacity) {
        this(delivery, threadName, queueCapacity, DEFAULT_CLOSE_WAIT);
    }

    /** 테스트 전용 — 종료 대기 시간을 줄여 "막힌 전달을 끊고 돌아오는가"를 빠르게 본다 */
    BackgroundAlertSender(Consumer<String> delivery, String threadName, int queueCapacity, Duration closeWait) {
        this.delivery = delivery;
        this.threadName = threadName;
        this.closeWait = closeWait;
        // 일꾼 1개 고정(반납 없음) + 상한 있는 선입선출 대기열. 넘치면 execute()가 거부 예외를 던진다.
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread t = new Thread(runnable, threadName);
                    t.setDaemon(true);   // 전송이 남아 있어도 종료를 막지 않는다
                    return t;
                });
    }

    /**
     * 즉시 반환한다 — 실제 전달은 전용 스레드에서 일어나고, 실패는 로그로 삼킨다.
     *
     * @return 대기열에 넣었으면 true, 가득 찼거나 이미 닫혀서 버렸으면 false (버린 건은 본문째 WARN)
     */
    public boolean send(String message) {
        try {
            executor.execute(() -> deliver(message));
            return true;
        } catch (RejectedExecutionException e) {
            String reason = executor.isShutdown() ? "이미 닫힘" : "대기열 가득 참";
            log.warn("[{}] 알림을 버렸다({}): {}", threadName, reason, message);
            return false;
        }
    }

    private void deliver(String message) {
        try {
            delivery.accept(message);
        } catch (Exception e) {
            // 알림 실패는 매매·감시에 절대 전파하지 않는다
            log.warn("[{}] 알림 전송 실패: {}", threadName, e.getMessage());
        }
    }

    /** 새 메시지를 막고 받은 것은 {@code closeWait} 안에서 마저 보낸다 — 시간이 지나면 끊고 남은 건수를 남긴다 */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (executor.awaitTermination(closeWait.toMillis(), TimeUnit.MILLISECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int dropped = executor.shutdownNow().size();
        log.warn("[{}] 종료 대기 {}ms 안에 다 보내지 못했다 — 진행 중 1건을 끊고 대기 {}건을 버린다",
                threadName, closeWait.toMillis(), dropped);
    }
}
