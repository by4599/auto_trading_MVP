package com.trading.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("ScaleInMath · ScaleInProfile · ScaleInRunStats — 분할 매수 순수 계산과 격자")
class ScaleInMathTest {

    @Test
    @DisplayName("트리거 = 1차가 + (n−1)·k·ATR, 1차(n=1)는 트리거가 없다")
    void triggerPrice() {
        assertThat(ScaleInMath.triggerPrice(100, 20, 0.5, 2)).isEqualTo(110.0);
        assertThat(ScaleInMath.triggerPrice(100, 20, 0.5, 3)).isEqualTo(120.0);
        assertThat(ScaleInMath.triggerPrice(100, 20, 1.0, 3)).isEqualTo(140.0);
        assertThatThrownBy(() -> ScaleInMath.triggerPrice(100, 20, 0.5, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("체결 원가 = max(시가, 트리거) — 갭 상승이면 더 비싼 시가, 고가 미달이면 미발동")
    void addRawPriceAndTrigger() {
        assertThat(ScaleInMath.addRawPrice(105, 110)).isEqualTo(110.0);
        assertThat(ScaleInMath.addRawPrice(115, 110)).isEqualTo(115.0);
        assertThat(ScaleInMath.triggered(109.99, 110)).isFalse();
        assertThat(ScaleInMath.triggered(110, 110)).isTrue();
    }

    @Test
    @DisplayName("1차 금액: 분할은 목표÷3, 전량은 목표 전부(하락추세 제한이면 1/3)")
    void firstTrancheAmount() {
        assertThat(ScaleInMath.firstTrancheAmount(10_000_000, 0.2, 3, false)).isCloseTo(666_666.67, within(0.01));
        assertThat(ScaleInMath.firstTrancheAmount(10_000_000, 0.2, 1, false)).isEqualTo(2_000_000.0);
        assertThat(ScaleInMath.firstTrancheAmount(10_000_000, 0.2, 1, true)).isCloseTo(666_666.67, within(0.01));
        assertThat(ScaleInMath.laterTrancheAmount(10_000_000, 0.2, 1)).isZero();
    }

    @Test
    @DisplayName("수량 = floor(금액÷원가), 비용 포함 현금 한도로 다시 깎는다")
    void quantityCapsByCash() {
        assertThat(ScaleInMath.quantity(1_000_000, 100, 0.001, 10_000_000)).isEqualTo(10_000);
        int capped = ScaleInMath.quantity(1_000_000, 100, 0.001, 500_000);
        assertThat(BacktestCosts.buyCashOut(BacktestCosts.buyFillPrice(100, 0.001), capped)).isLessThanOrEqualTo(500_000);
        assertThat(BacktestCosts.buyCashOut(BacktestCosts.buyFillPrice(100, 0.001), capped + 1)).isGreaterThan(500_000);
        assertThat(ScaleInMath.quantity(50, 100, 0.001, 10_000_000)).isZero();
    }

    @Test
    @DisplayName("손절 재설정: 1차가 기준은 불변, 평균단가 기준은 평균 − 손절폭")
    void rebasedStop() {
        assertThat(ScaleInMath.rebasedStop(ScaleInProperties.StopBasis.FIRST_ENTRY, 100, 108, 20)).isEqualTo(80.0);
        assertThat(ScaleInMath.rebasedStop(ScaleInProperties.StopBasis.AVERAGE, 100, 108, 20)).isEqualTo(88.0);
    }

    @Test
    @DisplayName("본 격자 15칸·코드 유일, 앵커는 분할 OFF, 모르는 코드는 거부")
    void gridAndAnchors() {
        List<ScaleInProfile> grid = ScaleInProfile.mainGrid();
        assertThat(grid).hasSize(15);
        Set<String> codes = new HashSet<>();
        grid.forEach(p -> codes.add(p.code()));
        assertThat(codes).hasSize(15).contains("C100-a", "S05F-b", "S10A-c");
        assertThat(grid).allMatch(ScaleInProfile::scaleIn);
        assertThat(ScaleInLab.ANCHOR_SZ0.scaleIn()).isFalse();
        assertThat(ScaleInLab.ANCHOR_SZ2.riskFraction()).isEqualTo(0.0025);
        assertThatThrownBy(() -> ScaleInLabRouter.findProfile("없음"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("민감도 격자: 분할은 k ±20% 포함 11칸, 전량(C100)은 k 칸 없이 9칸")
    void sensitivityGrid() {
        List<ScaleInProfile> split = ScaleInLab.sensitivityGrid(ScaleInLabRouter.findProfile("S05F-a"));
        assertThat(split).hasSize(11);
        assertThat(split.get(3).stepAtr()).isCloseTo(0.4, within(1e-9));
        assertThat(split.get(4).stepAtr()).isCloseTo(0.6, within(1e-9));
        assertThat(ScaleInLab.sensitivityGrid(ScaleInLabRouter.findProfile("C100-a"))).hasSize(9);
    }

    @Test
    @DisplayName("손절 폭 격자(56 §0.4): 15칸·코드 유일 · N=수량1.0+손절없음 · B=수량1.0 고정 · 민감도는 배수 ×0.8/×1.2")
    void stopWidthGrid() {
        List<StopWidthProfile> grid = StopWidthProfile.grid();
        assertThat(grid).hasSize(15);
        Set<String> codes = new HashSet<>();
        grid.forEach(p -> codes.add(p.code()));
        assertThat(codes).hasSize(15).contains("N", "A10-S", "A30-X", "B15-S", "B30-X");
        StopWidthProfile none = grid.get(0);
        assertThat(none.sizingMult()).isEqualTo(1.0);
        assertThat(none.stopMult()).isZero();
        StopWidthProfile b20 = ScaleInLabRouter.findStopProfile("B20-X");
        assertThat(b20.sizingMult()).isEqualTo(1.0);
        assertThat(b20.stopMult()).isEqualTo(2.0);
        assertThat(b20.entryDay()).isEqualTo(BacktestStopRules.EntryDayStop.FROM_NEXT_DAY);
        assertThat(ScaleInLabRouter.findStopProfile("A20-S").stopMult()).isNaN();

        List<StopWidthProfile> sens = StopWidthProfile.sensitivity(ScaleInLabRouter.findStopProfile("A20-S"));
        assertThat(sens).hasSize(9);
        assertThat(sens.get(1).sizingMult()).isCloseTo(1.6, within(1e-9));
        assertThat(sens.get(2).sizingMult()).isCloseTo(2.4, within(1e-9));
        List<StopWidthProfile> sensB = StopWidthProfile.sensitivity(b20);
        assertThat(sensB.get(1).stopMult()).isCloseTo(1.6, within(1e-9));
        assertThat(sensB.get(1).sizingMult()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("현금 비중·사건 수는 검증 런에서만 센다")
    void runStatsCountValidateOnly() {
        ScaleInRunStats stats = new ScaleInRunStats();
        stats.beginRun("WF00-train-K0.5");
        stats.sampleDayEnd(100, 100);
        stats.count("x");
        stats.beginRun("WF00-validate-K0.5");
        stats.sampleDayEnd(25, 100);
        stats.sampleDayEnd(75, 100);
        stats.count("x");

        assertThat(stats.averageCashRatio()).isEqualTo(0.5);
        assertThat(stats.sampledDays()).isEqualTo(2);
        assertThat(stats.counters()).containsEntry("x", 1);
        stats.clear();
        assertThat(stats.averageCashRatio()).isNaN();
    }
}
