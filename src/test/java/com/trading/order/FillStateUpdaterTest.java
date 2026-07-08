package com.trading.order;

import com.trading.RetryConfig;
import com.trading.position.PortfolioState;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResultTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.*;

/**
 * FillStateUpdater 상태 가드 통합 테스트 (@DataJpaTest + H2).
 *
 * 각 테스트는 @DataJpaTest 기본 동작에 따라 트랜잭션 안에서 실행되고 롤백된다.
 * @Import는 @Profile("paper") 조건을 우회하지 못하므로 @ActiveProfiles("paper")로
 * 프로필을 활성화한다 (DataSource는 @DataJpaTest가 임베디드 H2로 교체).
 *
 * 동시성 테스트는 별도 FillStateConcurrencyTest 참조.
 */
@DataJpaTest
@ActiveProfiles("paper")
@Import({FillStateUpdater.class, RetryConfig.class, TradeResultTracker.class})
@DisplayName("FillStateUpdater 상태 가드 통합 테스트")
class FillStateUpdaterTest {

    @Autowired FillStateUpdater stateUpdater;
    @Autowired OrderHistoryRepository orderRepo;
    @Autowired PositionRepository positionRepo;
    @Autowired PortfolioStateRepository portfolioStateRepo;

    // ── CANCEL_FAILED 전이 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("CANCEL_REQUESTED → markCancelFailed() → CANCEL_FAILED")
    void cancelFailed_transitions_correctly() {
        OrderHistory order = savedCancelRequested("ORD-CF-01");

        stateUpdater.markCancelFailed(order.getId());

        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CANCEL_FAILED);
    }

    @Test
    @DisplayName("이미 CANCELLED → markCancelFailed() 무시 (CANCELLED 유지)")
    void cancelFailed_guard_ignores_already_cancelled() {
        OrderHistory order = savedCancelRequested("ORD-CF-02");
        order.markCancelled();
        orderRepo.saveAndFlush(order);

        stateUpdater.markCancelFailed(order.getId());

        // CANCEL_FAILED로 역전이 없어야 한다
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("이미 FILLED → markCancelFailed() 무시 (FILLED 유지)")
    void cancelFailed_guard_ignores_already_filled() {
        OrderHistory order = savedCancelRequested("ORD-CF-03");
        order.markFilled(100, 72500.0);
        orderRepo.saveAndFlush(order);

        stateUpdater.markCancelFailed(order.getId());

        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    // ── applyFill P1 상태 가드 ────────────────────────────────────────────────

    @Test
    @DisplayName("FILLED 상태 → applyFill() 무시 — Position 중복 가산 없음")
    void applyFill_guard_skips_filled_order() {
        // 1주 주문, 전량 체결 완료
        OrderHistory order = orderRepo.saveAndFlush(
                OrderHistory.accepted("005930", OrderSide.BUY, 1, "ORD-P1-01"));
        order.markFilled(1, 72500.0);
        orderRepo.saveAndFlush(order);

        // apiTotalFilledQty=2 > filledQuantity=1 → newlyFilled=1 > 0
        // 상태 가드 없으면 updatePosition이 호출되어 Position이 생성됨
        long posCountBefore = positionRepo.count();
        boolean applied = stateUpdater.applyFill(order.getId(), 2, 73000.0);

        // P1 가드 발동 → false 반환, Position 건수 변화 없음
        assertThat(applied).isFalse();
        assertThat(positionRepo.count()).isEqualTo(posCountBefore);
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    @DisplayName("CANCELLED 상태 → applyFill() false 반환")
    void applyFill_guard_skips_cancelled_order() {
        OrderHistory order = savedCancelRequested("ORD-P1-02");
        order.markCancelled();
        orderRepo.saveAndFlush(order);

        long posCountBefore = positionRepo.count();
        boolean applied = stateUpdater.applyFill(order.getId(), 50, 72000.0);

        assertThat(applied).isFalse();
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(positionRepo.count()).isEqualTo(posCountBefore);
    }

    // ── markCancelRequested P1 상태 가드 ─────────────────────────────────────

    @Test
    @DisplayName("FILLED 상태 → markCancelRequested() 무시 (FILLED 유지)")
    void cancelRequested_guard_skips_filled_order() {
        OrderHistory order = orderRepo.saveAndFlush(
                OrderHistory.accepted("005930", OrderSide.BUY, 1, "ORD-P1-03"));
        order.markFilled(1, 72500.0);
        orderRepo.saveAndFlush(order);

        stateUpdater.markCancelRequested(order.getId());

        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    // ── finalizeAfterCancel ───────────────────────────────────────────────────

    @Test
    @DisplayName("취소 창 동안 30주 체결 → CANCELLED, filledQuantity=30, Position 30주")
    void finalizeAfterCancel_partial_fill_preserved() {
        OrderHistory order = savedCancelRequested("ORD-FC-01");

        stateUpdater.finalizeAfterCancel(order.getId(), 30, 72000.0);

        OrderHistory result = reload(order);
        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(result.getFilledQuantity()).isEqualTo(30);

        assertThat(positionRepo.findByStockCode("005930").orElseThrow().getQuantity())
                .isEqualTo(30);
    }

    @Test
    @DisplayName("취소 창 동안 전량(100주) 체결 → FILLED")
    void finalizeAfterCancel_full_fill_during_cancel() {
        OrderHistory order = savedCancelRequested("ORD-FC-02");

        stateUpdater.finalizeAfterCancel(order.getId(), 100, 72500.0);

        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(positionRepo.findByStockCode("005930").orElseThrow().getQuantity())
                .isEqualTo(100);
    }

    @Test
    @DisplayName("finalizeAfterCancel — CANCEL_REQUESTED 아닌 상태 → IllegalStateException")
    void finalizeAfterCancel_throws_on_wrong_status() {
        OrderHistory order = orderRepo.saveAndFlush(
                OrderHistory.accepted("005930", OrderSide.BUY, 100, "ORD-FC-03"));
        // ACCEPTED 상태 (CANCEL_REQUESTED 아님)

        assertThatThrownBy(() -> stateUpdater.finalizeAfterCancel(order.getId(), 0, 0.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCEL_REQUESTED");
    }

    // ── F-5: 매도 체결 → 실현손익 연속 손실 카운터 ───────────────────────────

    @Test
    @DisplayName("손실 매도 체결 → portfolio_state 연속손실 카운트 증가")
    void loss_sell_fill_increments_consecutive_loss_count() {
        // 보유: 1주 @73,000
        Position pos = Position.empty("005930");
        pos.applyBuy(1, 73_000.0);
        positionRepo.saveAndFlush(pos);

        // 72,000에 매도 체결 → 실현손익 -1,000
        OrderHistory sell = orderRepo.saveAndFlush(
                OrderHistory.accepted("005930", OrderSide.SELL, 1, "ORD-F5-01"));
        stateUpdater.applyFill(sell.getId(), 1, 72_000.0);

        assertThat(portfolioStateRepo.findById(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT))
                .isPresent()
                .hasValueSatisfying(s -> assertThat(s.getStateValue()).isEqualTo(1.0));
    }

    @Test
    @DisplayName("수익 매도 체결 → 연속손실 카운트 0으로 리셋")
    void profit_sell_fill_resets_consecutive_loss_count() {
        portfolioStateRepo.saveAndFlush(
                PortfolioState.of(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT, 2));

        Position pos = Position.empty("005930");
        pos.applyBuy(1, 73_000.0);
        positionRepo.saveAndFlush(pos);

        OrderHistory sell = orderRepo.saveAndFlush(
                OrderHistory.accepted("005930", OrderSide.SELL, 1, "ORD-F5-02"));
        stateUpdater.applyFill(sell.getId(), 1, 74_000.0); // +1,000

        assertThat(portfolioStateRepo.findById(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT))
                .isPresent()
                .hasValueSatisfying(s -> assertThat(s.getStateValue()).isEqualTo(0.0));
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /** 100주 BUY 주문을 CANCEL_REQUESTED 상태로 저장한다. */
    private OrderHistory savedCancelRequested(String orderNo) {
        OrderHistory order = orderRepo.saveAndFlush(
                OrderHistory.accepted("005930", OrderSide.BUY, 100, orderNo));
        order.markCancelRequested();
        return orderRepo.saveAndFlush(order);
    }

    private OrderHistory reload(OrderHistory order) {
        return orderRepo.findById(order.getId()).orElseThrow();
    }
}
