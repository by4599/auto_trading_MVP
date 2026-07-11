package com.trading.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DisclosureEventClassifier — 공시 보고서명 → 이벤트 택소노미")
class DisclosureEventClassifierTest {

    private final DisclosureEventClassifier sut =
            new DisclosureEventClassifier(new NewsSentimentAnalyzer());

    @Test
    @DisplayName("공급계약/수주 → SUPPLY_CONTRACT(호재)")
    void supply_contract() {
        assertThat(sut.classify("단일판매ㆍ공급계약체결").type()).isEqualTo("SUPPLY_CONTRACT");
        assertThat(sut.classify("단일판매ㆍ공급계약체결").sentiment()).isEqualTo("POSITIVE");
        assertThat(sut.classify("대규모 수주 공시").type()).isEqualTo("SUPPLY_CONTRACT");
    }

    @Test
    @DisplayName("증자: 무상=호재 / 유상=악재")
    void capital_increase_direction() {
        assertThat(sut.classify("무상증자결정").type()).isEqualTo("FREE_INCREASE");
        assertThat(sut.classify("무상증자결정").sentiment()).isEqualTo("POSITIVE");
        assertThat(sut.classify("유상증자결정").type()).isEqualTo("PAID_INCREASE");
        assertThat(sut.classify("유상증자결정").sentiment()).isEqualTo("NEGATIVE");
    }

    @Test
    @DisplayName("자사주: 취득=호재 / 처분=악재 (처분 룰이 취득보다 먼저 매칭)")
    void treasury_stock_direction() {
        assertThat(sut.classify("주요사항보고서(자기주식취득결정)").type()).isEqualTo("TREASURY_BUY");
        assertThat(sut.classify("주요사항보고서(자기주식처분결정)").type()).isEqualTo("TREASURY_SELL");
        assertThat(sut.classify("주요사항보고서(자기주식처분결정)").sentiment()).isEqualTo("NEGATIVE");
    }

    @Test
    @DisplayName("전환사채/신주인수권부사채 → CONVERTIBLE(악재)")
    void convertible_bonds() {
        assertThat(sut.classify("전환사채권발행결정").type()).isEqualTo("CONVERTIBLE");
        assertThat(sut.classify("신주인수권부사채권발행결정").type()).isEqualTo("CONVERTIBLE");
    }

    @Test
    @DisplayName("실적 공시 → EARNINGS(중립 — 방향은 통계가 결정)")
    void earnings_neutral() {
        assertThat(sut.classify("연결재무제표기준영업(잠정)실적(공정공시)").type()).isEqualTo("EARNINGS");
        assertThat(sut.classify("연결재무제표기준영업(잠정)실적(공정공시)").sentiment()).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("미분류 보고서 → OTHER + 뉴스 감성분석 폴백")
    void fallback_to_other() {
        assertThat(sut.classify("주주총회소집결의").type()).isEqualTo("OTHER");
        assertThat(sut.classify(null).type()).isEqualTo("OTHER");
        assertThat(sut.classify(null).sentiment()).isEqualTo("NEUTRAL");
    }
}
