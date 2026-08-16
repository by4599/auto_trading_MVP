package com.trading.backtest;

import com.trading.backtest.RegimeLab.RegimeProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * regime-sens 민감도 프로필 리스트 단위 검증 (§14.4).
 *
 * <p>순수 단위 테스트 — 스프링 컨텍스트·Mockito 없이 정적 상수만 본다. 두 가지를 못박는다:
 * ① 새 REGIME_SENS_PROFILES가 정확히 [OFF, MA96, MA120, MA144]인지(민감도 = MA120 ±20%),
 * ② 공유 리팩터가 regime-lab의 REGIME_PROFILES를 [OFF, MA120, MA200]에서 바꾸지 않았는지(회귀).
 */
@DisplayName("RegimeLab — 레짐 프로필 리스트")
class RegimeLabProfilesTest {

    @Test
    @DisplayName("regime-sens 프로필은 [OFF, MA96, MA120, MA144] — MA120 ±20% 단일 파라미터 스윕")
    void regimeSensProfilesAreMa120PlusMinus20() {
        assertThat(RegimeLab.REGIME_SENS_PROFILES).hasSize(4);

        RegimeProfile s0 = RegimeLab.REGIME_SENS_PROFILES.get(0);
        RegimeProfile s1 = RegimeLab.REGIME_SENS_PROFILES.get(1);
        RegimeProfile s2 = RegimeLab.REGIME_SENS_PROFILES.get(2);
        RegimeProfile s3 = RegimeLab.REGIME_SENS_PROFILES.get(3);

        // S0: 회귀 앵커 = 필터 OFF (§14.4 G0와 동일 — maPeriod 200이지만 비활성)
        assertThat(s0.trendEnabled()).isFalse();
        assertThat(s0.maPeriod()).isEqualTo(200);

        // S1/S2/S3: 필터 ON, MA 기간만 96/120/144 (기준 120의 ±20%)
        assertThat(s1.trendEnabled()).isTrue();
        assertThat(s1.maPeriod()).isEqualTo(96);
        assertThat(s2.trendEnabled()).isTrue();
        assertThat(s2.maPeriod()).isEqualTo(120);
        assertThat(s3.trendEnabled()).isTrue();
        assertThat(s3.maPeriod()).isEqualTo(144);

        // 정확히 96 = 120 × 0.8, 144 = 120 × 1.2 임을 못박는다
        assertThat(s1.maPeriod()).isEqualTo((int) Math.round(120 * 0.8));
        assertThat(s3.maPeriod()).isEqualTo((int) Math.round(120 * 1.2));
    }

    @Test
    @DisplayName("regime-lab 프로필은 여전히 [OFF, MA120, MA200] — 공유 리팩터가 §14.4 앵커를 안 바꿨다")
    void regimeLabProfilesUnchanged() {
        assertThat(RegimeLab.REGIME_PROFILES).hasSize(3);

        RegimeProfile g0 = RegimeLab.REGIME_PROFILES.get(0);
        RegimeProfile g1 = RegimeLab.REGIME_PROFILES.get(1);
        RegimeProfile g2 = RegimeLab.REGIME_PROFILES.get(2);

        assertThat(g0.trendEnabled()).isFalse();
        assertThat(g0.maPeriod()).isEqualTo(200);
        assertThat(g1.trendEnabled()).isTrue();
        assertThat(g1.maPeriod()).isEqualTo(120);
        assertThat(g2.trendEnabled()).isTrue();
        assertThat(g2.maPeriod()).isEqualTo(200);
    }
}
