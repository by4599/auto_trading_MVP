package com.trading.order;

import com.trading.RetryConfig;
import com.trading.position.PositionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * @Retryable + @TransactionalEventListener(AFTER_COMMIT) 연동 검증.
 *
 * 핵심 질문:
 *   낙관적 락 충돌 → @Retryable 재시도(롤백) → 최종 커밋
 *   이 흐름에서 OrderFilledEvent가 정확히 1회만 발행되는가?
 *
 * Spring 동작 원리:
 *   @TransactionalEventListener(AFTER_COMMIT)은 트랜잭션이 커밋된 후에만 리스너를 실행한다.
 *   롤백된 시도에서 publishEvent()를 호출해도 AFTER_COMMIT 리스너는 실행되지 않는다.
 *   따라서 재시도(롤백)가 발생해도 최종 성공 커밋에서만 이벤트가 전달된다.
 *
 * RetryCounter는 Spring Retry가 context에서 RetryListener 빈을 자동 수집하므로
 * @Import만으로 @Retryable 메서드의 재시도 횟수를 관찰할 수 있다.
 */
@DataJpaTest
@ActiveProfiles("paper")
@Import({
    FillStateUpdater.class,
    RetryConfig.class,
    OrderFilledEventTest.FillEventCapture.class,
    OrderFilledEventTest.RetryCounter.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("OrderFilledEvent AFTER_COMMIT 발행 테스트")
class OrderFilledEventTest {

    private static final Logger log = LoggerFactory.getLogger(OrderFilledEventTest.class);

    @Autowired FillStateUpdater        stateUpdater;
    @Autowired OrderHistoryRepository  orderRepo;
    @Autowired PositionRepository      positionRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired FillEventCapture        eventCapture;
    @Autowired RetryCounter            retryCounter;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        eventCapture.clear();
        retryCounter.reset();
    }

    @AfterEach
    void tearDown() {
        // @TestInstance(PER_CLASS) 등으로 컨텍스트가 공유될 경우를 대비해 이중 초기화
        eventCapture.clear();
    }

    // ── 정상 경로 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("applyFill() 단독 성공 → OrderFilledEvent 1회, duringCancel=false")
    void applyFill_success_publishes_one_event() {
        Long id = committed(OrderHistory.accepted("005930", OrderSide.BUY, 1, "EVT-01"));

        try {
            stateUpdater.applyFill(id, 1, 72500.0);

            List<OrderFilledEvent> events = eventCapture.captured();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).stockCode()).isEqualTo("005930");
            assertThat(events.get(0).duringCancel()).isFalse();

        } finally { cleanup(id, "005930"); }
    }

    @Test
    @DisplayName("finalizeAfterCancel() 전량 체결 → OrderFilledEvent 1회, duringCancel=true")
    void finalizeAfterCancel_full_fill_publishes_event_with_duringCancel() {
        Long id = tx.execute(s -> {
            OrderHistory o = orderRepo.save(
                    OrderHistory.accepted("005930", OrderSide.BUY, 100, "EVT-02"));
            o.markCancelRequested();
            return orderRepo.save(o).getId();
        });

        try {
            stateUpdater.finalizeAfterCancel(id, 100, 72500.0);

            List<OrderFilledEvent> events = eventCapture.captured();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).duringCancel()).isTrue();

        } finally { cleanup(id, "005930"); }
    }

    // ── 동시성 경로: OptimisticLock → @Retryable → 이벤트 ≤ 1회 ──────────────

    /**
     * 2 스레드 동시 실행 시 이벤트 중복 발행이 없는지 검증한다.
     *
     * [비결정적 동작 주의]
     * CountDownLatch.start.countDown() 후 두 스레드가 동시에 applyFill()에 진입하더라도
     * JPA TX 시작 타이밍에 따라 OptimisticLock이 발생할 수도, 순차 실행될 수도 있다.
     *
     * [RetryCounter 활용]
     * - retryCount > 0: OptimisticLock 실제 발생 → AFTER_COMMIT 중복 방지 검증 완료
     * - retryCount == 0: 순차 실행 → 이벤트 1건은 정상 동작이지만 Retry 검증 미완
     *
     * 핵심 불변 조건: 두 경우 모두 이벤트는 정확히 1건.
     * Position은 종목당 최대 1개 — 중복 생성 버그를 조기 감지한다.
     */
    @Test
    @DisplayName("2 스레드 동시 applyFill() — 이벤트 1건, Position 중복 없음")
    void concurrent_applyFill_publishes_at_most_one_event() throws InterruptedException {
        Long id = committed(OrderHistory.accepted("005930", OrderSide.BUY, 1, "EVT-03"));

        try {
            int N = 2;
            CountDownLatch ready  = new CountDownLatch(N);
            CountDownLatch start  = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(N);

            ExecutorService pool = Executors.newFixedThreadPool(N);
            for (int i = 0; i < N; i++) {
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        stateUpdater.applyFill(id, 1, 72500.0);
                    } catch (Exception ignored) {
                        // @Retryable maxAttempts(3) 초과 후 P1 가드 발동 시 도달 (사실상 없음)
                    } finally {
                        finish.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            finish.await();
            pool.shutdownNow();

            // @TransactionalEventListener는 커밋 성공 직후 동기 실행
            // → 스레드 finish.countDown() 이전에 이미 eventCapture에 추가됨
            assertThat(eventCapture.captured())
                    .as("OptimisticLock 여부와 무관하게 이벤트는 항상 1건")
                    .hasSize(1);

            // 종목당 Position은 최대 1개 — 동시성 버그 조기 감지
            long posCount = tx.execute(s -> positionRepo.count());
            assertThat(posCount)
                    .as("Position 중복 생성 금지: 종목당 최대 1개")
                    .isLessThanOrEqualTo(1L);

            int retries = retryCounter.get();
            if (retries > 0) {
                log.info("OptimisticLock {} 회 발생 → @Retryable 재시도 → AFTER_COMMIT 이벤트 중복 방지 검증 완료",
                        retries);
            } else {
                log.info("스레드 순차 실행 (OptimisticLock 미발생) — 이벤트 1건은 정상 동작");
            }

        } finally { cleanup(id, "005930"); }
    }

    // ── P1 가드 경로: 이벤트 미발행 ──────────────────────────────────────────

    @Test
    @DisplayName("FILLED 상태 → applyFill() 스킵 → OrderFilledEvent 발행 없음")
    void applyFill_guard_skip_publishes_no_event() {
        Long id = tx.execute(s -> {
            OrderHistory o = orderRepo.save(
                    OrderHistory.accepted("005930", OrderSide.BUY, 1, "EVT-04"));
            o.markFilled(1, 72500.0);
            return orderRepo.save(o).getId();
        });

        try {
            boolean applied = stateUpdater.applyFill(id, 2, 73000.0);

            assertThat(applied).isFalse();
            assertThat(eventCapture.captured())
                    .as("P1 가드 발동 시 publishEvent 미호출 → 이벤트 없음")
                    .isEmpty();

        } finally { tx.execute(s -> { orderRepo.deleteById(id); return null; }); }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private Long committed(OrderHistory order) {
        return tx.execute(s -> orderRepo.save(order).getId());
    }

    private void cleanup(Long orderId, String stockCode) {
        tx.execute(s -> {
            orderRepo.deleteById(orderId);
            positionRepo.findByStockCode(stockCode).ifPresent(positionRepo::delete);
            return null;
        });
    }

    // ── 테스트 전용 이벤트 캡처 컴포넌트 ──────────────────────────────────────

    /**
     * @DataJpaTest 컨텍스트에서 OrderFilledEvent를 캡처한다.
     * 이 컴포넌트는 @Import로만 사용하며 프로덕션 스캔 범위에 포함되지 않는다.
     */
    @Component
    static class FillEventCapture {

        private final CopyOnWriteArrayList<OrderFilledEvent> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(OrderFilledEvent e) {
            events.add(e);
        }

        public List<OrderFilledEvent> captured() {
            return List.copyOf(events);
        }

        public void clear() {
            events.clear();
        }
    }

    // ── @Retryable 재시도 횟수 관찰 ──────────────────────────────────────────

    /**
     * Spring Retry는 context 내 RetryListener 빈을 @EnableRetry 처리 시 자동 수집한다.
     * ObjectOptimisticLockingFailureException 발생 시 onError()가 호출되므로
     * 재시도 횟수를 외부에서 관찰할 수 있다.
     *
     * 이 관찰 데이터로 concurrent 테스트에서:
     *   - retryCount > 0 → OptimisticLock + AFTER_COMMIT 중복 방지가 실제 검증됨
     *   - retryCount == 0 → 순차 실행 (이벤트 1건은 여전히 유효하나 Retry 경로는 미검증)
     */
    @Component
    static class RetryCounter implements RetryListener {

        private final AtomicInteger count = new AtomicInteger(0);

        @Override
        public <T, E extends Throwable> void onError(RetryContext context,
                RetryCallback<T, E> callback, Throwable throwable) {
            if (throwable instanceof ObjectOptimisticLockingFailureException) {
                count.incrementAndGet();
            }
        }

        public int get() { return count.get(); }

        public void reset() { count.set(0); }
    }
}
