package com.trading.backtest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;

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
}
