package com.trading.bucket;

import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResult;
import com.trading.position.TradeResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 칸별 장부 계산 — 칸 자산 = 배분금 + 실현손익, 가용 현금 = 자산 − 투입 원가.
 * 리포지토리(인터페이스)만 목킹 — Java 25 Mockito 제약.
 */
@DisplayName("BucketAccountService — 칸 자산·가용 현금 계산")
class BucketAccountServiceTest {

    private PositionRepository positionRepository;
    private TradeResultRepository tradeResultRepository;
    private BucketAccountService sut;

    private static BucketProperties props(boolean enabled) {
        // 실험 시작일을 과거로 — TradeResult.live()는 오늘 날짜로 기록되므로 포함되게 한다
        return new BucketProperties(enabled, "2026-01-01",
                10_000_000, 10_000_000, 10_000_000, false, false);
    }

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        tradeResultRepository = mock(TradeResultRepository.class);
        when(positionRepository.findAll()).thenReturn(List.of());
        when(tradeResultRepository.findAll()).thenReturn(List.of());
        sut = new BucketAccountService(props(true), positionRepository, tradeResultRepository);
    }

    @Test
    @DisplayName("거래 없음 → 칸 자산 = 배분금 1천만, 가용 현금도 1천만")
    void fresh_bucket_equals_allocation() {
        assertThat(sut.equity(StrategyBucket.VB)).isEqualTo(10_000_000);
        assertThat(sut.availableCash(StrategyBucket.VB)).isEqualTo(10_000_000);
    }

    @Test
    @DisplayName("실현손익 +5만 반영 → 칸 자산 1,005만")
    void realized_pnl_added_to_equity() {
        // 7만 매수 → 7.5만 매도 10주 = +50,000 (실험 시작일 이후, 칸 미표기 → VB 간주)
        TradeResult win = TradeResult.live("005930", 10, 70_000, 75_000);
        when(tradeResultRepository.findAll()).thenReturn(List.of(win));

        assertThat(sut.equity(StrategyBucket.VB)).isEqualTo(10_050_000);
    }

    @Test
    @DisplayName("보유 포지션 원가 700만 → 가용 현금 300만")
    void invested_cost_reduces_available_cash() {
        Position held = Position.empty("005930");
        held.applyBuy(100, 70_000);
        when(positionRepository.findAll()).thenReturn(List.of(held));

        assertThat(sut.availableCash(StrategyBucket.VB)).isEqualTo(3_000_000);
    }

    @Test
    @DisplayName("다른 칸(EVENT) 실적·보유는 VB 장부에 섞이지 않는다")
    void buckets_are_isolated() {
        TradeResult eventWin = TradeResult.live("000660", 10, 70_000, 80_000, StrategyBucket.EVENT);
        Position eventPos = Position.empty("000660");
        eventPos.applyBuy(10, 70_000);
        eventPos.assignBucketIfAbsent(StrategyBucket.EVENT);
        when(tradeResultRepository.findAll()).thenReturn(List.of(eventWin));
        when(positionRepository.findAll()).thenReturn(List.of(eventPos));

        assertThat(sut.equity(StrategyBucket.VB)).isEqualTo(10_000_000);
        assertThat(sut.availableCash(StrategyBucket.VB)).isEqualTo(10_000_000);
        assertThat(sut.equity(StrategyBucket.EVENT)).isEqualTo(10_100_000);
    }

    @Test
    @DisplayName("실험 시작일 이전 실적은 칸 장부에서 제외")
    void trades_before_experiment_start_excluded() {
        TradeResult old = TradeResult.backfill("005930", 10, 70_000, 80_000,
                java.time.LocalDateTime.of(2025, 12, 31, 15, 0));
        when(tradeResultRepository.findAll()).thenReturn(List.of(old));

        assertThat(sut.equity(StrategyBucket.VB)).isEqualTo(10_000_000);
    }

    @Test
    @DisplayName("칸 OFF → sizingEquity는 계좌 전체 자산을 그대로 반환 (백테스트 보존)")
    void disabled_bucketing_passes_account_equity_through() {
        BucketAccountService off = new BucketAccountService(
                props(false), positionRepository, tradeResultRepository);

        assertThat(off.sizingEquity(StrategyBucket.VB, 52_345_678)).isEqualTo(52_345_678);
    }
}
