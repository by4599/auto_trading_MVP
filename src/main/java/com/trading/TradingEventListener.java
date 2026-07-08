package com.trading;

import com.trading.order.CancelFailedEvent;
import com.trading.order.OrderFilledEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 도메인 이벤트를 DB 커밋 완료 후에만 처리한다.
 *
 * AFTER_COMMIT을 사용하는 이유:
 *   트랜잭션 내부에서 publishEvent()를 호출해도 리스너는 커밋 성공 후에만 실행된다.
 *   따라서 "알림은 갔는데 DB는 롤백" 시나리오가 구조적으로 불가능하다.
 *
 *   비교: FillProcessor에서 직접 notifier.send() 호출 시
 *     → FillProcessor가 @Transactional을 얻는 순간 동일 위험 재발
 *     → 이벤트 기반은 미래 리팩터링에도 안전하다
 */
@Component
public class TradingEventListener {

    private final TelegramNotifier notifier;

    public TradingEventListener(TelegramNotifier notifier) {
        this.notifier = notifier;
    }

    /**
     * 예외 처리: TelegramNotifier.send() 내부에 try-catch가 있으므로
     * 이 리스너에서 예외가 전파되지 않는다. DB 커밋은 이미 완료된 상태.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderFilled(OrderFilledEvent event) {
        String tag = event.duringCancel() ? "[취소중체결완료]" : "[체결완료]";
        notifier.send(String.format("%s %s %s %d주 @%.0f원",
                tag, event.side(), event.stockCode(), event.filledQty(), event.avgPrice()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCancelFailed(CancelFailedEvent event) {
        notifier.send(String.format(
                "[CANCEL_FAILED] %s 주문번호=%s 취소접수=%s → KIS HTS 수동 확인 필요",
                event.stockCode(), event.orderNo(), event.cancelRequestedAt()));
    }
}
