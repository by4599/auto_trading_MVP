package com.trading.position;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Position의 "진입 이후 최고가" — 라운드트립(진입~전량 청산)에 묶인다.
 * 끝나면 비워서 다음 진입이 옛 고점을 물려받지 않게 한다(entryDate·realizedPnlAccum과 같은 수명).
 */
@DisplayName("Position.trailingHigh — 진입 이후 최고가의 수명")
class PositionTrailingHighTest {

    private static Position held() {
        Position pos = Position.empty("005930");
        pos.applyBuy(10, 100.0);
        return pos;
    }

    @Test
    @DisplayName("오를 때만 올라가고, 내려가거나 0 이하 가격은 무시한다")
    void raises_only_upward() {
        Position pos = held();

        assertThat(pos.raiseTrailingHigh(105.0)).isTrue();
        assertThat(pos.raiseTrailingHigh(104.0)).isFalse();
        assertThat(pos.raiseTrailingHigh(105.0)).isFalse();
        assertThat(pos.raiseTrailingHigh(0.0)).isFalse();
        assertThat(pos.getTrailingHigh()).isEqualTo(105.0);
    }

    @Test
    @DisplayName("전량 매도로 라운드트립이 끝나면 비운다")
    void cleared_when_round_trip_ends() {
        Position pos = held();
        pos.raiseTrailingHigh(120.0);

        pos.applySell(4);
        assertThat(pos.getTrailingHigh()).isEqualTo(120.0);   // 부분 매도는 같은 라운드트립

        pos.applySell(6);
        assertThat(pos.getTrailingHigh()).isNull();
    }

    @Test
    @DisplayName("수량 0에서 새로 사면 새 라운드트립 — 남아 있던 값도 비운다")
    void cleared_on_new_round_trip() {
        Position pos = Position.empty("005930");
        pos.reconcileTo(0, 0.0);
        pos.raiseTrailingHigh(150.0);   // 수량 0인데 값이 남은 비정상 행을 가정

        pos.applyBuy(5, 100.0);

        assertThat(pos.getTrailingHigh()).isNull();
    }
}
