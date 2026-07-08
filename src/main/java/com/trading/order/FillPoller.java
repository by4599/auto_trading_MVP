package com.trading.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 3초마다 미완료 주문 목록을 조회해 FillProcessor에 위임한다.
 *
 * 낙관적 락 재시도 전략:
 *   이전에는 FillPoller에서 루프로 재시도했으나 HTTP 체결조회도 함께 재호출됐다.
 *   현재는 FillStateUpdater 의 @Retryable이 DB 커밋 단계만 재시도한다.
 *   FillPoller는 단순 try-catch 로 유지 — HTTP는 1회, DB만 재시도.
 */
@Component
@Profile("paper")
public class FillPoller {

    private static final Logger log = LoggerFactory.getLogger(FillPoller.class);

    private final OrderHistoryRepository orderHistoryRepository;
    private final FillProcessor fillProcessor;

    public FillPoller(OrderHistoryRepository orderHistoryRepository,
                      FillProcessor fillProcessor) {
        this.orderHistoryRepository = orderHistoryRepository;
        this.fillProcessor = fillProcessor;
    }

    /**
     * 폴링 대상 상태: ACCEPTED, PARTIAL_FILLED, CANCEL_REQUESTED.
     *
     * CANCEL_FAILED 정책 (A안 — v1 의도):
     *   CANCEL_FAILED는 의도적으로 폴링 대상에서 제외한다.
     *   24시간 취소 미확정은 KIS 장애 또는 운영자 수동 조치가 필요한 이례 상황이므로
     *   자동 복구를 시도하지 않고 ERROR 로그로 알림만 남긴다.
     *   운영자는 KIS HTS에서 직접 주문 상태를 확인하고 DB를 수동 정정한다.
     *
     *   FILLED, CANCELLED, FAILED, TIMEOUT 도 터미널 상태이므로 폴링하지 않는다.
     */
    @Scheduled(fixedDelay = 3000)
    public void pollFills() {
        List<OrderHistory> pending = orderHistoryRepository.findByStatusIn(
                List.of(OrderStatus.ACCEPTED, OrderStatus.PARTIAL_FILLED, OrderStatus.CANCEL_REQUESTED));
        if (pending.isEmpty()) return;

        log.debug("체결 확인 대상: {}건", pending.size());
        for (OrderHistory order : pending) {
            try {
                fillProcessor.process(order.getId());
            } catch (Exception e) {
                log.error("체결조회 실패: ordNo={}", order.getOrderNo(), e);
            }
        }
    }
}
