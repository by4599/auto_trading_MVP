package com.trading.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 숫자 파라미터 검증 — NaN은 범위 비교({@code v < min}, {@code v > max})가 둘 다 거짓이라 그대로 통과했다.
 * {@code risk.mddLimit}에 "NaN"이 저장되면 {@code drawdown > NaN}이 항상 거짓이 되어 매수 차단·강제청산이
 * 영구히 꺼진다(재시작해도 같은 검증으로 다시 적용된다 — 37_audit M-2).
 */
@DisplayName("ParamCatalog — 숫자가 아닌 값(NaN·무한대)은 거부, 범위 안 숫자는 허용")
class ParamCatalogTest {

    @ParameterizedTest(name = "risk.mddLimit = \"{0}\" 거부")
    @ValueSource(strings = {"NaN", "+NaN", "-NaN", "Infinity", "-Infinity"})
    void non_finite_numbers_are_rejected(String value) {
        assertThat(ParamCatalog.MDD_LIMIT.validate(value)).isNotNull();
    }

    @ParameterizedTest(name = "risk.mddLimit = \"{0}\" 허용")
    @ValueSource(strings = {"0.08", "0.1", "0.03", "0.30"})
    void finite_numbers_in_range_pass(String value) {
        assertThat(ParamCatalog.MDD_LIMIT.validate(value)).isNull();
    }
}
