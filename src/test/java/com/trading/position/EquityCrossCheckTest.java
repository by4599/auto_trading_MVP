package com.trading.position;

import com.trading.position.BalanceClient.BalanceSnapshot;
import com.trading.position.BalanceClient.Holding;
import com.trading.position.EquityCrossCheck.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 잔고 총자산 대조 — 증권사 총자산(tot_evlu_amt)을 "D+2 정산 현금 + Σ보유수량×현재가"로 다시 계산해 비교한다.
 *
 * <p>2026-09-21 사고: 전고점이 10,890,158원으로 오염돼 7거래일간 매 개장 직후 강제청산이 발동했다(결함 6).
 * paper 낙폭 한도가 8%가 되면서 전고점이 +5.11%만 튀어도 멈추는데, 기존 상한(실측 최대 × 1.15)은 그 사이를
 * 통과시킨다. 이 판정이 그 구간을 막는다.
 */
@DisplayName("EquityCrossCheck — 증권사 총자산 대조 판정")
class EquityCrossCheckTest {

    /** 2026-09-21 교정된 전고점 = 실측 최고 시작자산(전액 현금이던 날) */
    private static final double REAL_EQUITY = 10_088_806;

    private static EquityCrossCheck cashOnly(double brokerTotal, double settledCash) {
        return EquityCrossCheck.evaluate(brokerTotal, OptionalDouble.of(settledCash), List.of());
    }

    @Test
    @DisplayName("증권사 총자산 = D+2 정산 현금 + 보유수량×현재가 이면 일치")
    void exact_match() {
        EquityCrossCheck check = EquityCrossCheck.evaluate(10_001_955, OptionalDouble.of(9_699_955),
                List.of(new Holding("005930", 4, 75_000, 75_500)));

        assertThat(check.verdict()).isEqualTo(Verdict.MATCH);
        assertThat(check.holdingsValue()).isEqualTo(302_000);
        assertThat(check.computedTotal()).isEqualTo(10_001_955);
        assertThat(check.diffRatio()).isZero();
        assertThat(check.isMismatch()).isFalse();
    }

    @Test
    @DisplayName("보유 합계는 종목마다 수량×현재가를 더한다")
    void holdings_value_sums_quantity_times_current_price() {
        double held = EquityCrossCheck.holdingsValue(List.of(
                new Holding("005930", 4, 75_000, 75_500),
                new Holding("000660", 2, 190_000, 200_000)));

        assertThat(held).isEqualTo(4 * 75_500 + 2 * 200_000);
    }

    @Test
    @DisplayName("2026-09-21 오염값(10,890,158원)은 계산값(10,088,806원)과 어긋나 불일치")
    void contamination_of_2026_09_21_is_a_mismatch() {
        EquityCrossCheck check = cashOnly(10_890_158, REAL_EQUITY);

        assertThat(check.verdict()).isEqualTo(Verdict.MISMATCH);
        assertThat(check.isMismatch()).isTrue();
        assertThat(check.diffRatio()).isCloseTo(0.07358, within(1e-4));   // (증권사 - 계산) / 증권사
    }

    @Test
    @DisplayName("8% 한도를 부르는 가장 작은 오염(+5.11%, 10,604,158원)도 불일치 — 기존 상한(11,602,127원)이 놓치던 구간")
    void smallest_dangerous_contamination_is_a_mismatch() {
        EquityCrossCheck check = cashOnly(10_604_158, REAL_EQUITY);

        assertThat(check.isMismatch()).isTrue();
    }

    @Nested
    @DisplayName("허용오차 1% 경계")
    class ToleranceBoundary {

        @Test
        @DisplayName("허용오차는 1%다 — 막아야 할 최소 오염 +5.11%보다 충분히 작고, 정상 오차(수수료 ~0.1%)보다 충분히 크다")
        void tolerance_is_one_percent() {
            assertThat(EquityCrossCheck.TOLERANCE).isEqualTo(0.01);
        }

        @Test
        @DisplayName("정확히 1% 차이는 일치로 본다")
        void exactly_one_percent_is_a_match() {
            assertThat(cashOnly(10_000_000, 9_900_000).verdict()).isEqualTo(Verdict.MATCH);
        }

        @Test
        @DisplayName("1%를 1원이라도 넘으면 불일치")
        void one_won_beyond_one_percent_is_a_mismatch() {
            assertThat(cashOnly(10_000_000, 9_899_999).verdict()).isEqualTo(Verdict.MISMATCH);
        }

        @Test
        @DisplayName("계산값이 더 커도(증권사 값이 아래로 튀어도) 1%를 넘으면 불일치 — 방향을 가리지 않는다")
        void broker_below_computed_beyond_tolerance_is_a_mismatch() {
            assertThat(cashOnly(10_000_000, 10_100_001).verdict()).isEqualTo(Verdict.MISMATCH);
        }
    }

    @Nested
    @DisplayName("판정 불가 — 막지 않는다")
    class Unchecked {

        @Test
        @DisplayName("D+2 정산 현금 칸이 없으면 판정 불가")
        void missing_settled_cash_is_unchecked() {
            EquityCrossCheck check = EquityCrossCheck.evaluate(10_890_158, OptionalDouble.empty(), List.of());

            assertThat(check.verdict()).isEqualTo(Verdict.UNCHECKED);
            assertThat(check.isMismatch()).isFalse();
        }

        @Test
        @DisplayName("증권사 총자산이 0 이하이면 판정 불가")
        void non_positive_broker_total_is_unchecked() {
            assertThat(cashOnly(0, REAL_EQUITY).verdict()).isEqualTo(Verdict.UNCHECKED);
            assertThat(cashOnly(-1, REAL_EQUITY).verdict()).isEqualTo(Verdict.UNCHECKED);
        }

        @Test
        @DisplayName("증권사 총자산이 숫자가 아니면(NaN) 판정 불가")
        void non_finite_broker_total_is_unchecked() {
            assertThat(cashOnly(Double.NaN, REAL_EQUITY).verdict()).isEqualTo(Verdict.UNCHECKED);
        }

        @Test
        @DisplayName("판정 불가의 차이는 0으로 보고한다")
        void unchecked_diff_is_zero() {
            assertThat(EquityCrossCheck.unchecked().diffRatio()).isZero();
            assertThat(EquityCrossCheck.unchecked().isMismatch()).isFalse();
        }
    }

    @Test
    @DisplayName("보유 현재가가 망가져 차이를 잴 수 없으면(NaN) 일치로 넘기지 않는다 — 불일치")
    void broken_holding_price_is_not_certified() {
        EquityCrossCheck check = EquityCrossCheck.evaluate(10_000_000, OptionalDouble.of(9_000_000),
                List.of(new Holding("005930", 4, 75_000, Double.NaN)));

        assertThat(check.verdict()).isEqualTo(Verdict.MISMATCH);
    }

    @Test
    @DisplayName("D+2 정산 현금이 음수여도(미수) 식은 같다 — 정상 판정")
    void negative_settled_cash_is_checked_normally() {
        EquityCrossCheck check = EquityCrossCheck.evaluate(9_950_000, OptionalDouble.of(-50_000),
                List.of(new Holding("005930", 100, 99_000, 100_000)));

        assertThat(check.verdict()).isEqualTo(Verdict.MATCH);
    }

    @Nested
    @DisplayName("BalanceSnapshot — 기존 3인자 생성자는 판정 불가")
    class SnapshotCompatibility {

        @Test
        @DisplayName("저장소의 기존 new BalanceSnapshot(총자산, 예수금, 보유) 30여 곳은 판정 불가를 뜻한다")
        void three_arg_snapshot_is_unchecked() {
            BalanceSnapshot snapshot = new BalanceSnapshot(10_890_158, 10_890_158, List.of());

            assertThat(snapshot.equityCheck().verdict()).isEqualTo(Verdict.UNCHECKED);
            assertThat(snapshot.totalAssetValue()).isEqualTo(10_890_158);
            assertThat(snapshot.deposit()).isEqualTo(10_890_158);
        }

        @Test
        @DisplayName("판정 칸에 null을 넣어도 판정 불가로 받는다")
        void null_check_becomes_unchecked() {
            BalanceSnapshot snapshot = new BalanceSnapshot(1, 1, List.of(), null);

            assertThat(snapshot.equityCheck().verdict()).isEqualTo(Verdict.UNCHECKED);
        }

        @Test
        @DisplayName("4인자로 넣은 판정은 그대로 실린다")
        void four_arg_snapshot_carries_the_check() {
            EquityCrossCheck mismatch = cashOnly(10_890_158, REAL_EQUITY);
            BalanceSnapshot snapshot = new BalanceSnapshot(10_890_158, REAL_EQUITY, List.of(), mismatch);

            assertThat(snapshot.equityCheck()).isSameAs(mismatch);
        }
    }
}
