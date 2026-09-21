package com.trading.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사유 문자열 → 룰 이름 되짚기.
 *
 * <p>아래 사유 문구는 <b>각 룰의 실제 메시지를 그대로 옮긴 것</b>이다(2026-09-22 기준).
 * 룰이 메시지를 바꾸면 이 테스트가 먼저 깨지므로, 조용히 UNKNOWN으로 떨어지는 일을
 * 여기서 잡는다 — 이것이 {@link RiskRuleNameResolver}의 유일한 안전망이다.
 */
@DisplayName("RiskRuleNameResolver — 사유에서 룰 이름 되짚기")
class RiskRuleNameResolverTest {

    @ParameterizedTest(name = "{1} ← {0}")
    @CsvSource(delimiter = '|', value = {
        "이미 보유 중인 종목: 005930                                              | PendingOrderRule",
        "미체결 매수 주문 대기 중: 005930                                         | PendingOrderRule",
        "종목 비중 한도 초과: 005930 (현재 12.0% >= 최대 10%)                    | PositionLimitRule",
        "최대 보유 종목 수 초과: 현재 5개 (최대 5개)                              | MaxPositionCountRule",
        "15:20 이후 신규 매수 금지 (장 마감 임박)                                 | MarketCloseRule",
        "15:15 타임컷 이후 신규 매수 금지 (당일 청산 전제) — 현재 15:31           | PostTimeCutBuyRule",
        "일일 손실 한도 -3% 도달 (현재 -3.20%) — 당일 신규 매수 중지             | DailyLossRule",
        "전고점 대비 MDD 10.12% 초과 — 신규 매수 금지                            | GlobalEquityStopRule",
        "연속 손실 3회 — 1시간 매수 중지                                          | ConsecutiveLossRule",
        "칸 비활성: EVENT — 재료(검증 통과 신호) 확보 전까지 매수 잠금            | BucketBudgetRule",
        "칸 예산 소진: MIX (가용 현금 12000원)                                    | BucketBudgetRule",
        "공시 쿨다운 — 유상증자 공시(2026-09-01) 후 5일 내 신규 매수 금지         | DisclosureCooldownRule",
        "진입 시간창 필터 — 09:30 이전 신규 매수 금지 (현재 09:05)                | EntryTimeWindowRule",
        "지수 추세 필터 — 지수가 MA120 아래(하락 추세) 신규 매수 금지             | IndexTrendRule",
        "지수 레짐 필터 — KOSPI 갭다운일 신규 매수 금지                           | IndexRegimeRule",
        "직전 주문 실패로 대기 중: 005930                                         | OrderFailureCooldownRule",
    })
    void maps_each_rule_message(String reason, String expectedRule) {
        assertThat(RiskRuleNameResolver.resolve(reason.trim())).isEqualTo(expectedRule);
    }

    @Test
    @DisplayName("모르는 문구·null·빈 문자열은 UNKNOWN — 사유 원문은 따로 저장되므로 정보를 잃지 않는다")
    void unknown_messages_fall_back() {
        assertThat(RiskRuleNameResolver.resolve("아직 없는 새 룰의 사유")).isEqualTo("UNKNOWN");
        assertThat(RiskRuleNameResolver.resolve(null)).isEqualTo("UNKNOWN");
        assertThat(RiskRuleNameResolver.resolve("   ")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("장 마감과 타임컷은 둘 다 시각으로 시작하지만 서로 섞이지 않는다")
    void market_close_and_time_cut_are_not_confused() {
        assertThat(RiskRuleNameResolver.resolve("15:20 이후 신규 매수 금지 (장 마감 임박)"))
                .isEqualTo("MarketCloseRule");
        assertThat(RiskRuleNameResolver.resolve(
                "15:15 타임컷 이후 신규 매수 금지 (당일 청산 전제) — 현재 15:31"))
                .isEqualTo("PostTimeCutBuyRule");
    }
}
