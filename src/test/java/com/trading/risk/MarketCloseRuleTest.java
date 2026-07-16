package com.trading.risk;

import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.Account;
import com.trading.signal.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clock 주입(F-8) 검증 — 서버 타임존과 무관하게 KST 기준으로 판정한다.
 * 컷은 MarketCalendarService의 당일 마감 시각 - 10분(OPERATIONS §5.1) — 평시엔
 * 15:30 마감이라 15:20 컷과 동일, 수능일 등 특례일은 자동으로 밀린다.
 */
@DisplayName("MarketCloseRule — 장 마감 10분 전 이후 신규 매수 금지")
class MarketCloseRuleTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Account ACCOUNT = new Account(1_000_000, 0.0, 0, List.of());

    private static MarketCloseRule ruleAt(LocalDate date, int hour, int minute,
                                           MarketCalendarProperties.EarlyOpenDay... earlyOpenDays) {
        Clock fixed = Clock.fixed(
                LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(KST).toInstant(), KST);
        MarketCalendarProperties props = new MarketCalendarProperties();
        props.setEarlyOpenDays(List.of(earlyOpenDays));
        return new MarketCloseRule(fixed, new MarketCalendarService(props, fixed));
    }

    private static MarketCloseRule ruleAt(int hour, int minute) {
        return ruleAt(LocalDate.of(2026, 7, 8), hour, minute); // 평시(휴장일 아님) 기준 — 기본 마감 15:30
    }

    private static MarketCalendarProperties.EarlyOpenDay earlyOpenDay(LocalDate date, LocalTime open, LocalTime close) {
        MarketCalendarProperties.EarlyOpenDay d = new MarketCalendarProperties.EarlyOpenDay();
        d.setDate(date);
        d.setOpenTime(open);
        d.setCloseTime(close);
        return d;
    }

    @Test
    @DisplayName("15:19 매수 → 통과")
    void buy_passes_before_cutoff() {
        assertThat(ruleAt(15, 19).validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isTrue();
    }

    @Test
    @DisplayName("15:21 매수 → 거부")
    void buy_rejected_after_cutoff() {
        assertThat(ruleAt(15, 21).validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isFalse();
    }

    @Test
    @DisplayName("15:20 정각 매수 → 통과 (isAfter — 정각까지 허용)")
    void buy_passes_at_exact_cutoff() {
        assertThat(ruleAt(15, 20).validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isTrue();
    }

    @Test
    @DisplayName("15:21 매도 → 통과 (매도는 시간 제한 없음)")
    void sell_always_passes() {
        assertThat(ruleAt(15, 21).validate(Signal.sell("005930", "t"), ACCOUNT).isPass()).isTrue();
    }

    // ── 특례일(수능일 등) — 마감 시각이 밀리면 컷도 같이 밀린다 ────────────────────

    @Test
    @DisplayName("수능일 16:19 매수 → 통과 (마감 16:30 기준 컷 16:20)")
    void buy_passes_before_cutoff_on_csat_day() {
        LocalDate csat = LocalDate.of(2026, 11, 19);
        MarketCloseRule rule = ruleAt(csat, 16, 19,
                earlyOpenDay(csat, LocalTime.of(10, 0), LocalTime.of(16, 30)));

        assertThat(rule.validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isTrue();
    }

    @Test
    @DisplayName("수능일 16:21 매수 → 거부 (평시라면 15:20 컷을 훌쩍 넘겼어도 아직 장중이라 통과가 아니라 거부여야 함)")
    void buy_rejected_after_cutoff_on_csat_day() {
        LocalDate csat = LocalDate.of(2026, 11, 19);
        MarketCloseRule rule = ruleAt(csat, 16, 21,
                earlyOpenDay(csat, LocalTime.of(10, 0), LocalTime.of(16, 30)));

        assertThat(rule.validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isFalse();
    }

    @Test
    @DisplayName("수능일 15:20(평시 컷 시각) 매수 → 통과 (수능일엔 아직 컷 전)")
    void buy_passes_at_ordinary_cutoff_time_on_csat_day() {
        LocalDate csat = LocalDate.of(2026, 11, 19);
        MarketCloseRule rule = ruleAt(csat, 15, 20,
                earlyOpenDay(csat, LocalTime.of(10, 0), LocalTime.of(16, 30)));

        assertThat(rule.validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isTrue();
    }
}
