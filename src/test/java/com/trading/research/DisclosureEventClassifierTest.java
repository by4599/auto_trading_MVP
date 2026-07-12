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
    @DisplayName("정례·행정성 공시 → 노이즈 유형으로 분리 (OTHER 오염 방지)")
    void routine_filings_classified() {
        assertThat(sut.classify("임원ㆍ주요주주특정증권등소유상황보고서").type())
                .isEqualTo("INSIDER_OWNERSHIP");
        assertThat(sut.classify("주식등의대량보유상황보고서(일반)").type()).isEqualTo("LARGE_HOLDING");
        assertThat(sut.classify("기업설명회(IR)개최(안내공시)").type()).isEqualTo("IR_EVENT");
        assertThat(sut.classify("풍문또는보도에대한해명(미확정)").type()).isEqualTo("RUMOR_CLARIFY");
        assertThat(sut.classify("주주총회소집결의").type()).isEqualTo("REGULAR_FILING");
        assertThat(sut.classify("분기보고서 (2026.03)").type()).isEqualTo("REGULAR_FILING");
    }

    @Test
    @DisplayName("정례 공시 룰이 먼저 — '특수관계인…매수'가 '수주'(공급계약)로 오분류되지 않는다")
    void routine_rules_precede_substring_hazards() {
        assertThat(sut.classify("특수관계인으로부터기타주식매수").type()).isEqualTo("RELATED_PARTY");
    }

    @Test
    @DisplayName("미분류 보고서 → OTHER + 뉴스 감성분석 폴백")
    void fallback_to_other() {
        assertThat(sut.classify("타법인주식및출자증권취득자금사용내역").type()).isEqualTo("OTHER");
        assertThat(sut.classify(null).type()).isEqualTo("OTHER");
        assertThat(sut.classify(null).sentiment()).isEqualTo("NEUTRAL");
    }
}
