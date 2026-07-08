package com.trading.order;

// 파일명: OrderLifecycleTest.java → 실제 프로젝트에서는 OrderLifecycleUnitTest.java로 리네임 권장.
// (Stub Repository 기반 단위 테스트. @DataJpaTest + H2 연동 통합테스트는 별도 작성 필요.)

import com.trading.position.Position;
import com.trading.position.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * 주문 생명주기 단위 테스트 (Stub Repository).
 *
 * 시나리오:
 *   1. 매수 접수 → 전량 체결 → Position 생성
 *   2. 매도 체결 → Position 제거
 *   3. 가중평균 단가 계산 (100주@50,000 + 50주@55,000 = 51,666.67)
 *   4. ACCEPTED 주문 → PendingOrderRule 차단
 *   5. 부분체결 (100주 중 30주) → PARTIAL_FILLED, Position 30주 반영
 *   6. 부분체결 → 잔여 체결 → FILLED, Position 누적 100주 (중복 가산 없음)
 *   7. 상태머신: 터미널 상태(FILLED)에서 재전이 시도 → IllegalStateException
 *   8. applySell 음수 가드: 보유량 초과 매도 시 IllegalStateException
 */
@DisplayName("주문 생명주기 단위 테스트 (Stub Repository)")
class OrderLifecycleUnitTest {

    private final java.util.Map<Long, OrderHistory> orderStore    = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Position>   positionStore = new java.util.LinkedHashMap<>();
    private long autoId = 1;

    private StubOrderHistoryRepository orderRepo;
    private StubPositionRepository     positionRepo;

    @BeforeEach
    void setUp() {
        orderStore.clear();
        positionStore.clear();
        autoId = 1;
        orderRepo    = new StubOrderHistoryRepository(orderStore);
        positionRepo = new StubPositionRepository(positionStore);
    }

    // ── 시나리오 1: 전량 체결 → Position 생성 ────────────────────────────────

    @Test
    @DisplayName("매수 전량 체결 → Position 생성")
    void buyFullFillCreatesPosition() {
        OrderHistory order = save(OrderHistory.accepted("005930", OrderSide.BUY, 1, "ORD-001"));

        simulateFill(order, 1, 1, 72500.0);  // 1주 중 1주 체결

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(positionRepo.findByStockCode("005930")).isPresent()
                .hasValueSatisfying(p -> {
                    assertThat(p.getQuantity()).isEqualTo(1);
                    assertThat(p.getAveragePrice()).isEqualTo(72500.0);
                });
    }

    // ── 시나리오 2: 매도 체결 → Position 제거 ────────────────────────────────

    @Test
    @DisplayName("매도 전량 체결 후 수량 0 → Position 삭제")
    void sellFullFillRemovesPosition() {
        Position pos = Position.empty("005930");
        pos.applyBuy(1, 72500.0);
        positionRepo.save(pos);

        OrderHistory sell = save(OrderHistory.accepted("005930", OrderSide.SELL, 1, "ORD-002"));
        simulateFill(sell, 1, 1, 73000.0);

        assertThat(positionRepo.findByStockCode("005930")).isEmpty();
    }

    // ── 시나리오 3: 가중평균 단가 ─────────────────────────────────────────────

    @Test
    @DisplayName("100주@50,000 추가 50주@55,000 → 평단 51,666.67")
    void weightedAveragePrice() {
        Position pos = Position.empty("005930");
        pos.applyBuy(100, 50_000.0);
        pos.applyBuy(50,  55_000.0);

        double expected = (100 * 50_000.0 + 50 * 55_000.0) / 150.0;
        assertThat(pos.getQuantity()).isEqualTo(150);
        assertThat(pos.getAveragePrice()).isCloseTo(expected, within(0.01));
    }

    // ── 시나리오 4: PendingOrderRule 차단 ────────────────────────────────────

    @Test
    @DisplayName("ACCEPTED 매수 주문 존재 → PendingOrderRule 차단 신호")
    void pendingOrderBlocksDuplicateBuy() {
        save(OrderHistory.accepted("005930", OrderSide.BUY, 1, "ORD-003"));

        assertThat(orderRepo.existsByStockCodeAndSideAndStatus(
                "005930", OrderSide.BUY, OrderStatus.ACCEPTED)).isTrue();
    }

    @Test
    @DisplayName("체결 완료 후 ACCEPTED 소멸 → 신규 매수 허용")
    void noBlockAfterFill() {
        OrderHistory order = save(OrderHistory.accepted("005930", OrderSide.BUY, 1, "ORD-004"));
        simulateFill(order, 1, 1, 72500.0);

        assertThat(orderRepo.existsByStockCodeAndSideAndStatus(
                "005930", OrderSide.BUY, OrderStatus.ACCEPTED)).isFalse();
    }

    // ── 시나리오 5: 부분체결 → PARTIAL_FILLED ────────────────────────────────

    @Test
    @DisplayName("100주 주문 중 30주 체결 → PARTIAL_FILLED, Position 30주 반영")
    void partialFillUpdatesPositionIncrementally() {
        OrderHistory order = save(OrderHistory.accepted("005930", OrderSide.BUY, 100, "ORD-005"));

        simulateFill(order, 100, 30, 72000.0);  // 100주 중 30주 체결

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIAL_FILLED);
        assertThat(order.getFilledQuantity()).isEqualTo(30);
        assertThat(positionRepo.findByStockCode("005930"))
                .hasValueSatisfying(p -> assertThat(p.getQuantity()).isEqualTo(30));
    }

    // ── 시나리오 6: 부분체결 후 잔여 체결 → 중복 가산 없음 ──────────────────

    @Test
    @DisplayName("30주 체결 후 추가 70주 체결 → Position 정확히 100주 (중복 가산 없음)")
    void secondPartialFillOnlyAddsIncrement() {
        OrderHistory order = save(OrderHistory.accepted("005930", OrderSide.BUY, 100, "ORD-006"));

        // 1차: 30주 체결
        simulateFill(order, 100, 30, 72000.0);
        assertThat(positionRepo.findByStockCode("005930").map(Position::getQuantity)).hasValue(30);

        // 2차: 누적 100주 = 추가 70주
        simulateFill(order, 100, 100, 72500.0);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(positionRepo.findByStockCode("005930").map(Position::getQuantity)).hasValue(100);
    }

    // ── 시나리오 7: 상태머신 — 터미널 상태 역전환 차단 ──────────────────────

    @Test
    @DisplayName("FILLED 상태에서 markFilled() 재호출 → IllegalStateException")
    void stateMachineRejectsInvalidTransition() {
        OrderHistory order = save(OrderHistory.accepted("005930", OrderSide.BUY, 1, "ORD-007"));
        order.markFilled(1, 72500.0);

        assertThatThrownBy(() -> order.markFilled(1, 73000.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FILLED");
    }

    @Test
    @DisplayName("TIMEOUT 상태에서 markPartialFilled() → IllegalStateException")
    void timeoutIsTerminal() {
        OrderHistory order = save(OrderHistory.accepted("005930", OrderSide.BUY, 1, "ORD-008"));
        order.markTimeout();

        assertThatThrownBy(() -> order.markPartialFilled(1, 72500.0))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── 시나리오 8: applySell 음수 가드 ──────────────────────────────────────

    @Test
    @DisplayName("보유 수량 초과 매도 → IllegalStateException")
    void applySellGuardsPreventsNegativeQuantity() {
        Position pos = Position.empty("005930");
        pos.applyBuy(1, 72500.0);

        assertThatThrownBy(() -> pos.applySell(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("초과");
    }

    // ── 시나리오 9: filledQuantity 감소 가드 ─────────────────────────────────

    @Test
    @DisplayName("30주 체결 후 10주로 감소 시도 → IllegalStateException")
    void filledQuantityDecreasePrevented() {
        OrderHistory order = save(OrderHistory.accepted("005930", OrderSide.BUY, 100, "ORD-009"));
        order.markPartialFilled(30, 72000.0);

        assertThatThrownBy(() -> order.markPartialFilled(10, 71000.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("감소");
    }

    @Test
    @DisplayName("30주 체결 후 markFilled(10) 시도 → IllegalStateException")
    void filledQuantityDecreasePreventsFullFill() {
        OrderHistory order = save(OrderHistory.accepted("005930", OrderSide.BUY, 100, "ORD-010"));
        order.markPartialFilled(30, 72000.0);

        assertThatThrownBy(() -> order.markFilled(10, 71000.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("감소");
    }

    // ── 시나리오 10: CANCEL_REQUESTED 상태 전이 ──────────────────────────────

    @Test
    @DisplayName("타임아웃 → CANCEL_REQUESTED → 취소 확정 → CANCELLED")
    void cancelRequestedThenCancelled() {
        OrderHistory order = save(OrderHistory.accepted("005930", OrderSide.BUY, 100, "ORD-011"));
        order.markCancelRequested();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);

        // 취소 창 동안 신규 체결 없음 → 취소 확정
        order.markCancelled();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("CANCEL_REQUESTED 중 신규 체결 → recordFillDuringCancel 후 CANCELLED")
    void cancelRequestedWithFillDuringCancel() {
        OrderHistory order = save(OrderHistory.accepted("005930", OrderSide.BUY, 100, "ORD-012"));
        order.markPartialFilled(30, 72000.0);
        order.markCancelRequested();

        Position pos = Position.empty("005930");
        pos.applyBuy(30, 72000.0);
        positionRepo.save(pos);

        // 취소 창 동안 70주 추가 체결 (합계 100주 → 전량 체결)
        order.markFilled(100, 72300.0);
        pos.applyBuy(70, 72300.0);
        positionRepo.save(pos);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(positionRepo.findByStockCode("005930").map(Position::getQuantity)).hasValue(100);
    }

    @Test
    @DisplayName("CANCEL_REQUESTED 중 일부 체결 → recordFillDuringCancel 후 CANCELLED")
    void cancelRequestedWithPartialFillThenCancelled() {
        OrderHistory order = save(OrderHistory.accepted("005930", OrderSide.BUY, 100, "ORD-013"));
        order.markCancelRequested();

        // 취소 창 동안 30주만 체결됨 (나머지 70주는 취소)
        order.recordFillDuringCancel(30, 72000.0);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(order.getFilledQuantity()).isEqualTo(30);

        order.markCancelled();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getFilledQuantity()).isEqualTo(30);  // 부분 체결 기록 보존
    }

    @Test
    @DisplayName("FILLED 상태에서 markCancelRequested() → IllegalStateException")
    void filledCannotBeginCancelRequest() {
        OrderHistory order = save(OrderHistory.accepted("005930", OrderSide.BUY, 1, "ORD-014"));
        order.markFilled(1, 72500.0);

        assertThatThrownBy(order::markCancelRequested)
                .isInstanceOf(IllegalStateException.class);
    }

    // ── 시나리오 11: recordFillDuringCancel 상태 불변 확인 ──────────────────

    @Test
    @DisplayName("recordFillDuringCancel은 filledQty만 갱신하고 CANCEL_REQUESTED 유지")
    void recordFillDuringCancelKeepsStatus() {
        OrderHistory order = save(OrderHistory.accepted("005930", OrderSide.BUY, 100, "ORD-015"));
        order.markCancelRequested();

        order.recordFillDuringCancel(30, 72000.0);

        // 상태: CANCEL_REQUESTED 유지 (PARTIAL_FILLED로 바뀌면 안 됨)
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(order.getFilledQuantity()).isEqualTo(30);
    }

    @Test
    @DisplayName("recordFillDuringCancel에서 수량 감소 시도 → IllegalStateException")
    void recordFillDuringCancelDecreasePrevented() {
        OrderHistory order = save(OrderHistory.accepted("005930", OrderSide.BUY, 100, "ORD-016"));
        order.markCancelRequested();
        order.recordFillDuringCancel(40, 72000.0);

        assertThatThrownBy(() -> order.recordFillDuringCancel(20, 71000.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("감소");
    }

    // ── 테스트 헬퍼 ──────────────────────────────────────────────────────────

    private OrderHistory save(OrderHistory order) {
        orderRepo.save(order);
        return order;
    }

    /**
     * FillPoller.processOrder() 의 핵심 로직을 재현한다.
     *
     * @param order       대상 주문
     * @param totalOrder  원래 주문 수량 (order.getQuantity() 역할)
     * @param totalFilled KIS API가 반환하는 누적 체결 수량 (tot_ccld_qty)
     * @param avgPrice    평균 체결 단가
     */
    private void simulateFill(OrderHistory order, int totalOrder, int totalFilled, double avgPrice) {
        int newlyFilled = totalFilled - order.getFilledQuantity();

        if (newlyFilled > 0) {
            Position pos = positionRepo.findByStockCode(order.getStockCode())
                    .orElse(Position.empty(order.getStockCode()));

            if (order.getSide() == OrderSide.BUY) {
                pos.applyBuy(newlyFilled, avgPrice);
                positionRepo.save(pos);
            } else {
                pos.applySell(newlyFilled);
                if (pos.getQuantity() == 0) positionRepo.delete(pos);
                else positionRepo.save(pos);
            }
        }

        if (totalFilled >= totalOrder) {
            order.markFilled(totalFilled, avgPrice);
        } else {
            order.markPartialFilled(totalFilled, avgPrice);
        }
        orderRepo.save(order);
    }

    // ── Stub Repository ───────────────────────────────────────────────────────

    private class StubOrderHistoryRepository implements OrderHistoryRepository {
        private final java.util.Map<Long, OrderHistory> store;
        StubOrderHistoryRepository(java.util.Map<Long, OrderHistory> store) { this.store = store; }

        @Override public <S extends OrderHistory> S save(S e) { store.put(autoId++, e); return e; }
        @Override public java.util.List<OrderHistory> findByStatus(OrderStatus s) {
            return store.values().stream().filter(o -> o.getStatus() == s).toList(); }
        @Override public java.util.List<OrderHistory> findByStatusIn(java.util.List<OrderStatus> ss) {
            return store.values().stream().filter(o -> ss.contains(o.getStatus())).toList(); }
        @Override public boolean existsByStockCodeAndSideAndStatus(String code, OrderSide side, OrderStatus status) {
            return store.values().stream().anyMatch(o ->
                    o.getStockCode().equals(code) && o.getSide() == side && o.getStatus() == status); }
        @Override public java.util.Optional<OrderHistory> findById(Long id) { return java.util.Optional.ofNullable(store.get(id)); }
        @Override public java.util.List<OrderHistory> findAll() { return new java.util.ArrayList<>(store.values()); }
        @Override public void delete(OrderHistory e) { store.values().remove(e); }
        @Override public void deleteById(Long id) { store.remove(id); }
        @Override public long count() { return store.size(); }
        @Override public boolean existsById(Long id) { return store.containsKey(id); }
        @Override public <S extends OrderHistory> java.util.List<S> saveAll(Iterable<S> it) { it.forEach(this::save); return new java.util.ArrayList<>(); }
        @Override public java.util.List<OrderHistory> findAllById(Iterable<Long> ids) { return java.util.List.of(); }
        @Override public void deleteAll() { store.clear(); }
        @Override public void deleteAll(Iterable<? extends OrderHistory> it) {}
        @Override public void deleteAllById(Iterable<? extends Long> ids) {}
        @Override public void flush() {}
        @Override public <S extends OrderHistory> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends OrderHistory> java.util.List<S> saveAllAndFlush(Iterable<S> it) { return saveAll(it); }
        @Override public void deleteAllInBatch(Iterable<OrderHistory> it) {}
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public OrderHistory getOne(Long id) { return store.get(id); }
        @Override public OrderHistory getById(Long id) { return store.get(id); }
        @Override public OrderHistory getReferenceById(Long id) { return store.get(id); }
        @Override public <S extends OrderHistory> java.util.Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { return java.util.Optional.empty(); }
        @Override public <S extends OrderHistory> java.util.List<S> findAll(org.springframework.data.domain.Example<S> ex) { return java.util.List.of(); }
        @Override public <S extends OrderHistory> java.util.List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort s) { return java.util.List.of(); }
        @Override public <S extends OrderHistory> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Pageable p) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends OrderHistory> long count(org.springframework.data.domain.Example<S> ex) { return 0; }
        @Override public <S extends OrderHistory> boolean exists(org.springframework.data.domain.Example<S> ex) { return false; }
        @Override public <S extends OrderHistory, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> q) { return null; }
        @Override public java.util.List<OrderHistory> findAll(org.springframework.data.domain.Sort s) { return findAll(); }
        @Override public org.springframework.data.domain.Page<OrderHistory> findAll(org.springframework.data.domain.Pageable p) { return org.springframework.data.domain.Page.empty(); }
    }

    private static class StubPositionRepository implements PositionRepository {
        private final java.util.Map<String, Position> store;
        StubPositionRepository(java.util.Map<String, Position> store) { this.store = store; }
        @Override public java.util.Optional<Position> findByStockCode(String c) { return java.util.Optional.ofNullable(store.get(c)); }
        @Override public <S extends Position> S save(S e) { store.put(e.getStockCode(), e); return e; }
        @Override public void delete(Position e) { store.remove(e.getStockCode()); }
        @Override public java.util.List<Position> findAll() { return new java.util.ArrayList<>(store.values()); }
        @Override public java.util.Optional<Position> findById(Long id) { return java.util.Optional.empty(); }
        @Override public long count() { return store.size(); }
        @Override public boolean existsById(Long id) { return false; }
        @Override public <S extends Position> java.util.List<S> saveAll(Iterable<S> it) { it.forEach(this::save); return new java.util.ArrayList<>(); }
        @Override public java.util.List<Position> findAllById(Iterable<Long> ids) { return java.util.List.of(); }
        @Override public void deleteById(Long id) {}
        @Override public void deleteAll() { store.clear(); }
        @Override public void deleteAll(Iterable<? extends Position> it) {}
        @Override public void deleteAllById(Iterable<? extends Long> ids) {}
        @Override public void flush() {}
        @Override public <S extends Position> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends Position> java.util.List<S> saveAllAndFlush(Iterable<S> it) { return saveAll(it); }
        @Override public void deleteAllInBatch(Iterable<Position> it) {}
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public Position getOne(Long id) { return null; }
        @Override public Position getById(Long id) { return null; }
        @Override public Position getReferenceById(Long id) { return null; }
        @Override public <S extends Position> java.util.Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { return java.util.Optional.empty(); }
        @Override public <S extends Position> java.util.List<S> findAll(org.springframework.data.domain.Example<S> ex) { return java.util.List.of(); }
        @Override public <S extends Position> java.util.List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort s) { return java.util.List.of(); }
        @Override public <S extends Position> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Pageable p) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends Position> long count(org.springframework.data.domain.Example<S> ex) { return 0; }
        @Override public <S extends Position> boolean exists(org.springframework.data.domain.Example<S> ex) { return false; }
        @Override public <S extends Position, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> q) { return null; }
        @Override public java.util.List<Position> findAll(org.springframework.data.domain.Sort s) { return findAll(); }
        @Override public org.springframework.data.domain.Page<Position> findAll(org.springframework.data.domain.Pageable p) { return org.springframework.data.domain.Page.empty(); }
    }
}
