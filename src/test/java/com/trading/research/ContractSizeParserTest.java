package com.trading.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContractSizeParser — 공급계약 원문에서 매출액 대비 % 추출")
class ContractSizeParserTest {

    @Test
    @DisplayName("KIND 표준 서식: '매출액대비(%)' 행 → 비율 추출")
    void parses_standard_kind_format() {
        String doc = "<TR><TD>계약금액(원)</TD><TD>12,345,678,900</TD></TR>"
                + "<TR><TD>최근 매출액(원)</TD><TD>98,765,432,100</TD></TR>"
                + "<TR><TD>매출액대비(%)</TD><TD>12.5</TD></TR>";

        assertThat(ContractSizeParser.parseSalesRatioPercent(doc)).hasValue(12.5);
    }

    @Test
    @DisplayName("공백·괄호 변형: '매출액 대비 (%)' / 천 단위 쉼표")
    void parses_spacing_variants() {
        assertThat(ContractSizeParser.parseSalesRatioPercent("매출액 대비 (%) : 1,234.56"))
                .hasValue(1234.56);
        assertThat(ContractSizeParser.parseSalesRatioPercent("최근매출액대비(%)</TD><TD>7.2"))
                .hasValue(7.2);
    }

    @Test
    @DisplayName("비상식 값(0 이하, 매출 100배 초과)·비율 부재 → empty")
    void rejects_insane_or_missing() {
        assertThat(ContractSizeParser.parseSalesRatioPercent("매출액대비(%) 0")).isEmpty();
        assertThat(ContractSizeParser.parseSalesRatioPercent("매출액대비(%) 99999")).isEmpty();
        assertThat(ContractSizeParser.parseSalesRatioPercent("계약금액만 있는 문서")).isEmpty();
        assertThat(ContractSizeParser.parseSalesRatioPercent(null)).isEmpty();
    }
}
