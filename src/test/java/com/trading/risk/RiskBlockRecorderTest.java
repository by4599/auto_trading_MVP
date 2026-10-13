package com.trading.risk;

import com.trading.signal.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 매수 차단 이력 (2026-09-22 신설).
 *
 * <p>여기서 고정하는 것은 두 가지다:
 * <ul>
 *   <li><b>중복 억제가 실제로 동작한다</b> — 1초 루프 × 유니버스 20종목에서 억제가 없으면
 *       하루 수천~수만 행이 쌓인다.</li>
 *   <li><b>기록 실패가 매매 루프로 새지 않는다.</b></li>
 * </ul>
 *
 * <p>Java 25 Mockito 제약: 리포지토리(인터페이스)만 목, 기록기는 실객체로 조립한다.
 * 시계는 테스트가 직접 굴린다 — 창이 지났는지를 진짜 시간에 기대지 않는다.
 */
@DisplayName("RiskBlockRecorder — 매수 차단 이력 · 중복 억제")
class RiskBlockRecorderTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 22, 9, 5, 0);
    private static final int WINDOW_MINUTES = 10;

    private static final String HOLDING = "이미 보유 중인 종목: 005930";
    private static final String MAX_COUNT = "최대 보유 종목 수 초과: 현재 5개 (최대 5개)";

    /** 테스트가 손으로 굴리는 시계 — 창(10분)이 지났는지를 실제 대기 없이 확인한다 */
    private static final class MovableClock extends Clock {
        private Instant now;
        MovableClock(LocalDateTime at) { this.now = at.atZone(KST).toInstant(); }
        void advanceMinutes(long minutes) { now = now.plusSeconds(minutes * 60); }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return KST; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final RiskBlockRecordRepository repository = mock(RiskBlockRecordRepository.class);
    private final MovableClock clock = new MovableClock(START);
    /** 목은 리포지토리(인터페이스)뿐 — writer·recorder는 실객체로 조립한다 */
    private final RiskBlockRecorder sut =
            new RiskBlockRecorder(new RiskBlockWriter(repository), clock, WINDOW_MINUTES);

    private static Signal buy(String stockCode) {
        return Signal.buy(stockCode, "VolatilityBreakout");
    }

    private List<RiskBlockRecord> saved(int count) {
        ArgumentCaptor<RiskBlockRecord> captor = ArgumentCaptor.forClass(RiskBlockRecord.class);
        verify(repository, times(count)).save(captor.capture());
        return captor.getAllValues();
    }

    // ── 중복 억제 ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("중복 억제")
    class Suppression {

        @Test
        @DisplayName("첫 차단은 즉시 저장한다 (창이 끝나기를 기다리지 않는다)")
        void first_block_is_saved_immediately() {
            sut.record(buy("005930"), HOLDING);

            RiskBlockRecord row = saved(1).getFirst();
            assertThat(row.getStockCode()).isEqualTo("005930");
            assertThat(row.getRuleName()).isEqualTo("PendingOrderRule");
            assertThat(row.getBlockedCount()).isEqualTo(1);
            assertThat(row.getOccurredAt()).isEqualTo(START);
        }

        @Test
        @DisplayName("창 안에서 같은 종목·같은 룰이 반복되면 더 저장하지 않는다")
        void repeats_inside_the_window_are_not_saved() {
            for (int i = 0; i < 60; i++) {
                sut.record(buy("005930"), HOLDING);
                clock.advanceSeconds(1);
            }

            verify(repository, times(1)).save(any());
        }

        @Test
        @DisplayName("창이 지나면 다시 저장하고, 억제된 횟수가 blockedCount에 실린다")
        void next_window_carries_the_suppressed_count() {
            sut.record(buy("005930"), HOLDING);     // 1회차 — 즉시 저장
            for (int i = 0; i < 4; i++) {           // 창 안에서 4회 더 (억제)
                clock.advanceSeconds(30);
                sut.record(buy("005930"), HOLDING);
            }
            clock.advanceMinutes(WINDOW_MINUTES);
            sut.record(buy("005930"), HOLDING);     // 창 이후 — 저장

            List<RiskBlockRecord> rows = saved(2);
            assertThat(rows.get(0).getBlockedCount()).isEqualTo(1);
            // 억제된 4회 + 이번 1회 = 5
            assertThat(rows.get(1).getBlockedCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("종목이 다르면 서로 억제하지 않는다")
        void different_stocks_are_independent() {
            sut.record(buy("005930"), HOLDING);
            sut.record(buy("000660"), HOLDING);

            assertThat(saved(2)).extracting(RiskBlockRecord::getStockCode)
                    .containsExactly("005930", "000660");
        }

        @Test
        @DisplayName("같은 종목이라도 룰이 다르면 서로 억제하지 않는다")
        void different_rules_are_independent() {
            sut.record(buy("005930"), HOLDING);
            sut.record(buy("005930"), MAX_COUNT);

            assertThat(saved(2)).extracting(RiskBlockRecord::getRuleName)
                    .containsExactly("PendingOrderRule", "MaxPositionCountRule");
        }

        @Test
        @DisplayName("억제 창을 0분으로 끄면 매번 저장한다 (설정으로 되돌릴 수 있다)")
        void zero_window_saves_every_time() {
            RiskBlockRecorder noSuppression =
                    new RiskBlockRecorder(new RiskBlockWriter(repository), clock, 0);

            noSuppression.record(buy("005930"), HOLDING);
            noSuppression.record(buy("005930"), HOLDING);
            noSuppression.record(buy("005930"), HOLDING);

            verify(repository, times(3)).save(any());
        }
    }

    // ── 기록 실패 ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("기록 실패")
    class Failure {

        @Test
        @DisplayName("DB가 터져도 예외가 매매 루프로 새지 않는다")
        void db_failure_is_swallowed() {
            when(repository.save(any())).thenThrow(new RuntimeException("DB 다운"));

            assertThatCode(() -> sut.record(buy("005930"), HOLDING)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("OpportunityCostLogger는 기록기가 없어도 로그만 남기고 조용히 넘어간다")
        void logger_works_without_a_recorder() {
            OpportunityCostLogger logger = new OpportunityCostLogger();

            assertThatCode(() -> logger.logDropped(buy("005930"), HOLDING))
                    .doesNotThrowAnyException();
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("OpportunityCostLogger가 기록기에 넘긴다 — 로그 출력은 그대로 두고 덧붙인 것")
        void logger_delegates_to_the_recorder() {
            new OpportunityCostLogger(sut).logDropped(buy("005930"), HOLDING);

            assertThat(saved(1).getFirst().getReason()).isEqualTo(HOLDING);
        }

        @Test
        @DisplayName("기록기가 통째로 터져도(커밋 실패 흉내) 로거는 예외를 내지 않는다")
        void logger_absorbs_recorder_explosion() {
            RiskBlockRecorder exploding = new RiskBlockRecorder(
                    new RiskBlockWriter(repository), clock, WINDOW_MINUTES) {
                @Override
                public void record(Signal signal, String reason) {
                    throw new IllegalStateException("커밋 실패");
                }
            };

            assertThatCode(() -> new OpportunityCostLogger(exploding)
                    .logDropped(buy("005930"), HOLDING)).doesNotThrowAnyException();
        }
    }

    // ── 스프링 배선 (앱이 못 뜨는 사고를 구조로 막는다) ────────────────────────

    @Nested
    @DisplayName("스프링 배선")
    class Wiring {

        @Test
        @DisplayName("기록기 빈이 없어도 OpportunityCostLogger는 기동한다 — backtest 프로필이 이 경우다")
        void logger_boots_without_the_recorder_bean() {
            try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
                ctx.register(OpportunityCostLogger.class);
                ctx.refresh();

                assertThatCode(() -> ctx.getBean(OpportunityCostLogger.class)
                        .logDropped(buy("005930"), HOLDING)).doesNotThrowAnyException();
                verify(repository, never()).save(any());
            }
        }

        @Test
        @DisplayName("기록기 빈이 있으면 주입돼 이력이 남는다")
        void logger_gets_the_recorder_when_present() {
            try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
                ctx.registerBean(RiskBlockRecordRepository.class, () -> repository);
                ctx.registerBean(Clock.class, () -> clock);
                ctx.register(RiskBlockWriter.class, RiskBlockRecorder.class,
                             OpportunityCostLogger.class);
                ctx.refresh();

                ctx.getBean(OpportunityCostLogger.class).logDropped(buy("005930"), HOLDING);

                assertThat(saved(1).getFirst().getStockCode()).isEqualTo("005930");
            }
        }
    }

    // ── 감사 24_audit M-1 — 기록이 남의 트랜잭션을 오염시키지 않는다 ──────────

    @Nested
    @DisplayName("트랜잭션 격리 (24_audit M-1)")
    class TransactionIsolation {

        @Test
        @DisplayName("저장은 REQUIRES_NEW 독립 트랜잭션으로 나간다")
        void save_runs_in_its_own_transaction() throws Exception {
            Transactional annotation = RiskBlockWriter.class
                    .getMethod("saveInNewTransaction", RiskBlockRecord.class)
                    .getAnnotation(Transactional.class);

            assertThat(annotation).isNotNull();
            assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        }

        @Test
        @DisplayName("저장기가 프록시 경계에서 터져도(커밋 실패) 기록기가 삼킨다")
        void writer_failure_at_the_proxy_boundary_is_swallowed() {
            RiskBlockWriter explodingWriter = new RiskBlockWriter(repository) {
                @Override
                public void saveInNewTransaction(RiskBlockRecord record) {
                    throw new IllegalStateException("커밋 실패");
                }
            };
            RiskBlockRecorder recorder =
                    new RiskBlockRecorder(explodingWriter, clock, WINDOW_MINUTES);

            assertThatCode(() -> recorder.record(buy("005930"), HOLDING))
                    .doesNotThrowAnyException();
        }
    }

    // ── 저장 내용 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("정리 배치는 기록기와 같은 프로필이다 — real에서 쌓이기만 하면 안 된다 (24_audit L-2)")
    void retention_profile_matches_the_recorder() {
        assertThat(RiskBlockRetention.class.getAnnotation(Profile.class).value())
                .containsExactly(RiskBlockRecorder.class.getAnnotation(Profile.class).value());
    }

    @Test
    @DisplayName("사유 원문과 전략 이름을 그대로 남긴다 — 룰 이름은 추정이지만 사유는 원문이다")
    void keeps_the_raw_reason() {
        sut.record(Signal.buy("005930", "MovingAverageBreakout"), MAX_COUNT);

        RiskBlockRecord row = saved(1).getFirst();
        assertThat(row.getReason()).isEqualTo(MAX_COUNT);
        assertThat(row.getStrategyName()).isEqualTo("MovingAverageBreakout");
    }

    @Test
    @DisplayName("모르는 사유는 UNKNOWN으로 묶되 원문은 잃지 않는다")
    void unknown_rule_still_keeps_the_reason() {
        sut.record(buy("005930"), "앞으로 누가 새로 쓸지 모르는 사유 문구");

        RiskBlockRecord row = saved(1).getFirst();
        assertThat(row.getRuleName()).isEqualTo("UNKNOWN");
        assertThat(row.getReason()).isEqualTo("앞으로 누가 새로 쓸지 모르는 사유 문구");
    }
}
