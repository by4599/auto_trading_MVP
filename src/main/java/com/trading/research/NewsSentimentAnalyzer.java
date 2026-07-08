package com.trading.research;

import org.springframework.stereotype.Component;
import java.util.List;

/**
 * 뉴스 제목 키워드 기반 호재/악재 분류.
 * LLM 연동 없이 한국어 금융 키워드만으로 1차 분류한다.
 * 정확도는 70-80% 수준 — 최종 판단은 사람이 한다.
 */
@Component
public class NewsSentimentAnalyzer {

    private static final List<String> POSITIVE = List.of(
            "급등", "신고가", "호실적", "수주", "계약", "흑자", "상승",
            "매수", "기대", "성장", "회복", "반등", "강세", "확대", "호재",
            "배당", "자사주", "증가", "상향", "승인", "돌파", "최대"
    );

    private static final List<String> NEGATIVE = List.of(
            "급락", "손실", "적자", "소송", "리콜", "하락", "매도",
            "우려", "하향", "제재", "취소", "부진", "악재", "감소",
            "위기", "경고", "충당금", "구조조정", "파산", "조사", "최저"
    );

    /** @return "POSITIVE" | "NEGATIVE" | "NEUTRAL" */
    public String analyze(String title) {
        if (title == null || title.isBlank()) return "NEUTRAL";
        long posScore = POSITIVE.stream().filter(title::contains).count();
        long negScore = NEGATIVE.stream().filter(title::contains).count();
        if (posScore > negScore) return "POSITIVE";
        if (negScore > posScore) return "NEGATIVE";
        return "NEUTRAL";
    }
}
