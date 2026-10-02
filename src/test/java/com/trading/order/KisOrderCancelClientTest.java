package com.trading.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.trading.order.OrderCancelClient.CancelOutcome.FAILED;
import static com.trading.order.OrderCancelClient.CancelOutcome.MARKET_CLOSED;
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

    /**
     * 2026-09-22 실측 문구. 이 응답이 FAILED로 분류돼 자정까지 5,983회 재시도됐다 —
     * 장이 끝난 뒤의 취소는 성공할 수 없으므로 재시도 대상이 아니다.
     */
    @Test
    @DisplayName("'모의투자 장종료 입니다.' → MARKET_CLOSED (재시도 무의미)")
    void market_closed_is_market_closed() {
        assertThat(KisOrderCancelClient.classify("1", "모의투자 장종료 입니다."))
                .isEqualTo(MARKET_CLOSED);
    }

    @Test
    @DisplayName("실전 계좌의 다른 '장종료' 문구도 MARKET_CLOSED (부분 문자열 매칭)")
    void other_market_closed_wording_is_market_closed() {
        assertThat(KisOrderCancelClient.classify("1", "장종료되었습니다."))
                .isEqualTo(MARKET_CLOSED);
    }

    /**
     * 검사 순서 고정 — 이미 체결된 주문이 우선이다. NO_OPEN_QTY는 실잔고 대사로 desync를
     * 복구하는 유일한 경로여서, 장종료 판정이 그 앞으로 오면 복구가 막힌다.
     */
    @Test
    @DisplayName("'취소할 수량'과 '장종료'가 함께 오면 NO_OPEN_QTY가 우선")
    void already_filled_wins_over_market_closed() {
        assertThat(KisOrderCancelClient.classify("1", "모의투자 정정/취소할 수량이 없습니다. 장종료"))
                .isEqualTo(NO_OPEN_QTY);
    }

    @Test
    @DisplayName("일시적 장애 문구는 FAILED 유지 — 재시도로 성공할 수 있다")
    void transient_delay_is_still_failed() {
        assertThat(KisOrderCancelClient.classify("1", "모의투자 서비스가 지연되고 있습니다. 잠시후 재시도 바랍니다."))
                .isEqualTo(FAILED);
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
