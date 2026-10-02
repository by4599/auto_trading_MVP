package com.trading.risk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 거부 사유 문자열에서 "어느 룰이 막았나"를 되짚는다 (2026-09-22 신설).
 *
 * <p><b>왜 이렇게 하나 (한계를 먼저 밝힌다).</b> {@link RiskEngine}은 거부한 룰의 이름을
 * 돌려주지 않고 {@link RiskResult}에는 사유 문자열 하나뿐이다. 이름을 제대로 얻으려면
 * {@code RiskEngine}이나 14개 룰 전부를 고쳐야 하는데, 둘 다 매매·안전 경로라
 * <b>건드리지 않는 쪽을 택했다</b>(CLAUDE.md 아키텍처 규칙 3).
 *
 * <p>그래서 이 표는 <b>룰 메시지에 의존한다</b> — 룰이 메시지를 바꾸면 조용히
 * {@link #UNKNOWN}으로 떨어진다. 그래도 사고가 되진 않는다: 이름은 집계용 라벨일 뿐이고
 * <b>사유 원문은 그대로 저장</b>되므로 화면에서 무엇이 막았는지는 계속 읽을 수 있다.
 * 표가 맞는지는 {@code RiskRuleNameResolverTest}가 고정한다.
 */
final class RiskRuleNameResolver {

    static final String UNKNOWN = "UNKNOWN";

    /**
     * 사유에 들어 있는 고유 조각 → 룰 이름. 위에서부터 차례로 검사하므로
     * <b>더 구체적인 조각을 먼저</b> 둔다 ("신규 매수 금지"는 여러 룰이 공유한다).
     */
    private static final Map<String, String> BY_FRAGMENT = new LinkedHashMap<>();

    static {
        BY_FRAGMENT.put("이미 보유 중인 종목",       "PendingOrderRule");
        BY_FRAGMENT.put("미체결 매수 주문 대기 중",  "PendingOrderRule");
        BY_FRAGMENT.put("종목 비중 한도 초과",       "PositionLimitRule");
        BY_FRAGMENT.put("최대 보유 종목 수 초과",    "MaxPositionCountRule");
        BY_FRAGMENT.put("장 마감 임박",              "MarketCloseRule");
        BY_FRAGMENT.put("일일 손실 한도",            "DailyLossRule");
        BY_FRAGMENT.put("전고점 대비 MDD",           "GlobalEquityStopRule");
        BY_FRAGMENT.put("연속 손실",                 "ConsecutiveLossRule");
        BY_FRAGMENT.put("칸 비활성",                 "BucketBudgetRule");
        BY_FRAGMENT.put("칸 예산 소진",              "BucketBudgetRule");
        BY_FRAGMENT.put("타임컷 이후 신규 매수 금지", "PostTimeCutBuyRule");
        BY_FRAGMENT.put("공시 쿨다운",               "DisclosureCooldownRule");
        BY_FRAGMENT.put("진입 시간창 필터",          "EntryTimeWindowRule");
        BY_FRAGMENT.put("지수 추세 판정 불가",       "IndexTrendDataGateRule");
        BY_FRAGMENT.put("지수 추세 필터",            "IndexTrendRule");
        BY_FRAGMENT.put("지수 레짐 필터",            "IndexRegimeRule");
        BY_FRAGMENT.put("직전 주문 실패로 대기 중",  "OrderFailureCooldownRule");
    }

    private RiskRuleNameResolver() {}

    static String resolve(String reason) {
        if (reason == null || reason.isBlank()) return UNKNOWN;
        for (Map.Entry<String, String> e : BY_FRAGMENT.entrySet()) {
            if (reason.contains(e.getKey())) return e.getValue();
        }
        return UNKNOWN;
    }
}
