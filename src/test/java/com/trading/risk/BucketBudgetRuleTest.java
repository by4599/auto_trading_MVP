package com.trading.risk;

import com.trading.bucket.BucketAccountService;
import com.trading.bucket.BucketProperties;
import com.trading.bucket.StrategyBucket;
import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResultRepository;
import com.trading.signal.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 칸 예산 지킴이 — 칸 잠금/예산 소진 시 매수 차단, 칸 OFF·매도는 무조건 통과.
 */
@DisplayName("BucketBudgetRule — 칸 잠금·예산 소진 매수 차단")
class BucketBudgetRuleTest {

    private static final Account ACCOUNT = new Account(50_000_000, 0.0, 0, List.of());

    private PositionRepository positionRepository;
    private TradeResultRepository tradeResultRepository;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        tradeResultRepository = mock(TradeResultRepository.class);
        when(positionRepository.findAll()).thenReturn(List.of());
        when(tradeResultRepository.findAll()).thenReturn(List.of());
    }

    private BucketBudgetRule rule(boolean enabled) {
        BucketProperties props = new BucketProperties(enabled, "2026-01-01",
                10_000_000, 10_000_000, 10_000_000, false, false);
        return new BucketBudgetRule(props,
                new BucketAccountService(props, positionRepository, tradeResultRepository));
    }

    @Test
    @DisplayName("칸 OFF(백테스트 포함) → 무조건 통과")
    void disabled_bucketing_always_passes() {
        RiskResult result = rule(false).validate(Signal.buy("005930", "t"), ACCOUNT);
        assertThat(result.isPass()).isTrue();
    }

    @Test
    @DisplayName("매도 신호 → 통과 (예산 검사는 매수만)")
    void sell_signals_pass() {
        RiskResult result = rule(true).validate(Signal.sell("005930", "t"), ACCOUNT);
        assertThat(result.isPass()).isTrue();
    }

    @Test
    @DisplayName("방식1(VB) 예산 여유 → 통과")
    void active_bucket_with_cash_passes() {
        RiskResult result = rule(true).validate(
                Signal.buy("005930", "t", StrategyBucket.VB), ACCOUNT);
        assertThat(result.isPass()).isTrue();
    }

    @Test
    @DisplayName("방식2(EVENT) 잠금 상태 → 매수 차단")
    void locked_bucket_rejected() {
        RiskResult result = rule(true).validate(
                Signal.buy("005930", "t", StrategyBucket.EVENT), ACCOUNT);
        assertThat(result.isPass()).isFalse();
        assertThat(result.getReason()).contains("칸 비활성");
    }

    @Test
    @DisplayName("칸 예산 소진 (투입 원가 = 배분금) → 매수 차단")
    void exhausted_budget_rejected() {
        Position held = Position.empty("000660");
        held.applyBuy(100, 100_000);  // 투입 원가 1,000만 = 배분금 전부
        when(positionRepository.findAll()).thenReturn(List.of(held));

        RiskResult result = rule(true).validate(
                Signal.buy("005930", "t", StrategyBucket.VB), ACCOUNT);
        assertThat(result.isPass()).isFalse();
        assertThat(result.getReason()).contains("예산 소진");
    }
}
