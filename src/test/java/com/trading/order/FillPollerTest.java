package com.trading.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 폴링 대상 상태 고정 — "취소를 미뤘어도 체결조회는 계속된다"의 절반을 증거로 남긴다.
 *
 * CancelRetryGate가 취소 시도를 억제하면 주문은 ACCEPTED/PARTIAL_FILLED에 그대로 남는다.
 * 그 상태가 폴링 대상에서 빠지면 그 주문은 영원히 잊혀지고, 취소거부(잔량없음)=체결
 * 자동복구도 발동하지 못한다. 그래서 상태 목록을 테스트로 못박는다.
 *
 * 리포지토리는 인터페이스라 목킹한다(CLAUDE.md 컨벤션). FillProcessor는 구체 클래스여서
 * 목으로 만들지 않고, 미체결이 0건이면 손대지 않는 경로만 검증한다.
 */
@DisplayName("FillPoller — 폴링 대상 상태")
class FillPollerTest {

    @Test
    @DisplayName("취소가 미뤄져 ACCEPTED로 남은 주문도 계속 체결조회 대상이다")
    void polls_accepted_and_partial_and_cancel_requested() {
        OrderHistoryRepository repository = mock(OrderHistoryRepository.class);
        when(repository.findByStatusIn(anyList())).thenReturn(List.of());
        // 미체결 0건이면 FillProcessor를 호출하지 않는 경로 — 구체 클래스를 목으로 만들지 않는다
        FillPoller poller = new FillPoller(repository, null);

        poller.pollFills();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderStatus>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).findByStatusIn(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(
                OrderStatus.ACCEPTED, OrderStatus.PARTIAL_FILLED, OrderStatus.CANCEL_REQUESTED);
    }
}
