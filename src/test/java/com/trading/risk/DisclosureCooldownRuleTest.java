package com.trading.risk;

import com.trading.position.Account;
import com.trading.research.DisclosureItem;
import com.trading.research.DisclosureRepository;
import com.trading.signal.Signal;
import com.trading.strategy.FilterProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공시 쿨다운 필터 — 이벤트성 공시 후 N일간 신규 매수 금지 (기본 OFF).
 * 오늘 = 2026-07-13 고정 Clock. 판정 창은 [어제-(N-1), 어제] — 당일 공시 제외 (선견 편향).
 */
@DisplayName("DisclosureCooldownRule — 공시 후 N일 신규 매수 금지")
class DisclosureCooldownRuleTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 13);
    private static final Account ACCOUNT = new Account(10_000_000, 0.0, 0, List.of());

    private DisclosureRepository disclosureRepository;
    private FilterProperties filters;
    private DisclosureCooldownRule sut;

    @BeforeEach
    void setUp() {
        disclosureRepository = mock(DisclosureRepository.class);
        filters = new FilterProperties();
        Clock fixed = Clock.fixed(
                LocalDateTime.of(TODAY, java.time.LocalTime.NOON).atZone(KST).toInstant(), KST);
        sut = new DisclosureCooldownRule(filters, disclosureRepository, fixed);
    }

    private static DisclosureItem disclosure(String eventType, LocalDate disclosedAt) {
        return DisclosureItem.of("005930", "삼성전자", "R" + disclosedAt + eventType,
                "보고서", disclosedAt, "NEUTRAL", eventType);
    }

    private void givenRecentDisclosures(DisclosureItem... items) {
        when(disclosureRepository.findByStockCodeAndDisclosedAtBetween(
                anyString(), any(), any())).thenReturn(List.of(items));
    }

    @Test
    @DisplayName("기본 OFF → 공시가 있어도 통과")
    void disabled_by_default() {
        givenRecentDisclosures(disclosure("SUPPLY_CONTRACT", TODAY.minusDays(1)));

        assertThat(sut.validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isTrue();
        verify(disclosureRepository, never())
                .findByStockCodeAndDisclosedAtBetween(anyString(), any(), any());
    }

    @Test
    @DisplayName("ON + 어제 이벤트성 공시 → 매수 거부")
    void blocks_after_recent_event_disclosure() {
        filters.getDisclosureCooldown().setEnabled(true);
        givenRecentDisclosures(disclosure("SUPPLY_CONTRACT", TODAY.minusDays(1)));

        RiskResult result = sut.validate(Signal.buy("005930", "t"), ACCOUNT);

        assertThat(result.isPass()).isFalse();
        assertThat(result.getReason()).contains("SUPPLY_CONTRACT");
    }

    @Test
    @DisplayName("정례 공시(지분보고 등)만 있으면 통과 — 상시 차단 방지")
    void routine_disclosures_ignored() {
        filters.getDisclosureCooldown().setEnabled(true);
        givenRecentDisclosures(
                disclosure("INSIDER_OWNERSHIP", TODAY.minusDays(1)),
                disclosure("REGULAR_FILING",    TODAY.minusDays(2)),
                disclosure("LARGE_HOLDING",     TODAY.minusDays(3)));

        assertThat(sut.validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isTrue();
    }

    @Test
    @DisplayName("판정 창은 어제까지 — 당일 공시는 미래 정보라 제외 (선견 편향 차단)")
    void excludes_today_from_window() {
        filters.getDisclosureCooldown().setEnabled(true);
        filters.getDisclosureCooldown().setCooldownDays(5);
        when(disclosureRepository.findByStockCodeAndDisclosedAtBetween(
                anyString(), any(), any())).thenReturn(List.of());

        sut.validate(Signal.buy("005930", "t"), ACCOUNT);

        // 창 = [어제-4, 어제] = [07-08, 07-12]
        verify(disclosureRepository).findByStockCodeAndDisclosedAtBetween(
                "005930", LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 12));
    }

    @Test
    @DisplayName("SELL 신호는 항상 통과 (출구는 막지 않는다)")
    void sell_always_passes() {
        filters.getDisclosureCooldown().setEnabled(true);
        givenRecentDisclosures(disclosure("SUPPLY_CONTRACT", TODAY.minusDays(1)));

        assertThat(sut.validate(Signal.sell("005930", "t"), ACCOUNT).isPass()).isTrue();
    }
}
