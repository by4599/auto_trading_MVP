package com.trading.scheduler;

import com.trading.market.KisProperties;
import com.trading.order.KisOrderClient;
import com.trading.order.OrderEngine;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.position.Account;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskResult;
import com.trading.risk.RiskRule;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 15:15 타임컷 (F-4) 단위 테스트.
 *
 * 이 JVM(Java 25)의 Mockito는 구체 클래스를 목킹하지 못하므로
 * RiskEngine/OrderEngine/TradingStatusManager/KisProperties는 실객체로 구성하고
 * 인터페이스(KisOrderClient, PositionRepository 등)만 목킹한다.
 * → 타임컷이 Signal → RiskEngine → OrderEngine 경로를 실제로 통과하는지까지 검증된다.
 */
@DisplayName("TimeCutScheduler — 15:15 보유분 전량 정리")
class TimeCutSchedulerTest {

    private PositionRepository positionRepository;
    private OrderHistoryRepository orderHistoryRepository;
    private PositionManager positionManager;
    private KisOrderClient orderClient;
    private TradingStatusManager statusManager;
    private KisProperties kisProperties;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        orderHistoryRepository = mock(OrderHistoryRepository.class);
        positionManager = mock(PositionManager.class);
        orderClient = mock(KisOrderClient.class);
        statusManager = new TradingStatusManager();

        kisProperties = new KisProperties();
        kisProperties.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        kisProperties.setAppkey("test-appkey");
        kisProperties.setSecretkey("test-secretkey");
        kisProperties.setAccountNo("50000000-01");

        when(positionManager.snapshotAccount())
                .thenReturn(new Account(1_000_000.0, 0.0, 0, List.of()));
    }

    private TimeCutScheduler scheduler(List<RiskRule> rules) {
        return new TimeCutScheduler(
                positionRepository, orderHistoryRepository, positionManager,
                new RiskEngine(rules), new OrderEngine(orderClient, statusManager),
                statusManager, kisProperties);
    }

    private static Position holding(String stockCode, int quantity, double price) {
        Position pos = Position.empty(stockCode);
        pos.applyBuy(quantity, price);
        return pos;
    }

    // ── 기본 흐름 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("보유 포지션 존재 → SELL 신호가 RiskEngine→OrderEngine 경로로 매도 실행")
    void sells_held_position_through_order_engine() {
        when(positionRepository.findAll()).thenReturn(List.of(holding("005930", 1, 72500.0)));

        scheduler(List.of()).executeTimeCut();

        verify(orderClient).sell("005930");
    }

    @Test
    @DisplayName("보유 포지션 없음 → 매도 없음")
    void no_position_no_order() {
        when(positionRepository.findAll()).thenReturn(List.of());

        scheduler(List.of()).executeTimeCut();

        verify(orderClient, never()).sell(anyString());
    }

    @Test
    @DisplayName("수량 0 포지션(정리된 행) → 매도 없음")
    void zero_quantity_position_skipped() {
        when(positionRepository.findAll()).thenReturn(List.of(Position.empty("005930")));

        scheduler(List.of()).executeTimeCut();

        verify(orderClient, never()).sell(anyString());
    }

    // ── 가드 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FORCE_LIQUIDATING 모드 → 타임컷 양보 (청산 경로가 포지션 소유)")
    void yields_to_liquidation_mode() {
        statusManager.changeMode(TradingMode.FORCE_LIQUIDATING);
        when(positionRepository.findAll()).thenReturn(List.of(holding("005930", 1, 72500.0)));

        scheduler(List.of()).executeTimeCut();

        verify(orderClient, never()).sell(anyString());
    }

    @Test
    @DisplayName("EMERGENCY_STOPPED 모드 → 매도 없음")
    void skips_when_emergency_stopped() {
        statusManager.changeMode(TradingMode.EMERGENCY_STOPPED);
        when(positionRepository.findAll()).thenReturn(List.of(holding("005930", 1, 72500.0)));

        scheduler(List.of()).executeTimeCut();

        verify(orderClient, never()).sell(anyString());
    }

    @Test
    @DisplayName("KIS 자격증명 미설정 → 매도 없음")
    void skips_when_not_configured() {
        kisProperties.setAppkey("");
        when(positionRepository.findAll()).thenReturn(List.of(holding("005930", 1, 72500.0)));

        scheduler(List.of()).executeTimeCut();

        verify(orderClient, never()).sell(anyString());
    }

    @Test
    @DisplayName("미체결 SELL 주문 존재 → 중복 매도 방지")
    void skips_when_pending_sell_exists() {
        when(positionRepository.findAll()).thenReturn(List.of(holding("005930", 1, 72500.0)));
        when(orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                "005930", OrderSide.SELL, OrderStatus.ACCEPTED)).thenReturn(true);

        scheduler(List.of()).executeTimeCut();

        verify(orderClient, never()).sell(anyString());
    }

    @Test
    @DisplayName("RiskRule 거부 → 매도 없음 (RiskEngine을 건너뛰지 않는다)")
    void respects_risk_engine_rejection() {
        when(positionRepository.findAll()).thenReturn(List.of(holding("005930", 1, 72500.0)));
        RiskRule rejectAll = (signal, account) -> RiskResult.reject("테스트 거부");

        scheduler(List.of(rejectAll)).executeTimeCut();

        verify(orderClient, never()).sell(anyString());
    }

    // ── 종목별 예외 격리 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("한 종목 매도 실패해도 나머지 종목은 계속 진행")
    void one_failure_does_not_stop_the_rest() {
        when(positionRepository.findAll()).thenReturn(List.of(
                holding("005930", 1, 72500.0), holding("000660", 1, 190000.0)));
        doThrow(new IllegalStateException("KIS 오류")).when(orderClient).sell("005930");

        scheduler(List.of()).executeTimeCut();

        verify(orderClient).sell("000660");
    }
}
