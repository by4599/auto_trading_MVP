package com.trading.position;

import com.trading.position.EquityCrossCheck.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Account의 잔고 대조 표시 — {@code fresh}(신선도)와 같은 방식이다.
 * 기본값은 "판정 불가 = 전고점 인정 가능"이라 공개 4인자 생성자를 쓰는 곳(백테스트 포함)은 동작이 그대로다.
 */
@DisplayName("Account — 잔고 대조 표시 (기본 판정 불가 · 신선도와 서로 보존)")
class AccountEquityCheckTest {

    private static final EquityCrossCheck MISMATCH =
            EquityCrossCheck.evaluate(10_890_158, OptionalDouble.of(10_088_806), List.of());

    private static Account account() {
        return new Account(10_890_158, 0.012, 1,
                List.of(new Account.PositionSnapshot("005930", 4, 75_000, 75_500)));
    }

    @Test
    @DisplayName("공개 4인자 생성자는 판정 불가 — 불일치가 아니다(전고점 인정 가능)")
    void default_is_unchecked() {
        Account a = account();

        assertThat(a.getEquityCheck().verdict()).isEqualTo(Verdict.UNCHECKED);
        assertThat(a.isEquityMismatch()).isFalse();
        assertThat(a.isFresh()).isTrue();
    }

    @Test
    @DisplayName("판정을 달면 불일치가 보이고, 나머지 값은 하나도 바뀌지 않는다")
    void with_equity_check_keeps_every_other_value() {
        Account a = account().withEquityCheck(MISMATCH);

        assertThat(a.isEquityMismatch()).isTrue();
        assertThat(a.getEquityCheck()).isSameAs(MISMATCH);
        assertThat(a.getTotalAssetValue()).isEqualTo(10_890_158);
        assertThat(a.getDailyPnlPercent()).isEqualTo(0.012);
        assertThat(a.getConsecutiveLossCount()).isEqualTo(1);
        assertThat(a.getPositions()).isEqualTo(account().getPositions());
        assertThat(a.isFresh()).isTrue();
    }

    @Test
    @DisplayName("asStale()은 대조 판정을 보존한다")
    void as_stale_preserves_the_check() {
        Account stale = account().withEquityCheck(MISMATCH).asStale();

        assertThat(stale.isFresh()).isFalse();
        assertThat(stale.isEquityMismatch()).isTrue();
    }

    @Test
    @DisplayName("반대 방향도 — 낡은 스냅샷에 판정을 달아도 낡음이 보존된다")
    void with_equity_check_preserves_staleness() {
        Account stale = account().asStale().withEquityCheck(MISMATCH);

        assertThat(stale.isFresh()).isFalse();
        assertThat(stale.isEquityMismatch()).isTrue();
    }

    @Test
    @DisplayName("판정 자리에 null을 주면 판정 불가로 받는다")
    void null_check_becomes_unchecked() {
        Account a = account().withEquityCheck(null);

        assertThat(a.getEquityCheck().verdict()).isEqualTo(Verdict.UNCHECKED);
        assertThat(a.isEquityMismatch()).isFalse();
    }
}
