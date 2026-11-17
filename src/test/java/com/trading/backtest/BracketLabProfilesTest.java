package com.trading.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * bracket-lab 스윕 격자와 <b>기존 출구 프로필 불변</b> 고정 (§17).
 *
 * <p>순수 단위 테스트 — 스프링·Mockito 없이 상수만 본다. 두 가지를 못 박는다:
 * ① 9조합이 사전 동결한 3×3(손절 3·5·7% × 익절 10·15·20%)이고 사용자 원안(5%/15%)이 정확히
 * 한 칸이라는 것, ② 새 필드(stopPct·targetPct)가 기존 프로필(P0~P4·P3·B동현행)에 0으로만
 * 붙어 <b>코드 경로가 바뀌지 않는다</b>는 것.
 */
@DisplayName("BracketLab — 고정% 브래킷 프로필 격자")
class BracketLabProfilesTest {

    @Test
    @DisplayName("프로필은 기준선 1개 + 브래킷 9개 — 손절 3×익절 3 격자, 순서 고정")
    void profilesAreBaselinePlusNineBracketCells() {
        var profiles = BracketLab.profiles();

        assertThat(profiles).hasSize(10);
        assertThat(profiles.get(0)).isEqualTo(BracketLab.BASELINE);
        assertThat(profiles.get(0).stopPct()).isZero();
        assertThat(profiles.get(0).targetPct()).isZero();
        assertThat(profiles.get(0).trailEnabled()).isTrue();

        var cells = profiles.subList(1, 10);
        assertThat(cells).allSatisfy(p -> {
            assertThat(p.trailEnabled()).as("브래킷은 트레일링 없음").isFalse();
            assertThat(p.timecut()).as("당일 청산 아님").isFalse();
            assertThat(p.maxHoldDays()).as("기준선과 같은 최대보유 20일").isEqualTo(20);
            assertThat(p.atrMult()).as("사이징은 기준선과 동일(ATR1.0)").isEqualTo(1.0);
        });
        assertThat(cells).extracting(ExitProfile::stopPct)
                .containsExactly(0.03, 0.03, 0.03, 0.05, 0.05, 0.05, 0.07, 0.07, 0.07);
        assertThat(cells).extracting(ExitProfile::targetPct)
                .containsExactly(0.10, 0.15, 0.20, 0.10, 0.15, 0.20, 0.10, 0.15, 0.20);
    }

    @Test
    @DisplayName("사용자 원안(-5%/+15%)은 정확히 한 칸이고 이름에 표시된다")
    void userProposalCellIsMarked() {
        var marked = BracketLab.profiles().stream()
                .filter(p -> p.name().contains("사용자 원안")).toList();

        assertThat(marked).hasSize(1);
        assertThat(marked.get(0).stopPct()).isEqualTo(BracketLab.USER_STOP_PCT);
        assertThat(marked.get(0).targetPct()).isEqualTo(BracketLab.USER_TARGET_PCT);
    }

    @Test
    @DisplayName("기존 프로필은 stopPct·targetPct가 0 — 새 필드가 붙어도 동작이 바뀌지 않는다")
    void existingProfilesKeepZeroBracket() {
        assertThat(ExitProfile.P3.stopPct()).isZero();
        assertThat(ExitProfile.P3.targetPct()).isZero();
        assertThat(ExitProfile.P3.atrMult()).isEqualTo(1.0);
        assertThat(ExitProfile.P3.maxHoldDays()).isEqualTo(20);
        assertThat(ExitProfile.P3.trailArmPct()).isEqualTo(0.01);
        assertThat(ExitProfile.P3.trailPct()).isEqualTo(0.03);

        assertThat(ExitProfile.B_DONG_CURRENT.stopPct()).isZero();
        assertThat(ExitProfile.B_DONG_CURRENT.targetPct()).isZero();
        assertThat(ExitProfile.B_DONG_CURRENT.atrMult()).isEqualTo(1.5);
        assertThat(ExitProfile.B_DONG_CURRENT.timecut()).isTrue();

        assertThat(ExitLab.EXIT_PROFILES).hasSize(5);
        assertThat(ExitLab.EXIT_PROFILES).allSatisfy(p -> {
            assertThat(p.stopPct()).isZero();
            assertThat(p.targetPct()).isZero();
        });
    }

    @Test
    @DisplayName("ExitLabProperties 기본값·복원값은 브래킷 OFF")
    void propertiesDefaultToBracketOff() {
        ExitLabProperties props = new ExitLabProperties();
        assertThat(props.isBracketStop()).isFalse();
        assertThat(props.getTargetPct()).isZero();

        props.setStopPct(0.05);
        props.setTargetPct(0.15);
        props.resetDefaults();

        assertThat(props.isBracketStop()).isFalse();
        assertThat(props.getStopPct()).isZero();
        assertThat(props.getTargetPct()).isZero();
    }
}
