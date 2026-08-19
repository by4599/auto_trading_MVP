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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

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
@RecordApplicationEvents
@Import({FillStateUpdater.class, RetryConfig.class, TradeResultTracker.class})
@DisplayName("FillStateUpdater 상태 가드 통합 테스트")
class FillStateUpdaterTest {

    @Autowired FillStateUpdater stateUpdater;
    @Autowired OrderHistoryRepository orderRepo;
    @Autowired PositionRepository positionRepo;
    @Autowired PortfolioStateRepository portfolioStateRepo;
    @Autowired ApplicationEvents applicationEvents;

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

    // ── 부분 체결 이벤트 발행 (결함 #5 — 부분 체결분 손절 장착 경로) ─────────

    @Test
    @DisplayName("부분 체결 → OrderPartialFilledEvent 발행 (전량 체결 이벤트는 미발행)")
    void partial_fill_publishes_partial_event() {
        OrderHistory order = orderRepo.saveAndFlush(
                OrderHistory.accepted("005930", OrderSide.BUY, 100, "ORD-PE-01"));

        stateUpdater.applyFill(order.getId(), 30, 72_000.0);

        assertThat(applicationEvents.stream(OrderPartialFilledEvent.class))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.side()).isEqualTo(OrderSide.BUY);
                    assertThat(e.stockCode()).isEqualTo("005930");
                    assertThat(e.totalFilledQty()).isEqualTo(30);
                    assertThat(e.avgPrice()).isEqualTo(72_000.0);
                });
        assertThat(applicationEvents.stream(OrderFilledEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("전량 체결 → OrderFilledEvent만 발행 (부분 체결 이벤트 미발행)")
    void full_fill_publishes_only_filled_event() {
        OrderHistory order = orderRepo.saveAndFlush(
                OrderHistory.accepted("005930", OrderSide.BUY, 100, "ORD-PE-02"));

        stateUpdater.applyFill(order.getId(), 100, 72_000.0);

        assertThat(applicationEvents.stream(OrderFilledEvent.class)).hasSize(1);
        assertThat(applicationEvents.stream(OrderPartialFilledEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("취소 창 중 부분 체결 → 취소 확정과 함께 OrderPartialFilledEvent 발행")
    void finalize_after_cancel_partial_publishes_partial_event() {
        OrderHistory order = savedCancelRequested("ORD-PE-03");

        stateUpdater.finalizeAfterCancel(order.getId(), 30, 72_000.0);

        assertThat(applicationEvents.stream(OrderPartialFilledEvent.class)).hasSize(1);
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

    // ── 라운드트립 집계: 조각 체결이 아니라 "한 매매"가 단위 ────────────────────

    @Test
    @DisplayName("매도가 두 조각으로 체결돼도 손실은 1회만 센다")
    void partial_sell_fills_count_as_one_round_trip() {
        Position pos = Position.empty("005930");
        pos.applyBuy(5, 73_000.0);
        positionRepo.saveAndFlush(pos);

        OrderHistory sell = orderRepo.saveAndFlush(
                OrderHistory.accepted("005930", OrderSide.SELL, 5, "ORD-RT-01"));
        // 두 번째 인자는 브로커 누적 체결량이다 (3주 → 5주 = 2주 추가 체결)
        stateUpdater.applyFill(sell.getId(), 3, 72_000.0);   // -3,000
        stateUpdater.applyFill(sell.getId(), 5, 72_000.0);   // -2,000 → 합계 -5,000

        assertThat(portfolioStateRepo.findById(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT))
                .isPresent()
                .hasValueSatisfying(s -> assertThat(s.getStateValue()).isEqualTo(1.0));
    }

    @Test
    @DisplayName("일부만 팔고 보유가 남으면 아직 세지 않는다 (매매가 안 끝났다)")
    void partial_reduction_does_not_count_yet() {
        // 기존 스트릭 2 — 부분 축소는 이걸 늘리지도, 0으로 리셋하지도 않아야 한다
        portfolioStateRepo.saveAndFlush(
                PortfolioState.of(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT, 2));

        Position pos = Position.empty("005930");
        pos.applyBuy(5, 73_000.0);
        positionRepo.saveAndFlush(pos);

        OrderHistory sell = orderRepo.saveAndFlush(
                OrderHistory.accepted("005930", OrderSide.SELL, 3, "ORD-RT-02"));
        stateUpdater.applyFill(sell.getId(), 3, 72_000.0);   // 손실이지만 2주 남음

        assertThat(portfolioStateRepo.findById(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT))
                .isPresent()
                .hasValueSatisfying(s -> assertThat(s.getStateValue()).isEqualTo(2.0));
    }

    @Test
    @DisplayName("조각 손익이 엇갈려도 합계로 판정한다 (수익 조각 뒤 손실 조각)")
    void mixed_chunks_are_judged_by_total() {
        portfolioStateRepo.saveAndFlush(
                PortfolioState.of(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT, 2));

        Position pos = Position.empty("005930");
        pos.applyBuy(2, 73_000.0);
        positionRepo.saveAndFlush(pos);

        OrderHistory sell = orderRepo.saveAndFlush(
                OrderHistory.accepted("005930", OrderSide.SELL, 2, "ORD-RT-03"));
        stateUpdater.applyFill(sell.getId(), 1, 75_000.0);   // +2,000
        stateUpdater.applyFill(sell.getId(), 2, 72_000.0);   // -1,000 → 합계 +1,000

        assertThat(portfolioStateRepo.findById(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT))
                .isPresent()
                .hasValueSatisfying(s -> assertThat(s.getStateValue()).isEqualTo(0.0));
    }

    @Test
    @DisplayName("재진입한 다음 매매는 이전 매매의 손익을 물려받지 않는다")
    void re_entry_starts_a_fresh_round_trip() {
        Position pos = Position.empty("005930");
        pos.applyBuy(1, 73_000.0);
        positionRepo.saveAndFlush(pos);

        OrderHistory first = orderRepo.saveAndFlush(
                OrderHistory.accepted("005930", OrderSide.SELL, 1, "ORD-RT-04"));
        stateUpdater.applyFill(first.getId(), 1, 70_000.0);  // -3,000 → 손실 1회

        // 재진입 후 소폭 수익으로 청산 — 앞 매매의 -3,000이 섞이면 손실로 오판된다
        OrderHistory buy = orderRepo.saveAndFlush(
                OrderHistory.accepted("005930", OrderSide.BUY, 1, "ORD-RT-05"));
        stateUpdater.applyFill(buy.getId(), 1, 70_000.0);
        OrderHistory second = orderRepo.saveAndFlush(
                OrderHistory.accepted("005930", OrderSide.SELL, 1, "ORD-RT-06"));
        stateUpdater.applyFill(second.getId(), 1, 71_000.0); // +1,000 → 리셋

        assertThat(portfolioStateRepo.findById(PortfolioState.KEY_CONSECUTIVE_LOSS_COUNT))
                .isPresent()
                .hasValueSatisfying(s -> assertThat(s.getStateValue()).isEqualTo(0.0));
    }

    // ── 체결 대사: 취소불가(잔량없음)=체결로 판정 (유령 포지션 desync 해소) ──────

    @Test
    @DisplayName("취소불가+브로커 4주 보유 → 포지션 4주 생성·손절 이벤트·주문 FILLED")
    void reconcileFilledFromBalance_creates_position_from_broker() {
        OrderHistory order = orderRepo.saveAndFlush(
                OrderHistory.accepted("066570", OrderSide.BUY, 4, "ORD-RC-01"));

        stateUpdater.reconcileFilledFromBalance(order.getId(), 4, 90_000.0);

        Position pos = positionRepo.findByStockCode("066570").orElseThrow();
        assertThat(pos.getQuantity()).isEqualTo(4);
        assertThat(pos.getAveragePrice()).isEqualTo(90_000.0);
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(applicationEvents.stream(OrderPartialFilledEvent.class))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.stockCode()).isEqualTo("066570");
                    assertThat(e.totalFilledQty()).isEqualTo(4);
                });
    }

    @Test
    @DisplayName("취소불가+브로커 0주(이미 정리됨) → 포지션 없음·주문 FILLED(폴링 중단)·이벤트 없음")
    void reconcileFilledFromBalance_broker_flat_just_terminates_order() {
        OrderHistory order = orderRepo.saveAndFlush(
                OrderHistory.accepted("066570", OrderSide.BUY, 4, "ORD-RC-02"));

        stateUpdater.reconcileFilledFromBalance(order.getId(), 0, 0.0);

        assertThat(positionRepo.findByStockCode("066570")).isEmpty();
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(applicationEvents.stream(OrderPartialFilledEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("이미 FILLED 주문 → reconcileFilledFromBalance 무시 (포지션 중복 생성 없음)")
    void reconcileFilledFromBalance_guard_ignores_terminal_order() {
        OrderHistory order = orderRepo.saveAndFlush(
                OrderHistory.accepted("066570", OrderSide.BUY, 4, "ORD-RC-03"));
        order.markFilled(4, 90_000.0);
        orderRepo.saveAndFlush(order);

        long posBefore = positionRepo.count();
        stateUpdater.reconcileFilledFromBalance(order.getId(), 4, 90_000.0);

        assertThat(positionRepo.count()).isEqualTo(posBefore);
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
