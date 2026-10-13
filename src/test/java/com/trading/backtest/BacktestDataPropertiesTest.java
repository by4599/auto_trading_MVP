package com.trading.backtest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml의 backtest.event-themes(테마 → 종목 맵) 바인딩 검증.
 * 컨텍스트 테스트가 없어 YAML 구조 변경이 조용히 깨질 수 있으므로 실제 파일을 로드한다.
 */
class BacktestDataPropertiesTest {

    private BacktestDataProperties props;

    @BeforeEach
    void setUp() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        props = new Binder(new MapConfigurationPropertySource(properties))
                .bind("backtest", Bindable.ofInstance(new BacktestDataProperties()))
                .get();
    }

    @Test
    @DisplayName("event-themes 맵이 테마 키별로 바인딩된다 (반도체 파일럿 = 앵커 2 + 체인 19)")
    void eventThemes_bindsThemeMap() {
        assertThat(props.getEventThemes()).containsKeys(
                "semi-anchor", "semi-chain", "battery", "bio", "game", "robot", "large-benchmark");
        assertThat(props.getEventThemes().get("semi-anchor"))
                .containsExactly("005930", "000660");
        assertThat(props.getEventThemes().get("semi-chain")).hasSize(19)
                .contains("042700", "403870", "084370", "005290", "357780",
                        "089030", "074600", "067310", "348210", "281820");
    }

    @Test
    @DisplayName("getEventSymbols()는 테마 맵을 평면화한다 (중복 제거, 50종목)")
    void eventSymbols_flattensThemes() {
        List<String> flat = props.getEventSymbols();
        assertThat(flat).hasSize(50);
        assertThat(flat).doesNotHaveDuplicates();
        // 기존 표본 대표값 + 이번 보강분이 모두 포함되는지
        assertThat(flat).contains("247540", "402340", "036930", "042700", "281820");
    }

    @Test
    @DisplayName("kosdaq-symbols는 이벤트 표본의 부분집합이다 (벤치마크 분리 목록 정합성)")
    void kosdaqSymbols_areSubsetOfEventSample() {
        assertThat(props.getKosdaqSymbols()).hasSize(28);
        assertThat(props.getEventSymbols()).containsAll(props.getKosdaqSymbols());
    }

    // ── §14.1 후보 검증 재현성 고정 설정 (2026-07-23) ──

    @Test
    @DisplayName("candidate-symbols는 §14.1 기준선 표본 54종목이다 (첫 005930, 끝 267260)")
    void candidateSymbols_are54FixedUniverse() {
        BacktestDataProperties p = new BacktestDataProperties();
        assertThat(p.getCandidateSymbols()).hasSize(54);
        assertThat(p.getCandidateSymbols().get(0)).isEqualTo("005930");
        assertThat(p.getCandidateSymbols().get(53)).isEqualTo("267260");
        assertThat(p.getCandidateSymbols()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("candidate-from/to는 §14.1 고정 판정 창(2023-07-22 ~ 2026-07-21)이다")
    void candidatePeriod_isFixedWindow() {
        BacktestDataProperties p = new BacktestDataProperties();
        assertThat(p.getCandidateFrom()).isEqualTo(LocalDate.parse("2023-07-22"));
        assertThat(p.getCandidateTo()).isEqualTo(LocalDate.parse("2026-07-21"));
    }

    @Test
    @DisplayName("write-baseline 기본값은 false다 (대조/점검 실행이 기준선을 덮어쓰지 않게)")
    void writeBaseline_defaultsFalse() {
        assertThat(new BacktestDataProperties().isWriteBaseline()).isFalse();
    }

    @Test
    @DisplayName("전역 symbols(6종목)는 여전히 후보 유니버스와 별개다 (전역 불변 회귀)")
    void globalSymbols_remainSeparateFromCandidates() {
        BacktestDataProperties p = new BacktestDataProperties();
        assertThat(p.getSymbols()).hasSize(6);
        assertThat(p.getSymbols()).isNotEqualTo(p.getCandidateSymbols());
    }

    // ── 소급 데이터 확장 (2026-07-24) ──

    @Test
    @DisplayName("backfill-from 기본값은 null이다 (미설정 시 기존 rangeFrom 계산 유지)")
    void backfillFrom_defaultsNull() {
        assertThat(new BacktestDataProperties().getBackfillFrom()).isNull();
    }

    @Test
    @DisplayName("years 기본값 3은 불변이다 — 판정 창은 소급 저장과 무관 (전역 불변 회귀)")
    void years_remainsThree() {
        assertThat(new BacktestDataProperties().getYears()).isEqualTo(3);
    }

    @Test
    @DisplayName("crash-vol-from/to 기본값은 2020-01-01 ~ 2026-07-21이다 (진짜 하락장 포함)")
    void crashVolPeriod_defaultsToExtendedWindow() {
        BacktestDataProperties p = new BacktestDataProperties();
        assertThat(p.getCrashVolFrom()).isEqualTo(LocalDate.parse("2020-01-01"));
        assertThat(p.getCrashVolTo()).isEqualTo(LocalDate.parse("2026-07-21"));
    }

    @Test
    @DisplayName("crash-vol 창은 candidate 창과 독립이다 — 한쪽을 바꿔도 다른 쪽이 따라오지 않는다")
    void crashVolPeriod_isIndependentOfCandidatePeriod() {
        BacktestDataProperties p = new BacktestDataProperties();
        // 시작일은 서로 다르고(2020 vs 2023), 끝만 우연히 같은 날짜다
        assertThat(p.getCrashVolFrom()).isNotEqualTo(p.getCandidateFrom());
        assertThat(p.getCrashVolFrom()).isBefore(p.getCandidateFrom());

        p.setCrashVolFrom(LocalDate.parse("2018-01-01"));
        p.setCrashVolTo(LocalDate.parse("2019-12-31"));
        assertThat(p.getCandidateFrom()).isEqualTo(LocalDate.parse("2023-07-22"));
        assertThat(p.getCandidateTo()).isEqualTo(LocalDate.parse("2026-07-21"));

        p.setCandidateFrom(LocalDate.parse("2024-01-01"));
        assertThat(p.getCrashVolFrom()).isEqualTo(LocalDate.parse("2018-01-01"));
    }

    // ── regime-lab 약세장 판정 창 (2026-07-25) ──

    @Test
    @DisplayName("stress-from/to 기본값은 2020-01-01 ~ 2026-07-21이다 (§14.3과 같은 6.5년 창)")
    void stressPeriod_defaultsToBearMarketWindow() {
        BacktestDataProperties p = new BacktestDataProperties();
        assertThat(p.getStressFrom()).isEqualTo(LocalDate.parse("2020-01-01"));
        assertThat(p.getStressTo()).isEqualTo(LocalDate.parse("2026-07-21"));
    }

    @Test
    @DisplayName("stress 창은 candidate 창과 독립이다 — 한쪽을 바꿔도 다른 쪽이 따라오지 않는다")
    void stressPeriod_isIndependentOfCandidatePeriod() {
        BacktestDataProperties p = new BacktestDataProperties();
        assertThat(p.getStressFrom()).isNotEqualTo(p.getCandidateFrom());
        assertThat(p.getStressFrom()).isBefore(p.getCandidateFrom());

        p.setStressFrom(LocalDate.parse("2018-01-01"));
        p.setStressTo(LocalDate.parse("2019-12-31"));
        assertThat(p.getCandidateFrom()).isEqualTo(LocalDate.parse("2023-07-22"));
        assertThat(p.getCandidateTo()).isEqualTo(LocalDate.parse("2026-07-21"));

        p.setCandidateFrom(LocalDate.parse("2024-01-01"));
        assertThat(p.getStressFrom()).isEqualTo(LocalDate.parse("2018-01-01"));
        // crash-vol 창과도 별개 설정이다 (우연한 결합 금지)
        p.setCrashVolFrom(LocalDate.parse("2017-01-01"));
        assertThat(p.getStressFrom()).isEqualTo(LocalDate.parse("2018-01-01"));
    }

    @Test
    @DisplayName("application-backtest.yml이 stress 창을 바인딩한다 (재현성 — 부동 날짜 금지)")
    void backtestYaml_bindsStressWindow() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-backtest.yml"));
        BacktestDataProperties p = new Binder(new MapConfigurationPropertySource(yaml.getObject()))
                .bind("backtest", Bindable.ofInstance(new BacktestDataProperties())).get();

        assertThat(p.getStressFrom()).isEqualTo(LocalDate.parse("2020-01-01"));
        assertThat(p.getStressTo()).isEqualTo(LocalDate.parse("2026-07-21"));
        // §14.1 재현 기준선 창은 그대로
        assertThat(p.getCandidateFrom()).isEqualTo(LocalDate.parse("2023-07-22"));
        assertThat(p.getCandidateTo()).isEqualTo(LocalDate.parse("2026-07-21"));
    }

    // ── 커버리지 하한 (2026-08-17, 백필 슬랙 결함) ──

    @Test
    @DisplayName("requiredCoverageThrough는 세 판정 창 끝 중 가장 늦은 날이다")
    void requiredCoverageThrough_returnsLatestWindowEnd() {
        BacktestDataProperties p = new BacktestDataProperties();
        // 기본값은 셋 다 같은 날 — 그대로 그 날이 하한
        assertThat(p.requiredCoverageThrough()).isEqualTo(LocalDate.parse("2026-07-21"));

        p.setStressTo(LocalDate.parse("2026-08-10"));
        assertThat(p.requiredCoverageThrough()).isEqualTo(LocalDate.parse("2026-08-10"));

        p.setCrashVolTo(LocalDate.parse("2026-09-01"));
        assertThat(p.requiredCoverageThrough()).isEqualTo(LocalDate.parse("2026-09-01"));
    }

    @Test
    @DisplayName("커버리지 하한을 계산해도 세 창의 판정 설정은 서로 영향받지 않는다 (독립성 회귀)")
    void requiredCoverageThrough_doesNotCoupleWindows() {
        BacktestDataProperties p = new BacktestDataProperties();
        p.setStressTo(LocalDate.parse("2026-08-10"));

        assertThat(p.requiredCoverageThrough()).isEqualTo(LocalDate.parse("2026-08-10"));
        assertThat(p.getCandidateTo()).isEqualTo(LocalDate.parse("2026-07-21"));
        assertThat(p.getCrashVolTo()).isEqualTo(LocalDate.parse("2026-07-21"));
    }

    @Test
    @DisplayName("application-backtest.yml이 backfill-from·crash-vol 창을 바인딩한다")
    void backtestYaml_bindsBackfillAndCrashVolWindow() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-backtest.yml"));
        BacktestDataProperties p = new Binder(new MapConfigurationPropertySource(yaml.getObject()))
                .bind("backtest", Bindable.ofInstance(new BacktestDataProperties())).get();

        assertThat(p.getBackfillFrom()).isEqualTo(LocalDate.parse("2019-04-01"));
        assertThat(p.getCrashVolFrom()).isEqualTo(LocalDate.parse("2020-01-01"));
        assertThat(p.getCrashVolTo()).isEqualTo(LocalDate.parse("2026-07-21"));
        // §14.1 재현 기준선은 그대로 — cost-lab·risk-lab 판정 창 불변
        assertThat(p.getCandidateFrom()).isEqualTo(LocalDate.parse("2023-07-22"));
        assertThat(p.getCandidateTo()).isEqualTo(LocalDate.parse("2026-07-21"));
        assertThat(p.getYears()).isEqualTo(3);
    }
}
