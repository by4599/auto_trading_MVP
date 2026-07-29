package com.trading.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.trading.order.OrderCancelClient.CancelOutcome.FAILED;
import static com.trading.order.OrderCancelClient.CancelOutcome.NO_OPEN_QTY;
import static com.trading.order.OrderCancelClient.CancelOutcome.SENT;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KisOrderCancelClient.classify — 취소 응답 분류")
class KisOrderCancelClientTest {

    @Test
    @DisplayName("rt_cd=0 → SENT (취소 접수됨)")
    void success_is_sent() {
        assertThat(KisOrderCancelClient.classify("0", "정상처리 되었습니다.")).isEqualTo(SENT);
    }

    @Test
    @DisplayName("'취소할 수량 없음' → NO_OPEN_QTY (이미 체결된 주문)")
    void no_quantity_is_no_open_qty() {
        assertThat(KisOrderCancelClient.classify("1", "모의투자 정정/취소할 수량이 없습니다."))
                .isEqualTo(NO_OPEN_QTY);
    }

    @Test
    @DisplayName("'원주문번호가 존재하지 않습니다' → NO_OPEN_QTY (며칠 지나 종료된 주문)")
    void order_not_exist_is_no_open_qty() {
        assertThat(KisOrderCancelClient.classify("1", "모의투자 원주문번호가 존재하지 않습니다."))
                .isEqualTo(NO_OPEN_QTY);
    }

    @Test
    @DisplayName("그 밖의 rt_cd=1 오류 → FAILED (다음 폴에서 재시도)")
    void other_error_is_failed() {
        assertThat(KisOrderCancelClient.classify("1", "초당 거래건수를 초과하였습니다."))
                .isEqualTo(FAILED);
    }

    @Test
    @DisplayName("msg 없음 → FAILED")
    void null_msg_is_failed() {
        assertThat(KisOrderCancelClient.classify("1", null)).isEqualTo(FAILED);
    }
}
