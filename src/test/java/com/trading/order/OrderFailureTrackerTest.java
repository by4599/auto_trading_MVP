package com.trading.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 접수 실패 후 재시도 차단 — 실패가 장부에 안 남아 같은 매수를 반복하던 문제
 * (2026-08-04: 034020이 11분간 7회)의 재발 방지.
 */
@DisplayName("OrderFailureTracker — 실패 종목 쿨다운")
class OrderFailureTrackerTest {

    private Instant now = Instant.parse("2026-08-04T01:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    private OrderFailureTracker sut;

    @BeforeEach
    void setUp() {
        sut = new OrderFailureTracker(clock, 60, 600);
    }

    @Test
    @DisplayName("거부된 주문은 쿨다운 동안 차단되고, 지나면 풀린다")
    void rejected_blocks_until_cooldown_elapses() {
        sut.recordRejected("034020");
        assertThat(sut.isCoolingDown("034020")).isTrue();

        now = now.plusSeconds(59);
        assertThat(sut.isCoolingDown("034020")).isTrue();

        now = now.plusSeconds(2); // 총 61초
        assertThat(sut.isCoolingDown("034020")).isFalse();
    }

    @Test
    @DisplayName("응답 불명은 거부보다 오래 막는다 — 체결됐을 수 있어 재동기화 시간을 벌어야 하므로")
    void ambiguous_blocks_longer_than_rejected() {
        sut.recordAmbiguous("034020");

        now = now.plusSeconds(61); // 거부였다면 이미 풀렸을 시점
        assertThat(sut.isCoolingDown("034020")).isTrue();

        now = now.plusSeconds(540); // 총 601초
        assertThat(sut.isCoolingDown("034020")).isFalse();
    }

    @Test
    @DisplayName("차단은 종목별이다 — 한 종목 실패가 다른 종목을 막지 않는다")
    void cooldown_is_per_symbol() {
        sut.recordRejected("034020");

        assertThat(sut.isCoolingDown("034020")).isTrue();
        assertThat(sut.isCoolingDown("005930")).isFalse();
    }

    @Test
    @DisplayName("짧은 차단 중 응답 불명이 겹치면 더 긴 쪽으로 연장된다")
    void later_ambiguous_extends_existing_block() {
        sut.recordRejected("034020");
        sut.recordAmbiguous("034020");

        now = now.plusSeconds(61);
        assertThat(sut.isCoolingDown("034020")).isTrue();
    }

    @Test
    @DisplayName("체결이 확인되면 남은 차단을 푼다")
    void clear_releases_block() {
        sut.recordAmbiguous("034020");
        sut.clear("034020");

        assertThat(sut.isCoolingDown("034020")).isFalse();
    }
}
