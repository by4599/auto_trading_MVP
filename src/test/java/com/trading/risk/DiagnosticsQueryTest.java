package com.trading.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 새로 만든 파생 쿼리가 <b>실제 DB에서</b> 도는지 확인한다.
 *
 * <p>목으로만 검증하면 "서비스가 리포지토리를 불렀다"까지만 증명된다 — 파생 쿼리 이름이
 * 잘못되면 그 사실은 <b>애플리케이션 기동 때(즉 운영에서)</b> 드러난다.
 * 여기서 H2로 한 번 돌려 이름 파싱·정렬·페이징·기간 필터·삭제를 모두 고정한다
 * ({@code OrderHistoryQueryTest}와 같은 방식).
 *
 * <p>@DataJpaTest는 임베디드 DB로 갈아끼우므로 운영 DB(trading-db)를 건드리지 않는다.
 */
@DataJpaTest
@ActiveProfiles("paper")
@DisplayName("진단 이력 파생 쿼리 — 실제 H2")
class DiagnosticsQueryTest {

    @Autowired ModeTransitionRepository modeRepo;
    @Autowired RiskBlockRecordRepository blockRepo;

    private static LocalDateTime at(String iso) { return LocalDateTime.parse(iso); }

    // ── 모드 전환 이력 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("mode_transition")
    class ModeTransitions {

        private void given(String occurredAt, TradingMode from, TradingMode to) {
            modeRepo.save(ModeTransition.of(at(occurredAt), from, to, null, "LiquidationService"));
        }

        @Test
        @DisplayName("최신이 먼저 나오고, 기간 하한보다 이른 것은 빠진다")
        void newest_first_with_lower_bound() {
            given("2026-09-10T09:05", TradingMode.RUNNING, TradingMode.FORCE_LIQUIDATING);
            given("2026-09-10T09:06", TradingMode.FORCE_LIQUIDATING, TradingMode.EMERGENCY_STOPPED);
            given("2026-09-20T10:00", TradingMode.EMERGENCY_STOPPED, TradingMode.SAFE_MODE);

            List<ModeTransition> rows = modeRepo
                    .findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                            at("2026-09-10T09:06"), PageRequest.of(0, 50));

            assertThat(rows).extracting(ModeTransition::getNewMode)
                    .containsExactly(TradingMode.SAFE_MODE, TradingMode.EMERGENCY_STOPPED);
        }

        @Test
        @DisplayName("Pageable 상한이 실제로 잘라낸다")
        void page_size_caps_the_result() {
            for (int i = 1; i <= 5; i++) {
                given("2026-09-1" + i + "T09:00", TradingMode.RUNNING, TradingMode.SAFE_MODE);
            }

            assertThat(modeRepo.findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                    at("2026-01-01T00:00"), PageRequest.of(0, 2))).hasSize(2);
        }

        @Test
        @DisplayName("사유가 null이어도 저장된다 — changeMode가 사유를 주지 않는다")
        void null_reason_is_persistable() {
            given("2026-09-10T09:05", TradingMode.RUNNING, TradingMode.SAFE_MODE);

            assertThat(modeRepo.findAll()).singleElement()
                    .satisfies(t -> {
                        assertThat(t.getReason()).isNull();
                        assertThat(t.getSource()).isEqualTo("LiquidationService");
                    });
        }
    }

    // ── 매수 차단 이력 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("risk_block_record")
    class RiskBlocks {

        private void given(String occurredAt, String stockCode, String ruleName, int blockedCount) {
            blockRepo.save(RiskBlockRecord.of(at(occurredAt), stockCode, ruleName,
                    ruleName + " 사유", "VolatilityBreakout", blockedCount));
        }

        @Test
        @DisplayName("최신이 먼저 나오고, 기간 하한보다 이른 것은 빠진다")
        void newest_first_with_lower_bound() {
            given("2026-09-10T09:05", "005930", "PendingOrderRule", 3);
            given("2026-09-20T09:05", "000660", "MaxPositionCountRule", 7);

            List<RiskBlockRecord> rows = blockRepo
                    .findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                            at("2026-09-15T00:00"), PageRequest.of(0, 50));

            assertThat(rows).extracting(RiskBlockRecord::getStockCode).containsExactly("000660");
        }

        @Test
        @DisplayName("보존 기간 정리 — 센 만큼 지워지고 최근 것은 남는다")
        void retention_counts_and_deletes() {
            given("2026-06-01T09:05", "005930", "PendingOrderRule", 1);
            given("2026-06-02T09:05", "005930", "PendingOrderRule", 1);
            given("2026-09-20T09:05", "000660", "PendingOrderRule", 1);

            LocalDateTime cutoff = at("2026-07-01T00:00");
            assertThat(blockRepo.countByOccurredAtBefore(cutoff)).isEqualTo(2);

            assertThat(blockRepo.deleteByOccurredAtBefore(cutoff)).isEqualTo(2);
            assertThat(blockRepo.findAll()).extracting(RiskBlockRecord::getStockCode)
                    .containsExactly("000660");
        }
    }
}
