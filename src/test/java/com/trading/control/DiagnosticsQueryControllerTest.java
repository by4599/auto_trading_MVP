package com.trading.control;

import com.trading.risk.ModeTransition;
import com.trading.risk.ModeTransitionRepository;
import com.trading.risk.RiskBlockRecord;
import com.trading.risk.RiskBlockRecordRepository;
import com.trading.risk.TradingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 진단 조회 API 두 개 — 모드 전환 이력과 매수 차단 이력.
 *
 * <p>2026-09-10부터 7거래일간 매매가 0건이었는데 화면에 "언제 멈췄나 / 왜 안 샀나"가
 * 없어 아무도 몰랐다. 이 둘이 그 구멍을 메운다.
 *
 * <p>Java 25 Mockito 제약: 리포지토리(인터페이스)만 목, 컨트롤러는 실객체로 조립한다.
 */
@DisplayName("진단 조회 API — 모드 전환 · 매수 차단")
class DiagnosticsQueryControllerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);

    private final Clock clock = Clock.fixed(TODAY.atTime(20, 0).atZone(KST).toInstant(), KST);

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }

    // ── 모드 전환 이력 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/trading/mode-history")
    class ModeHistory {

        private final ModeTransitionRepository repository = mock(ModeTransitionRepository.class);
        private final ModeHistoryController sut = new ModeHistoryController(repository, clock);

        private void given(ModeTransition... rows) {
            when(repository.findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(any(), any()))
                    .thenReturn(List.of(rows));
        }

        private static ModeTransition transition(String at, TradingMode from, TradingMode to,
                                                 String source) {
            return ModeTransition.of(LocalDateTime.parse(at), from, to, null, source);
        }

        @Test
        @DisplayName("전환 이력을 그대로 내려준다 (사유는 null이어도 필드는 있다)")
        void returns_transitions() {
            given(transition("2026-09-22T09:05:12", TradingMode.RUNNING,
                             TradingMode.FORCE_LIQUIDATING, "LiquidationService"));

            Map<String, Object> result = sut.getModeHistory(30);

            assertThat(result.get("count")).isEqualTo(1);
            Map<String, Object> row = list(result, "transitions").getFirst();
            assertThat(row.get("previousMode")).isEqualTo("RUNNING");
            assertThat(row.get("newMode")).isEqualTo("FORCE_LIQUIDATING");
            assertThat(row.get("source")).isEqualTo("LiquidationService");
            assertThat(row).containsKey("reason");
            assertThat(row.get("reason")).isNull();
        }

        @Test
        @DisplayName("days는 오늘을 포함한 최근 N일 — 하한 시각이 그렇게 계산된다")
        void days_becomes_a_lower_bound() {
            given();

            sut.getModeHistory(30);

            ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(repository).findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                    from.capture(), any(Pageable.class));
            assertThat(from.getValue()).isEqualTo(TODAY.minusDays(29).atStartOfDay());
        }

        @Test
        @DisplayName("days가 범위를 벗어나면 잘라 맞춘다 (0 → 1, 9999 → 365)")
        void days_is_bounded() {
            given();

            assertThat(sut.getModeHistory(0).get("days")).isEqualTo(1);
            assertThat(sut.getModeHistory(9999).get("days")).isEqualTo(365);
        }

        @Test
        @DisplayName("이력이 없어도 빈 배열을 준다 (화면이 깨지지 않게)")
        void empty_is_an_empty_array() {
            given();

            Map<String, Object> result = sut.getModeHistory(30);

            assertThat(result.get("count")).isEqualTo(0);
            assertThat(list(result, "transitions")).isEmpty();
        }
    }

    // ── 매수 차단 이력 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/risk/blocks")
    class RiskBlocks {

        private final RiskBlockRecordRepository repository = mock(RiskBlockRecordRepository.class);
        private final RiskBlockController sut = new RiskBlockController(repository, clock);

        private void given(RiskBlockRecord... rows) {
            when(repository.findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(any(), any()))
                    .thenReturn(List.of(rows));
        }

        private static RiskBlockRecord block(String stockCode, String ruleName, int blockedCount) {
            return RiskBlockRecord.of(LocalDateTime.parse("2026-09-22T09:05:00"), stockCode,
                    ruleName, ruleName + " 사유", "VolatilityBreakout", blockedCount);
        }

        @Test
        @DisplayName("룰별로 몇 번 막았는지 집계한다 — 행 수가 아니라 실제 차단 횟수 기준")
        void aggregates_by_rule_using_the_blocked_count() {
            given(block("005930", "PendingOrderRule", 12),
                  block("000660", "PendingOrderRule", 30),
                  block("005930", "MaxPositionCountRule", 5));

            Map<String, Object> result = sut.getBlocks(7);

            assertThat(result.get("blockedTotal")).isEqualTo(47L);
            List<Map<String, Object>> byRule = list(result, "byRule");
            assertThat(byRule.getFirst().get("ruleName")).isEqualTo("PendingOrderRule");
            assertThat(byRule.getFirst().get("blockedCount")).isEqualTo(42L);
            assertThat(byRule.getFirst().get("recordCount")).isEqualTo(2L);
            assertThat(byRule.getFirst().get("stockCount")).isEqualTo(2);
            assertThat(byRule.getLast().get("ruleName")).isEqualTo("MaxPositionCountRule");
        }

        @Test
        @DisplayName("개별 내역에는 종목·룰·사유 원문·합쳐진 횟수가 들어 있다")
        void rows_carry_the_raw_reason() {
            given(block("005930", "PendingOrderRule", 12));

            Map<String, Object> row = list(sut.getBlocks(7), "blocks").getFirst();

            assertThat(row.get("stockCode")).isEqualTo("005930");
            assertThat(row.get("ruleName")).isEqualTo("PendingOrderRule");
            assertThat(row.get("reason")).isEqualTo("PendingOrderRule 사유");
            assertThat(row.get("blockedCount")).isEqualTo(12);
        }

        @Test
        @DisplayName("기본 기간은 7일이고 하한 시각이 그렇게 계산된다")
        void default_window_is_seven_days() {
            given();

            Map<String, Object> result = sut.getBlocks(7);

            assertThat(result.get("days")).isEqualTo(7);
            assertThat(result.get("from")).isEqualTo(TODAY.minusDays(6).toString());
        }

        @Test
        @DisplayName("차단이 없으면 빈 집계를 준다")
        void empty_is_an_empty_aggregate() {
            given();

            Map<String, Object> result = sut.getBlocks(7);

            assertThat(result.get("blockedTotal")).isEqualTo(0L);
            assertThat(list(result, "byRule")).isEmpty();
            assertThat(list(result, "blocks")).isEmpty();
        }
    }
}
