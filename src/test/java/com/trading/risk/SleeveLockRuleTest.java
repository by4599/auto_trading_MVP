package com.trading.risk;

import com.trading.bucket.BucketProperties;
import com.trading.bucket.InMemoryPortfolioState;
import com.trading.bucket.SleeveStateStore;
import com.trading.bucket.StrategyBucket;
import com.trading.position.Account;
import com.trading.position.PortfolioStateRepository;
import com.trading.signal.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 칸 잠금 룰 — 잠긴 칸의 <b>매수만</b> 막는다. 매도(손절·타임컷·칸 정리)와 다른 칸은 통과.
 */
@DisplayName("SleeveLockRule — 잠긴 칸의 신규 매수 거부")
class SleeveLockRuleTest {

    private static final Account ACCOUNT = new Account(10_000_000, 0.0, 0, List.of());

    private final Map<String, Double> state = new HashMap<>();
    private SleeveLockRule rule;

    private static BucketProperties buckets(boolean enabled) {
        return new BucketProperties(enabled, "2026-07-20",
                10_000_000, 10_000_000, 10_000_000, 4_000_000, false, false, true);
    }

    @BeforeEach
    void setUp() {
        SleeveStateStore store = new SleeveStateStore(InMemoryPortfolioState.create(state));
        store.lock(StrategyBucket.TREND,
                new SleeveStateStore.LockState(true, Instant.EPOCH, 0.125, 3_500_000, 0.12));
        rule = new SleeveLockRule(buckets(true), store);
    }

    @Test
    @DisplayName("잠긴 A동(TREND)의 매수는 거부한다 — 사유에 고유 문구와 낙폭·한도가 들어간다")
    void rejects_buy_of_locked_sleeve() {
        RiskResult r = rule.validate(Signal.buy("005930", "DONCHIAN", StrategyBucket.TREND), ACCOUNT);

        assertThat(r.isPass()).isFalse();
        assertThat(r.getReason()).contains("칸 손실 상한 도달로 잠김").contains("TREND").contains("12.5%");
    }

    @Test
    @DisplayName("잠긴 칸이라도 매도는 통과한다 — 손절·칸 정리 매도를 막으면 손실이 커진다")
    void sell_passes_even_when_locked() {
        assertThat(rule.validate(Signal.sell("005930", "SleeveDrawdownCap"), ACCOUNT).isPass()).isTrue();
    }

    @Test
    @DisplayName("다른 칸(VB)의 매수는 통과한다 — 그 칸만 멈춘다")
    void other_sleeve_buy_passes() {
        assertThat(rule.validate(Signal.buy("000660", "VB", StrategyBucket.VB), ACCOUNT).isPass()).isTrue();
    }

    @Test
    @DisplayName("이름표 없는 매수 신호는 VB로 본다 — VB가 잠겼으면 막는다")
    void unlabeled_buy_is_treated_as_vb() {
        SleeveStateStore store = new SleeveStateStore(InMemoryPortfolioState.create(state));
        store.lock(StrategyBucket.VB,
                new SleeveStateStore.LockState(true, Instant.EPOCH, 0.21, 7_900_000, 0.20));

        RiskResult r = rule.validate(Signal.buy("000660", "VB"), ACCOUNT);

        assertThat(r.isPass()).isFalse();
        assertThat(r.getReason()).contains("VB");
    }

    @Test
    @DisplayName("칸 나누기가 꺼져 있으면(백테스트 등) 무조건 통과한다")
    void passes_when_buckets_disabled() {
        SleeveLockRule off = new SleeveLockRule(buckets(false),
                new SleeveStateStore(InMemoryPortfolioState.create(state)));

        assertThat(off.validate(Signal.buy("005930", "DONCHIAN", StrategyBucket.TREND), ACCOUNT).isPass())
                .isTrue();
    }

    @Test
    @DisplayName("잠금 상태를 못 읽으면 매수를 보류한다(fail-closed) — 같은 고유 문구로 진단 화면에 잡힌다")
    void unreadable_state_blocks_buy() {
        PortfolioStateRepository broken = mock(PortfolioStateRepository.class);
        when(broken.findAllById(anyIterable())).thenThrow(new IllegalStateException("DB 잠김"));
        SleeveLockRule sut = new SleeveLockRule(buckets(true), new SleeveStateStore(broken));

        RiskResult buy = sut.validate(Signal.buy("005930", "DONCHIAN", StrategyBucket.TREND), ACCOUNT);
        RiskResult sell = sut.validate(Signal.sell("005930", "StopLoss-ATR"), ACCOUNT);

        assertThat(buy.isPass()).isFalse();
        assertThat(buy.getReason()).contains("칸 손실 상한");
        assertThat(sell.isPass()).isTrue();
    }
}
