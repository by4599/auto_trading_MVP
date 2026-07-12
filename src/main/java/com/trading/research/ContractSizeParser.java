package com.trading.research;

import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 단일판매ㆍ공급계약 공시 원문에서 "최근 매출액 대비 계약금액 %"를 추출한다.
 *
 * KIND 표준 서식은 "매출액대비(%)" 행에 비율을 명시한다. 서식 변형(공백·괄호·주석)에
 * 대비해 "매출액" 뒤 "대비"가 나오고 그 뒤 첫 숫자를 취하는 느슨한 패턴을 쓴다.
 * 파싱 실패는 empty — 크기 조건화 표본에서 제외될 뿐 다른 통계에는 영향 없다.
 */
public final class ContractSizeParser {

    /** "매출액 대비 ... 12.34" — 사이에 태그·공백·괄호 등 비숫자 최대 80자 허용 */
    private static final Pattern SALES_RATIO = Pattern.compile(
            "매\\s*출\\s*액\\s*[^0-9%]{0,10}대\\s*비[^0-9\\-]{0,80}([0-9][0-9,]*(?:\\.[0-9]+)?)");

    private static final double MAX_SANE_RATIO = 10_000; // 매출 100배 초과는 파싱 오류로 간주

    private ContractSizeParser() {}

    public static OptionalDouble parseSalesRatioPercent(String documentText) {
        if (documentText == null || documentText.isBlank()) return OptionalDouble.empty();
        Matcher m = SALES_RATIO.matcher(documentText);
        if (!m.find()) return OptionalDouble.empty();
        try {
            double ratio = Double.parseDouble(m.group(1).replace(",", ""));
            if (ratio <= 0 || ratio > MAX_SANE_RATIO) return OptionalDouble.empty();
            return OptionalDouble.of(ratio);
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }
}
