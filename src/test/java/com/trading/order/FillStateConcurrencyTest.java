package com.trading.order;

import com.trading.RetryConfig;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * FillStateUpdater 동시성 통합 테스트.
 *
 * @DataJpaTest 기본 @Transactional을 클래스 레벨에서 NOT_SUPPORTED로 오버라이드한다.
 * 각 테스트가 직접 TransactionTemplate으로 TX를 관리하므로
 * 두 스레드가 실제로 서로 다른 커밋된 트랜잭션에서 충돌할 수 있다.
 *
 * H2 + JPA @Version 낙관적 락 동작 원리:
 *   TX1: UPDATE ... WHERE id=? AND version=0  → 1 row → 커밋 → version=1
 *   TX2: UPDATE ... WHERE id=? AND version=0  → 0 row → ObjectOptimisticLockingFailureException
 *   @Retryable: TX2 재시도 → version=1 읽음 → 커밋 성공
 */
@DataJpaTest
@ActiveProfiles("paper")
@Import({FillStateUpdater.class, RetryConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)  // 테스트 자체 TX 없음 — 직접 관리
@DisplayName("FillStateUpdater 동시성 통합 테스트")
class FillStateConcurrencyTest {

    @Autowired FillStateUpdater stateUpdater;
    @Autowired OrderHistoryRepository orderRepo;
    @Autowired PositionRepository positionRepo;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    // ── 동시 applyFill: 중복 체결 가산 방지 ──────────────────────────────────

    @Test
    @DisplayName("2 스레드 동시 applyFill → Position 중복 가산 없음 (@Retryable 재시도)")
    void concurrent_applyFill_noDoubleCount() throws InterruptedException {
        // 1) 초기 데이터 커밋 (1주 BUY 주문, 아직 체결 없음)
        Long orderId = tx.execute(s ->
                orderRepo.save(OrderHistory.accepted("005930", OrderSide.BUY, 1, "CONC-01")).getId());

        try {
            // 2) 2 스레드가 동시에 applyFill(qty=1, price=72500) 호출
            //    한 스레드는 커밋 성공, 다른 스레드는 OptimisticLock 후 @Retryable 재시도
            //    재시도 시 상태가 이미 FILLED이므로 P1 가드에서 조용히 스킵됨
            int THREADS = 2;
            CountDownLatch ready  = new CountDownLatch(THREADS);
            CountDownLatch start  = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(THREADS);
            AtomicInteger succeeded = new AtomicInteger(0);
            AtomicInteger failed    = new AtomicInteger(0);

            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        stateUpdater.applyFill(orderId, 1, 72500.0);
                        succeeded.incrementAndGet();
                    } catch (Exception e) {
                        // @Retryable maxAttempts=3 초과 시 도달
                        failed.incrementAndGet();
                    } finally {
                        finish.countDown();
                    }
                });
            }
            ready.await();
            start.countDown();
            finish.await();
            pool.shutdownNow();

            // 3) 검증: 커밋된 최종 상태 확인
            tx.execute(s -> {
                Position pos = positionRepo.findByStockCode("005930").orElseThrow(
                        () -> new AssertionError("Position이 생성되지 않았습니다"));

                assertThat(pos.getQuantity())
                        .as("2 스레드가 동시 체결해도 Position 수량은 정확히 1주여야 한다")
                        .isEqualTo(1);

                OrderHistory result = orderRepo.findById(orderId).orElseThrow();
                assertThat(result.getStatus())
                        .as("최종 상태는 FILLED여야 한다")
                        .isEqualTo(OrderStatus.FILLED);
                assertThat(result.getFilledQuantity())
                        .as("filledQuantity는 주문 수량과 동일해야 한다")
                        .isEqualTo(1);

                return null;
            });

            // 두 스레드 중 적어도 한 스레드는 정상 완료되어야 함
            assertThat(succeeded.get())
                    .as("@Retryable로 인해 적어도 1 스레드는 성공해야 한다")
                    .isGreaterThanOrEqualTo(1);

        } finally {
            // 4) 정리 (NOT_SUPPORTED이므로 롤백 없음 — 직접 삭제)
            tx.execute(s -> {
                orderRepo.deleteById(orderId);
                positionRepo.findByStockCode("005930").ifPresent(positionRepo::delete);
                return null;
            });
        }
    }

    // ── applyFill vs markCancelRequested 경합 ────────────────────────────────

    /**
     * 가장 위험한 경합: 체결 폴과 타임아웃 취소가 동시에 같은 주문을 처리하는 경우.
     *
     * P1 가드 동작 검증:
     *   applyFill()이 이기면   → FILLED  (markCancelRequested P1 가드가 FILLED→CANCEL_REQUESTED 역전 차단)
     *   markCancelRequested()이 이기면 → CANCEL_REQUESTED (applyFill P1 가드가 terminal 상태 skip)
     *
     * 허용 최종 상태: FILLED 또는 CANCEL_REQUESTED (둘 다 정상)
     * 금지 최종 상태: 없음 (상태 불일치, FILLED + CANCEL_REQUESTED 동시 등)
     */
    @Test
    @DisplayName("applyFill(전량) vs markCancelRequested 동시 실행 → 유효한 단일 상태로 수렴")
    void concurrent_fill_vs_cancel_request() throws InterruptedException {
        // 1) ACCEPTED 100주 주문 커밋
        Long orderId = tx.execute(s ->
                orderRepo.save(OrderHistory.accepted("005930", OrderSide.BUY, 100, "CONC-03")).getId());

        try {
            CountDownLatch ready  = new CountDownLatch(2);
            CountDownLatch start  = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(2);

            ExecutorService pool = Executors.newFixedThreadPool(2);

            // Thread A: 전량 체결 (ACCEPTED → FILLED)
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    stateUpdater.applyFill(orderId, 100, 72500.0);
                } catch (Exception ignored) {
                } finally { finish.countDown(); }
            });

            // Thread B: 취소 요청 (ACCEPTED → CANCEL_REQUESTED)
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    stateUpdater.markCancelRequested(orderId);
                } catch (Exception ignored) {
                } finally { finish.countDown(); }
            });

            ready.await();
            start.countDown();
            finish.await();
            pool.shutdownNow();

            // 2) 최종 상태 검증
            tx.execute(s -> {
                OrderHistory result = orderRepo.findById(orderId).orElseThrow();
                OrderStatus finalStatus = result.getStatus();

                // FILLED 또는 CANCEL_REQUESTED만 허용 (둘 중 하나가 먼저 커밋한 것)
                assertThat(finalStatus)
                        .as("최종 상태는 FILLED 또는 CANCEL_REQUESTED 중 하나여야 한다")
                        .isIn(OrderStatus.FILLED, OrderStatus.CANCEL_REQUESTED);

                // FILLED이면 Position이 100주여야 함
                if (finalStatus == OrderStatus.FILLED) {
                    assertThat(result.getFilledQuantity())
                            .as("FILLED 상태의 filledQuantity는 주문 수량과 동일해야 한다")
                            .isEqualTo(100);
                    assertThat(positionRepo.findByStockCode("005930")
                            .map(p -> p.getQuantity()).orElse(0))
                            .as("FILLED이면 Position 100주 존재")
                            .isEqualTo(100);
                }

                return null;
            });

        } finally {
            tx.execute(s -> {
                orderRepo.deleteById(orderId);
                positionRepo.findByStockCode("005930").ifPresent(positionRepo::delete);
                return null;
            });
        }
    }

    // ── finalizeAfterCancel vs markCancelFailed 경합 ─────────────────────────

    @Test
    @DisplayName("finalizeAfterCancel(취소 확정) 후 markCancelFailed(타임아웃) 늦게 도착 → CANCELLED 유지")
    void cancelFailed_arrives_after_finalize_is_noop() {
        // 1) CANCEL_REQUESTED 주문 커밋
        Long orderId = tx.execute(s -> {
            OrderHistory order = orderRepo.save(
                    OrderHistory.accepted("005930", OrderSide.BUY, 100, "CONC-02"));
            order.markCancelRequested();
            return orderRepo.save(order).getId();
        });

        try {
            // 2) finalizeAfterCancel 먼저 완료 (취소 확정, 체결 0주)
            stateUpdater.finalizeAfterCancel(orderId, 0, 0.0);

            // 3) 24h 타임아웃 스레드가 늦게 markCancelFailed 호출
            stateUpdater.markCancelFailed(orderId);

            // 4) 검증: CANCELLED 유지 (CANCEL_FAILED로 역전이 없음)
            tx.execute(s -> {
                assertThat(orderRepo.findById(orderId).orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.CANCELLED);
                return null;
            });

        } finally {
            tx.execute(s -> { orderRepo.deleteById(orderId); return null; });
        }
    }
}
