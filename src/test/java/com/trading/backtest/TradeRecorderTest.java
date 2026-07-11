package com.trading.backtest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("TradeRecorder — 원가 안분 라운드트립 집계")
class TradeRecorderTest {

    private static final LocalDate ENTRY = LocalDate.of(2026, 1, 5);
    private static final LocalDate EXIT  = LocalDate.of(2026, 1, 5);

    private TradeRecorder sut;

    @BeforeEach
    void setUp() {
        sut = new TradeRecorder();
    }

    @Test
    @DisplayName("전량 매도 시 라운드트립 손익 = 매도 유입 − 매수 원가")
    void fullClose() {
        sut.onBuyFill("005930", 10, 1_000_000, ENTRY);
        sut.onSellFill("005930", 10, 1_030_000, EXIT, "TimeCut-1515");

        assertThat(sut.getTrades()).hasSize(1);
        TradeRecorder.ClosedTrade t = sut.getTrades().get(0);
        assertThat(t.pnl()).isCloseTo(30_000, within(1e-6));
        assertThat(t.returnPct()).isCloseTo(0.03, within(1e-9));
        assertThat(t.exitReason()).isEqualTo("TimeCut-1515");
    }

    @Test
    @DisplayName("부분 매도는 원가를 수량 비례로 안분한다")
    void partialClose() {
        sut.onBuyFill("005930", 10, 1_000_000, ENTRY);
        sut.onSellFill("005930", 4, 420_000, EXIT, "StopLoss-ATR");

        assertThat(sut.getTrades()).hasSize(1);
        assertThat(sut.getTrades().get(0).cost()).isCloseTo(400_000, within(1e-6));
        assertThat(sut.getTrades().get(0).pnl()).isCloseTo(20_000, within(1e-6));

        // 잔여 랏 6주 = 원가 600,000 — 이후 전량 매도로 확인
        sut.onSellFill("005930", 6, 590_000, EXIT, "TimeCut-1515");
        assertThat(sut.getTrades()).hasSize(2);
        assertThat(sut.getTrades().get(1).cost()).isCloseTo(600_000, within(1e-6));
    }

    @Test
    @DisplayName("reset()은 원장·자산 곡선을 모두 비운다 (런 간 누출 차단)")
    void reset() {
        sut.onBuyFill("005930", 1, 100_000, ENTRY);
        sut.recordDayEnd(1_000_000);
        sut.reset();

        assertThat(sut.getTrades()).isEmpty();
        assertThat(sut.getDailyEquity()).isEmpty();
        // 리셋 후 이전 랏이 남아 있으면 안 된다 — 원장 없는 매도는 무시된다
        sut.onSellFill("005930", 1, 200_000, EXIT, "TimeCut-1515");
        assertThat(sut.getTrades()).isEmpty();
    }
}
