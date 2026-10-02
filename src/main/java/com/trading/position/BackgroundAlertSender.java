package com.trading.position;

import com.trading.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 감시 스레드를 막지 않고 알림을 내보내는 작은 전송기 (감사 M-2, 2026-09-21).
 *
 * <p>왜 필요한가: 전고점 경신 알림은 {@code ShadowPortfolio.tick()}의 1초 감시 루프 위에서
 * 불린다. 스프링 기본 스케줄러 풀은 1개라 그 스레드에 {@code RiskMonitor}(MDD 강제청산)·
 * {@code StopLossMonitor}(손절)가 함께 올라가 있다 — 동기 HTTP(connect 3s + read 5s)를 그
 * 자리에서 하면 <b>손절 감시가 최대 8초 멈춘다.</b> 그래서 전송만 전용 스레드로 떼어 낸다.
 *
 * <p><b>풀 크기를 늘리는 방식은 쓰지 않는다</b> — 지금까지 직렬이라 안전했던 감시들이 서로
 * 동시에 돌기 시작한다. 여기서 막는 것은 "이번에 새로 더한 블로킹"뿐이고, 기존 동기 전송
 * 5곳({@code RiskMonitor} 3 · {@code ShadowPortfolioReconciler} 2)은 건드리지 않는다
 * (BACKLOG [2026-09-21] 별건).
 *
 * <p>스프링 빈이 아니다 — 쓰는 쪽이 직접 만들고 {@link #close()}로 닫는다. 빈으로 두면
 * 다른 곳이 무심코 끌어다 쓰면서 위 경계가 흐려진다.
 */
final class BackgroundAlertSender implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BackgroundAlertSender.class);

    /** 대기열 상한 — 넘치면 버리고 로그만 남긴다(알림이 메모리를 먹지 않게) */
    private static final int QUEUE_CAPACITY = 32;
    private static final long IDLE_TIMEOUT_SEC = 30;

    private final NotificationService notifier;
    private final ExecutorService executor;

    BackgroundAlertSender(NotificationService notifier, String threadName) {
        this.notifier = notifier;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, IDLE_TIMEOUT_SEC, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread t = new Thread(runnable, threadName);
                    t.setDaemon(true);   // 전송이 남아 있어도 종료를 막지 않는다
                    return t;
                },
                (runnable, p) -> log.warn("[{}] 알림 대기열이 가득 차 한 건을 버렸다", threadName));
        pool.allowCoreThreadTimeOut(true);   // 한가하면 스레드도 반납한다
        this.executor = pool;
    }

    /** 즉시 반환한다 — 실제 전송은 전용 스레드에서 일어나고, 실패는 로그로 삼킨다 */
    void send(String message) {
        try {
            executor.execute(() -> {
                try {
                    notifier.sendCritical(message);
                } catch (Exception e) {
                    // 알림 실패는 매매·감시에 절대 전파하지 않는다
                    log.warn("[BackgroundAlertSender] 알림 전송 실패: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[BackgroundAlertSender] 알림을 대기열에 넣지 못했다: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
