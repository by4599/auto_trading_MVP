package com.trading.settings;

import com.trading.risk.RiskLimits;
import com.trading.risk.RiskLimitsProperties;
import com.trading.strategy.FilterProperties;
import com.trading.strategy.StrategyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 리포지토리(인터페이스)만 목킹, 파라미터 홀더 빈은 실객체 —
 * Java 25 인라인 Mockito의 구체 클래스 목킹 제약 대응 (CLAUDE.md).
 */
class TradingParamServiceTest {

    private AppSettingRepository repository;
    private RiskLimitsProperties riskLimits;
    private StrategyParameters strategyParameters;
    private FilterProperties filterProperties;
    private TradingParamService sut;

    @BeforeEach
    void setUp() {
        repository = mock(AppSettingRepository.class);
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        riskLimits = new RiskLimitsProperties();
        strategyParameters = new StrategyParameters();
        filterProperties = new FilterProperties();
        sut = new TradingParamService(repository, riskLimits, strategyParameters, filterProperties);
    }

    @Test
    void valid_value_is_saved_and_applied_immediately() {
        var result = sut.update(Map.of("risk.mddLimit", "0.12"));

        assertThat(result.saved()).containsExactly("risk.mddLimit");
        assertThat(result.errors()).isEmpty();
        assertThat(riskLimits.getMddLimit()).isEqualTo(0.12);
        verify(repository).save(any(AppSetting.class));
    }

    @Test
    void out_of_range_value_is_rejected_not_clamped() {
        var result = sut.update(Map.of("risk.mddLimit", "0.50")); // max 0.30

        assertThat(result.saved()).isEmpty();
        assertThat(result.errors()).containsKey("risk.mddLimit");
        assertThat(riskLimits.getMddLimit()).isEqualTo(RiskLimits.MDD_LIMIT); // 미반영
        verify(repository, never()).save(any(AppSetting.class));
    }

    @Test
    void unknown_key_is_rejected() {
        var result = sut.update(Map.of("risk.unknown", "1"));

        assertThat(result.errors()).containsKey("risk.unknown");
    }

    @Test
    void cross_validation_rejects_liquidate_above_block() {
        // 개별 범위는 통과하지만 청산(-2%)이 차단(-3%)보다 높은 모순 조합
        var result = sut.update(Map.of("risk.dailyLossLiquidate", "-0.02"));

        assertThat(result.saved()).isEmpty();
        assertThat(result.errors()).containsKey("risk.dailyLossLiquidate");
        assertThat(riskLimits.getDailyLossLiquidate()).isEqualTo(RiskLimits.DAILY_LOSS_LIQUIDATE);
    }

    @Test
    void cross_validation_rejects_trail_above_arm() {
        var result = sut.update(Map.of("filters.trailingStop.trailPct", "0.04")); // arm 기본 0.03

        assertThat(result.errors()).containsKey("filters.trailingStop.trailPct");
    }

    @Test
    void reset_deletes_all_and_restores_code_defaults() {
        riskLimits.setMddLimit(0.20);
        strategyParameters.setK(0.6);
        filterProperties.getTrailingStop().setEnabled(true);

        sut.resetAll();

        verify(repository).deleteAll();
        assertThat(riskLimits.getMddLimit()).isEqualTo(RiskLimits.MDD_LIMIT);
        assertThat(strategyParameters.getK()).isEqualTo(0.5);
        assertThat(filterProperties.getTrailingStop().isEnabled()).isFalse();
    }

    @Test
    void startup_load_applies_stored_values_and_skips_invalid() {
        when(repository.findAll()).thenReturn(List.of(
                AppSetting.of("strategy.k", "0.6"),
                AppSetting.of("risk.mddLimit", "9.99"),   // 범위 밖 — 스킵
                AppSetting.of("ghost.key", "1")));         // 알 수 없는 키 — 스킵

        sut.loadOnStartup();

        assertThat(strategyParameters.getK()).isEqualTo(0.6);
        assertThat(riskLimits.getMddLimit()).isEqualTo(RiskLimits.MDD_LIMIT);
    }

    @Test
    void bool_and_int_and_time_params_are_validated_by_type() {
        var ok = sut.update(Map.of(
                "filters.disclosureCooldown.enabled", "true",
                "filters.disclosureCooldown.cooldownDays", "7",
                "filters.entryWindow.notBefore", "09:30"));
        assertThat(ok.errors()).isEmpty();
        assertThat(filterProperties.getDisclosureCooldown().isEnabled()).isTrue();
        assertThat(filterProperties.getDisclosureCooldown().getCooldownDays()).isEqualTo(7);
        assertThat(filterProperties.getEntryWindow().getNotBefore().toString()).isEqualTo("09:30");

        var bad = sut.update(Map.of(
                "filters.disclosureCooldown.enabled", "maybe",
                "filters.entryWindow.notBefore", "11:00")); // max 10:00
        assertThat(bad.errors()).hasSize(2);
    }

    @Test
    void get_all_exposes_value_default_and_range() {
        riskLimits.setMddLimit(0.15);

        List<Map<String, Object>> groups = sut.getAll();

        Map<String, Object> mdd = groups.stream()
                .filter(g -> "리스크 한도".equals(g.get("name")))
                .flatMap(g -> ((List<Map<String, Object>>) g.get("params")).stream())
                .filter(p -> "risk.mddLimit".equals(p.get("key")))
                .findFirst().orElseThrow();

        assertThat(mdd.get("value")).isEqualTo("0.15");
        assertThat(mdd.get("defaultValue")).isEqualTo("0.1");
        assertThat(mdd.get("min")).isEqualTo("0.03");
        assertThat(mdd.get("max")).isEqualTo("0.30");
    }
}
