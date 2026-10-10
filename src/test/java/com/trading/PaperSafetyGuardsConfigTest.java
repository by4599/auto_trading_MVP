package com.trading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배포될 {@code application-paper.yml}의 안전장치 스위치를 고정한다 — 키 오타는 예외 없이 기본값(꺼짐)으로
 * 조용히 떨어지므로 설정 파일 그 자체를 검사한다.
 */
@DisplayName("paper 설정 — 안전장치 스위치")
class PaperSafetyGuardsConfigTest {

    private static Binder paperBinder() {
        return new Binder(ConfigurationPropertySources.from(PaperProfileYaml.sources()));
    }

    @Test
    @DisplayName("낡은 잔고 매수 차단이 켜져 있다 (28_audit M-3, 2026-10-10 사용자 결정)")
    void stale_account_buy_guard_is_on() {
        boolean enabled = paperBinder()
                .bind("trading.risk.stale-account-buy-guard", Boolean.class)
                .orElse(false);

        assertThat(enabled).isTrue();
    }
}
