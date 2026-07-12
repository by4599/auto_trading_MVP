package com.trading.research;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 공시 보고서명 → 이벤트 택소노미 분류 (방법론 §1, 설계 S1).
 *
 * 공시는 report_nm에 유형이 명시되어 LLM 없이 키워드 룰만으로 분류 가능하다.
 * 여기서의 sentiment(방향)는 통설 기반 잠정값일 뿐이며, 실제 채택 여부는
 * 유형별 과거 반응 통계(EventStatsBacktester, B-4)가 결정한다 — 원칙 ②.
 */
@Component
public class DisclosureEventClassifier {

    /** 키워드 → (유형, 잠정 방향). 순서 의미 있음 — 먼저 맞은 것이 우선.
     *  정례·행정성 공시를 먼저 걸러야 "매수주식"의 '수주' 같은 부분 문자열 오분류를 막는다. */
    private static final List<Map.Entry<String, EventClass>> RULES = List.of(
            // ── 정례·행정성 공시 (이벤트성 낮음 — 통계 노이즈 분리용) ──
            Map.entry("소유상황보고서",     new EventClass("INSIDER_OWNERSHIP", "NEUTRAL")),
            Map.entry("대량보유상황보고서", new EventClass("LARGE_HOLDING",     "NEUTRAL")),
            Map.entry("기업설명회",         new EventClass("IR_EVENT",          "NEUTRAL")),
            Map.entry("풍문",               new EventClass("RUMOR_CLARIFY",     "NEUTRAL")),
            Map.entry("해명",               new EventClass("RUMOR_CLARIFY",     "NEUTRAL")),
            Map.entry("특수관계인",         new EventClass("RELATED_PARTY",     "NEUTRAL")),
            Map.entry("계열회사",           new EventClass("RELATED_PARTY",     "NEUTRAL")),
            Map.entry("사업보고서",         new EventClass("REGULAR_FILING",    "NEUTRAL")),
            Map.entry("반기보고서",         new EventClass("REGULAR_FILING",    "NEUTRAL")),
            Map.entry("분기보고서",         new EventClass("REGULAR_FILING",    "NEUTRAL")),
            Map.entry("감사보고서",         new EventClass("REGULAR_FILING",    "NEUTRAL")),
            Map.entry("대규모기업집단",     new EventClass("REGULAR_FILING",    "NEUTRAL")),
            Map.entry("증권발행실적",       new EventClass("REGULAR_FILING",    "NEUTRAL")),
            Map.entry("일괄신고",           new EventClass("REGULAR_FILING",    "NEUTRAL")),
            Map.entry("주주총회",           new EventClass("REGULAR_FILING",    "NEUTRAL")),
            Map.entry("실적공시예고",       new EventClass("GUIDANCE",          "NEUTRAL")),
            Map.entry("영업실적등에대한전망", new EventClass("GUIDANCE",        "NEUTRAL")),
            // ── 이벤트성 공시 (방법론 §1 택소노미) ──
            Map.entry("단일판매ㆍ공급계약", new EventClass("SUPPLY_CONTRACT", "POSITIVE")),
            Map.entry("공급계약",           new EventClass("SUPPLY_CONTRACT", "POSITIVE")),
            Map.entry("수주",               new EventClass("SUPPLY_CONTRACT", "POSITIVE")),
            Map.entry("무상증자",           new EventClass("FREE_INCREASE",   "POSITIVE")),
            Map.entry("자기주식처분",       new EventClass("TREASURY_SELL",   "NEGATIVE")),
            Map.entry("자기주식 처분",      new EventClass("TREASURY_SELL",   "NEGATIVE")),
            Map.entry("자기주식취득",       new EventClass("TREASURY_BUY",    "POSITIVE")),
            Map.entry("자기주식 취득",      new EventClass("TREASURY_BUY",    "POSITIVE")),
            Map.entry("자사주",             new EventClass("TREASURY_BUY",    "POSITIVE")),
            Map.entry("현금ㆍ현물배당",     new EventClass("DIVIDEND",        "POSITIVE")),
            Map.entry("배당",               new EventClass("DIVIDEND",        "POSITIVE")),
            Map.entry("유상증자",           new EventClass("PAID_INCREASE",   "NEGATIVE")),
            Map.entry("전환사채",           new EventClass("CONVERTIBLE",     "NEGATIVE")),
            Map.entry("신주인수권부사채",   new EventClass("CONVERTIBLE",     "NEGATIVE")),
            Map.entry("교환사채",           new EventClass("CONVERTIBLE",     "NEGATIVE")),
            Map.entry("소송",               new EventClass("LAWSUIT",         "NEGATIVE")),
            Map.entry("거래정지",           new EventClass("HALT_PENALTY",    "NEGATIVE")),
            Map.entry("불성실공시",         new EventClass("HALT_PENALTY",    "NEGATIVE")),
            Map.entry("관리종목",           new EventClass("HALT_PENALTY",    "NEGATIVE")),
            Map.entry("영업(잠정)실적",     new EventClass("EARNINGS",        "NEUTRAL")),
            Map.entry("잠정실적",           new EventClass("EARNINGS",        "NEUTRAL")),
            Map.entry("실적공시",           new EventClass("EARNINGS",        "NEUTRAL")),
            Map.entry("합병",               new EventClass("MNA",             "NEUTRAL")),
            Map.entry("분할",               new EventClass("MNA",             "NEUTRAL")),
            Map.entry("영업양수",           new EventClass("MNA",             "NEUTRAL")),
            Map.entry("영업양도",           new EventClass("MNA",             "NEUTRAL")),
            Map.entry("주식교환",           new EventClass("MNA",             "NEUTRAL")),
            Map.entry("최대주주",           new EventClass("GOVERNANCE",      "NEUTRAL")),
            Map.entry("대표이사",           new EventClass("GOVERNANCE",      "NEUTRAL"))
    );

    private final NewsSentimentAnalyzer sentimentAnalyzer;

    public DisclosureEventClassifier(NewsSentimentAnalyzer sentimentAnalyzer) {
        this.sentimentAnalyzer = sentimentAnalyzer;
    }

    public EventClass classify(String reportName) {
        if (reportName == null || reportName.isBlank()) {
            return new EventClass("OTHER", "NEUTRAL");
        }
        for (Map.Entry<String, EventClass> rule : RULES) {
            if (reportName.contains(rule.getKey())) return rule.getValue();
        }
        return new EventClass("OTHER", sentimentAnalyzer.analyze(reportName));
    }

    public record EventClass(String type, String sentiment) {}
}
