package com.trading.order;

import com.trading.position.Position;
import com.trading.position.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/**
 * 주문/포지션의 DB 상태를 원자적으로 갱신하는 @Transactional 전담 빈.
 *
 * 모든 public 메서드는 DB 연산만 수행한다 (HTTP 호출 없음).
 * FillProcessor(HTTP 전담)가 여기로 결과를 위임한다.
 *
 * [P1 @Retryable 배치 전략]
 * @Retryable 은 @Transactional 보다 바깥쪽 프록시(낮은 order 숫자)로 적용된다.
 * 충돌 흐름:
 *   @Retryable 진입
 *     → @Transactional 시작 (새 트랜잭션)
 *       → DB 연산
 *     → @Transactional 커밋 실패 (OptimisticLock)
 *     → 롤백
 *   @Retryable 재시도 (100ms, 200ms 대기)
 *     → @Transactional 시작 (또 새 트랜잭션)
 *       → DB 연산 (최신 version 읽음)
 *     → @Transactional 커밋 성공
 *
 * FillPoller는 재시도 루프를 제거하고 단순 try-catch 로 유지한다.
 * HTTP 체결조회는 1회만 호출된다.
 */
@Component
@Profile("paper")
public class FillStateUpdater {

    private static final Logger log = LoggerFactory.getLogger(FillStateUpdater.class);

    private final OrderHistoryRepository orderHistoryRepository;
    private final PositionRepository positionRepository;
    private final ApplicationEventPublisher eventPublisher;

    public FillStateUpdater(OrderHistoryRepository orderHistoryRepository,
                            PositionRepository positionRepository,
                            ApplicationEventPublisher eventPublisher) {
        this.orderHistoryRepository = orderHistoryRepository;
        this.positionRepository = positionRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * HTTP 호출 전 현재 주문 상태를 불변 DTO로 읽는다.
     * version 필드 포함 — 로그 분석 시 동시성 추적에 유용.
     */
    @Transactional(readOnly = true)
    public OrderSnapshot loadSnapshot(Long orderId) {
        OrderHistory o = orderHistoryRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("주문 없음: id=" + orderId));
        log.debug("스냅샷 로드: orderId={} status={} version={}", o.getId(), o.getStatus(), o.getVersion());
        return new OrderSnapshot(
                o.getId(), o.getOrderNo(), o.getStatus(), o.getSide(), o.getStockCode(),
                o.getQuantity(), o.getFilledQuantity(), o.getRequestedAt(),
                o.getCancelRequestedAt(), o.getVersion());
    }

    /**
     * ACCEPTED/PARTIAL_FILLED 주문에 체결 결과를 원자적으로 반영한다.
     *
     * @return true  = DB 실제 갱신됨 (호출 측에서 알림 전송 가능)
     *         false = P1 가드 발동 또는 신규 체결 없음 (DB 변경 없음, 알림 보내면 안 됨)
     *
     * [P1 상태 검증] FILLED/CANCELLED 등 터미널 상태는 조용히 스킵.
     * [P1 @Retryable] 낙관적 락 충돌 시 DB 커밋만 재시도 (HTTP 재호출 없음).
     */
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2.0)
    )
    @Transactional
    public boolean applyFill(Long orderId, int apiTotalFilledQty, double apiAvgPrice) {
        OrderHistory order = orderHistoryRepository.findById(orderId).orElseThrow();

        OrderStatus current = order.getStatus();
        if (current != OrderStatus.ACCEPTED && current != OrderStatus.PARTIAL_FILLED) {
            log.warn("applyFill 무시 — 처리 불가 상태: status={} orderId={}", current, orderId);
            return false;
        }

        int newlyFilled = apiTotalFilledQty - order.getFilledQuantity();
        if (newlyFilled <= 0) return false;

        updatePosition(order, newlyFilled, apiAvgPrice);

        if (apiTotalFilledQty >= order.getQuantity()) {
            order.markFilled(apiTotalFilledQty, apiAvgPrice);
            log.info("전량 체결: side={} stockCode={} qty={} avgPrice={}",
                    order.getSide(), order.getStockCode(), apiTotalFilledQty, apiAvgPrice);
            // 트랜잭션 내부 발행 → AFTER_COMMIT 후 TradingEventListener.onOrderFilled() 호출
            eventPublisher.publishEvent(
                    new OrderFilledEvent(order.getSide(), order.getStockCode(), apiTotalFilledQty, apiAvgPrice, false));
        } else {
            order.markPartialFilled(apiTotalFilledQty, apiAvgPrice);
            log.info("부분 체결: side={} stockCode={} 체결={}/{} avgPrice={}",
                    order.getSide(), order.getStockCode(), apiTotalFilledQty, order.getQuantity(), apiAvgPrice);
        }
        return true;
    }

    /**
     * KIS 취소 API 접수 성공 후 CANCEL_REQUESTED로 전환한다.
     *
     * [P1 상태 검증] snapshot 조회 후 applyFill이 먼저 FILLED 처리했을 수 있으므로
     * 트랜잭션 안에서 최신 status를 재확인한다.
     */
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2.0)
    )
    @Transactional
    public void markCancelRequested(Long orderId) {
        OrderHistory order = orderHistoryRepository.findById(orderId).orElseThrow();

        OrderStatus current = order.getStatus();
        if (current != OrderStatus.ACCEPTED && current != OrderStatus.PARTIAL_FILLED) {
            log.warn("markCancelRequested 무시 — 이미 처리된 주문: status={} orderId={}", current, orderId);
            return;
        }

        order.markCancelRequested();
        log.warn("취소 접수: stockCode={} ordNo={}", order.getStockCode(), order.getOrderNo());
    }

    /**
     * CANCEL_REQUESTED 주문의 최종 폴 — 취소 창 동안의 체결을 정리한다.
     *
     * 사전 조건 검증: CANCEL_REQUESTED가 아니면 IllegalStateException.
     */
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2.0)
    )
    @Transactional
    public void finalizeAfterCancel(Long orderId, int apiTotalFilledQty, double apiAvgPrice) {
        OrderHistory order = orderHistoryRepository.findById(orderId).orElseThrow();

        if (order.getStatus() != OrderStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException(String.format(
                    "finalizeAfterCancel은 CANCEL_REQUESTED 상태에서만 호출 가능: 현재=%s (orderId=%d)",
                    order.getStatus(), orderId));
        }

        int newlyFilled = apiTotalFilledQty - order.getFilledQuantity();
        if (newlyFilled > 0) {
            updatePosition(order, newlyFilled, apiAvgPrice);
            if (apiTotalFilledQty >= order.getQuantity()) {
                order.markFilled(apiTotalFilledQty, apiAvgPrice);
                log.info("취소 중 전량 체결: stockCode={} ordNo={}", order.getStockCode(), order.getOrderNo());
                eventPublisher.publishEvent(
                        new OrderFilledEvent(order.getSide(), order.getStockCode(), apiTotalFilledQty, apiAvgPrice, true));
                return;
            }
            order.recordFillDuringCancel(apiTotalFilledQty, apiAvgPrice);
        }

        order.markCancelled();
        log.warn("취소 확정: stockCode={} ordNo={} 최종체결qty={}",
                order.getStockCode(), order.getOrderNo(), order.getFilledQuantity());
    }

    /**
     * CANCEL_REQUESTED 24시간 초과 시 수동 확인 필요 상태로 전환.
     *
     * [P1 상태 검증]
     * finalizeAfterCancel()이 먼저 CANCELLED/FILLED 처리했을 수 있으므로
     * 트랜잭션 안에서 최신 status를 재확인한다.
     *
     * [대칭적 @Retryable]
     * finalizeAfterCancel()과 markCancelFailed()가 동시에 같은 행을 업데이트하려 할 때
     * ObjectOptimisticLockingFailureException이 발생할 수 있다.
     * 재시도 시 status 가드(CANCEL_REQUESTED 검증)가 이미 처리된 경우를 걸러낸다.
     */
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2.0)
    )
    @Transactional
    public void markCancelFailed(Long orderId) {
        OrderHistory order = orderHistoryRepository.findById(orderId).orElseThrow();

        if (order.getStatus() != OrderStatus.CANCEL_REQUESTED) {
            log.warn("markCancelFailed 무시 — 이미 처리된 주문: status={} orderId={}", order.getStatus(), orderId);
            return;
        }

        order.markCancelFailed();
        eventPublisher.publishEvent(
                new CancelFailedEvent(order.getStockCode(), order.getOrderNo(), order.getCancelRequestedAt()));
        log.error("취소 장기 미확정 (수동 확인 필요): stockCode={} ordNo={} cancelRequestedAt={}",
                order.getStockCode(), order.getOrderNo(), order.getCancelRequestedAt());
    }

    // ── Position 갱신 ─────────────────────────────────────────────────────────

    private void updatePosition(OrderHistory order, int newlyFilled, double fillPrice) {
        Position pos = positionRepository.findByStockCode(order.getStockCode())
                .orElse(Position.empty(order.getStockCode()));

        if (order.getSide() == OrderSide.BUY) {
            pos.applyBuy(newlyFilled, fillPrice);
            positionRepository.save(pos);
        } else {
            pos.applySell(newlyFilled);
            if (pos.getQuantity() == 0) positionRepository.delete(pos);
            else                        positionRepository.save(pos);
        }
    }
}
