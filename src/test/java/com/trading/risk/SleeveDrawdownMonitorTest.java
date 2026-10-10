package com.trading.risk;

import com.trading.bucket.StrategyBucket;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import com.trading.position.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 칸 낙폭 감시기 — 1분마다, 장중에만, 신선한 잔고일 때만, 활성 칸만 판정하고
 * 2회 연속 초과면 그 칸을 잠근 뒤 보유분을 정상 매도 경로(Signal → RiskEngine → OrderEngine)로 판다.
 * 조립은 {@link SleeveMonitorFixture}(인터페이스만 목). 날짜 사실관계(시스템 도구 확인): 2026-10-14 수(평일).
 */
@DisplayName("SleeveDrawdownMonitor — 칸 낙폭 감시·잠금·정리 매도")
class SleeveDrawdownMonitorTest {

    private static final LocalDateTime MARKET_OPEN_TIME = LocalDateTime.of(2026, 10, 14, 10, 0);

    private final SleeveMonitorFixture f = new SleeveMonitorFixture();

    /** A동 100주 @40,000 = 400만 — 배분금 전부를 한 종목에 (계산을 단순하게) */
    private void givenTrendHolding() {
        f.hold("005930", StrategyBucket.TREND, 100, 40_000);
    }

    private void givenBrokerPrices(double trendPrice) {
        when(f.positionManager.snapshotAccount()).thenReturn(new Account(9_000_000, 0.0, 0, List.of(
                new Account.PositionSnapshot("005930", 100, 40_000, trendPrice))));
    }

    @Test
    @DisplayName("2회 연속 한도 초과 → 잠금 저장 → 텔레그램 → 그 칸 보유분을 정상 매도 경로로 판다")
    void two_consecutive_breaches_lock_alert_and_sell() {
        givenTrendHolding();
        givenBrokerPrices(35_000);   // 평가손익 −50만 → 칸 자산 350만, 낙폭 12.5%
        SleeveDrawdownMonitor monitor = f.monitorAt(MARKET_OPEN_TIME);

        monitor.checkSleeves();
        verify(f.orderClient, never()).sell(anyString(), anyInt());
        assertThat(f.locked(StrategyBucket.TREND)).isFalse();

        monitor.checkSleeves();
        assertThat(f.locked(StrategyBucket.TREND)).isTrue();
        verify(f.notifier).sendCritical(contains("칸이 잠겼습니다"));
        verify(f.orderClient).sell("005930", 100);
    }

    @Test
    @DisplayName("한 번만 넘고 다음 회차에 돌아오면 잠그지 않는다")
    void single_breach_then_recovery_does_not_lock() {
        givenTrendHolding();
        givenBrokerPrices(35_000);
        SleeveDrawdownMonitor monitor = f.monitorAt(MARKET_OPEN_TIME);
        monitor.checkSleeves();

        givenBrokerPrices(38_000);   // 낙폭 5%
        monitor.checkSleeves();

        assertThat(f.locked(StrategyBucket.TREND)).isFalse();
        verify(f.orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("잔고가 낡은 회차는 판정 보류 — 연속을 끊어서, 사이에 끼면 다시 2회를 채워야 한다")
    void stale_round_defers_and_breaks_the_streak() {
        givenTrendHolding();
        givenBrokerPrices(35_000);
        SleeveDrawdownMonitor monitor = f.monitorAt(MARKET_OPEN_TIME);
        monitor.checkSleeves();

        when(f.positionManager.snapshotAccount()).thenReturn(new Account(9_000_000, 0.0, 0, List.of(
                new Account.PositionSnapshot("005930", 100, 40_000, 35_000))).asStale());
        monitor.checkSleeves();

        givenBrokerPrices(35_000);
        monitor.checkSleeves();

        assertThat(f.locked(StrategyBucket.TREND)).isFalse();
        verify(f.orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("장 밖에는 잔고를 부르지도 판정하지도 않는다")
    void outside_market_hours_does_nothing() {
        givenTrendHolding();
        givenBrokerPrices(35_000);
        SleeveDrawdownMonitor monitor = f.monitorAt(LocalDateTime.of(2026, 10, 14, 16, 0));

        monitor.checkSleeves();
        monitor.checkSleeves();

        verify(f.positionManager, never()).snapshotAccount();
        assertThat(f.storedPeak(StrategyBucket.TREND)).isEmpty();
        assertThat(f.locked(StrategyBucket.TREND)).isFalse();
    }

    @Test
    @DisplayName("칸 나누기가 꺼져 있으면 아무것도 하지 않는다 (백테스트 결정성·칸 미사용 환경 보존)")
    void buckets_disabled_does_nothing() {
        f.bucketsEnabled = false;
        givenTrendHolding();
        givenBrokerPrices(35_000);
        SleeveDrawdownMonitor monitor = f.monitorAt(MARKET_OPEN_TIME);

        monitor.checkSleeves();
        monitor.checkSleeves();

        verify(f.positionManager, never()).snapshotAccount();
        assertThat(f.state).isEmpty();
    }

    @Test
    @DisplayName("강제청산 중이면 양보한다 — 포지션 주인은 청산 상태머신이다")
    void yields_while_force_liquidating() {
        givenTrendHolding();
        givenBrokerPrices(35_000);
        f.statusManager.changeMode(TradingMode.FORCE_LIQUIDATING);
        SleeveDrawdownMonitor monitor = f.monitorAt(MARKET_OPEN_TIME);

        monitor.checkSleeves();
        monitor.checkSleeves();

        assertThat(f.locked(StrategyBucket.TREND)).isFalse();
        verify(f.orderClient, never()).sell(anyString(), anyInt());
    }

    @Test
    @DisplayName("꺼진 칸(EVENT·MIX)은 판정하지 않는다 — 배분금이 있어도 활성 칸만")
    void inactive_buckets_are_not_evaluated() {
        givenBrokerPrices(40_000);
        SleeveDrawdownMonitor monitor = f.monitorAt(MARKET_OPEN_TIME);

        monitor.checkSleeves();

        assertThat(f.storedPeak(StrategyBucket.TREND)).contains(4_000_000.0);
        assertThat(f.storedPeak(StrategyBucket.VB)).contains(10_000_000.0);
        assertThat(f.storedPeak(StrategyBucket.EVENT)).isEmpty();
        assertThat(f.storedPeak(StrategyBucket.MIX)).isEmpty();
    }

    @Test
    @DisplayName("잠긴 칸에 보유가 남아도 같은 종목에 미체결 매도가 있으면 다시 내지 않는다, 없어지면 다시 낸다")
    void resells_remaining_holding_only_without_pending_sell() {
        givenTrendHolding();
        givenBrokerPrices(35_000);
        SleeveDrawdownMonitor monitor = f.monitorAt(MARKET_OPEN_TIME);
        monitor.checkSleeves();
        monitor.checkSleeves();                     // 잠금 + 1차 매도
        verify(f.orderClient, times(1)).sell("005930", 100);

        when(f.orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                "005930", OrderSide.SELL, OrderStatus.ACCEPTED)).thenReturn(true);
        monitor.checkSleeves();                     // 미체결 대기 중 — 이중 매도 금지
        verify(f.orderClient, times(1)).sell("005930", 100);

        when(f.orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                "005930", OrderSide.SELL, OrderStatus.ACCEPTED)).thenReturn(false);
        monitor.checkSleeves();                     // 앞 주문이 실패로 끝났다 — 다시 낸다
        verify(f.orderClient, times(2)).sell("005930", 100);
    }

    @Test
    @DisplayName("증권사 잔고에 이미 없으면(체결됐는데 DB가 아직 모름) 다시 내지 않는다")
    void does_not_resell_when_broker_is_already_flat() {
        givenTrendHolding();
        givenBrokerPrices(35_000);
        SleeveDrawdownMonitor monitor = f.monitorAt(MARKET_OPEN_TIME);
        monitor.checkSleeves();
        monitor.checkSleeves();

        when(f.positionManager.snapshotAccount()).thenReturn(new Account(9_000_000, 0.0, 0, List.of()));
        monitor.checkSleeves();

        verify(f.orderClient, times(1)).sell("005930", 100);
    }

    @Test
    @DisplayName("A동이 잠겨도 B동(VB) 보유는 팔지 않는다 — 그 칸만 멈춘다")
    void other_sleeve_holdings_are_untouched() {
        givenTrendHolding();
        f.hold("000660", StrategyBucket.VB, 10, 100_000);
        when(f.positionManager.snapshotAccount()).thenReturn(new Account(9_000_000, 0.0, 0, List.of(
                new Account.PositionSnapshot("005930", 100, 40_000, 35_000),
                new Account.PositionSnapshot("000660", 10, 100_000, 100_000))));
        SleeveDrawdownMonitor monitor = f.monitorAt(MARKET_OPEN_TIME);

        monitor.checkSleeves();
        monitor.checkSleeves();

        verify(f.orderClient).sell("005930", 100);
        verify(f.orderClient, never()).sell(eq("000660"), anyInt());
        assertThat(f.locked(StrategyBucket.VB)).isFalse();
    }
}
