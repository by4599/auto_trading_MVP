package com.trading.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 모드 전환 이력 (2026-09-22 신설).
 *
 * <p>고정하는 약속은 하나다: <b>기록은 절대 모드 전환을 막지 않는다.</b>
 * 2026-09월 7거래일 매매 0건을 못 본 이유가 "언제 왜 멈췄는지 기록이 없어서"였으므로
 * 이력을 남기되, 그 이력 때문에 청산·SAFE_MODE 판정이 흔들리면 본말이 전도된다.
 *
 * <p>Java 25 Mockito 제약: 리포지토리(인터페이스)만 목으로 만들고
 * {@link ModeTransitionRecorder}·{@link TradingStatusManager}는 실객체로 조립한다.
 */
@DisplayName("TradingStatusManager — 모드 전환 이력")
class TradingStatusManagerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 22, 9, 5, 12);

    private final ModeTransitionRepository repository = mock(ModeTransitionRepository.class);
    private final Clock clock = Clock.fixed(NOW.atZone(KST).toInstant(), KST);

    /** 목은 리포지토리(인터페이스)뿐 — writer·recorder·manager는 전부 실객체로 조립한다 */
    private TradingStatusManager sut() {
        return new TradingStatusManager(recorder());
    }

    private ModeTransitionRecorder recorder() {
        return new ModeTransitionRecorder(new ModeTransitionWriter(repository), clock);
    }

    private ModeTransition savedTransition() {
        ArgumentCaptor<ModeTransition> captor = ArgumentCaptor.forClass(ModeTransition.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    // ── 기록 ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("기록")
    class Recording {

        @Test
        @DisplayName("모드가 바뀌면 이전 모드·새 모드·시각이 한 건 남는다")
        void records_one_row_per_change() {
            sut().changeMode(TradingMode.EMERGENCY_STOPPED);

            ModeTransition saved = savedTransition();
            assertThat(saved.getPreviousMode()).isEqualTo(TradingMode.RUNNING);
            assertThat(saved.getNewMode()).isEqualTo(TradingMode.EMERGENCY_STOPPED);
            assertThat(saved.getOccurredAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("사유는 null이다 — changeMode가 사유를 받지 않는다 (문서화된 한계)")
        void reason_is_null_because_change_mode_has_none() {
            sut().changeMode(TradingMode.SAFE_MODE);

            assertThat(savedTransition().getReason()).isNull();
        }

        @Test
        @DisplayName("같은 모드로 다시 바꾸면 아무것도 남기지 않는다 (전환이 아니다)")
        void same_mode_is_not_a_transition() {
            TradingStatusManager sut = sut();

            sut.changeMode(TradingMode.RUNNING);   // 초기값이 RUNNING

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("연속 전환은 건마다 남는다")
        void records_every_hop() {
            TradingStatusManager sut = sut();

            sut.changeMode(TradingMode.FORCE_LIQUIDATING);
            sut.changeMode(TradingMode.EMERGENCY_STOPPED);
            sut.changeMode(TradingMode.SAFE_MODE);

            ArgumentCaptor<ModeTransition> captor = ArgumentCaptor.forClass(ModeTransition.class);
            verify(repository, org.mockito.Mockito.times(3)).save(captor.capture());
            assertThat(captor.getAllValues()).extracting(ModeTransition::getNewMode)
                    .containsExactly(TradingMode.FORCE_LIQUIDATING,
                                     TradingMode.EMERGENCY_STOPPED,
                                     TradingMode.SAFE_MODE);
        }
    }

    // ── 기록 실패가 전환을 막지 않는다 (이 테스트가 이 기능의 핵심) ──────────────

    @Nested
    @DisplayName("기록 실패")
    class RecordingFailure {

        @Test
        @DisplayName("DB 저장이 터져도 모드는 바뀐다")
        void db_failure_does_not_block_the_transition() {
            when(repository.save(any())).thenThrow(new RuntimeException("DB 다운"));
            TradingStatusManager sut = sut();

            assertThatCode(() -> sut.changeMode(TradingMode.EMERGENCY_STOPPED))
                    .doesNotThrowAnyException();

            assertThat(sut.getCurrentMode()).isEqualTo(TradingMode.EMERGENCY_STOPPED);
        }

        @Test
        @DisplayName("기록기 자체가 터져도(커밋 실패 흉내) 모드는 바뀐다 — 바깥 try/catch가 받는다")
        void recorder_failure_outside_its_own_catch_is_still_absorbed() {
            // save()를 감싼 catch 밖에서 터지는 경우(트랜잭션 커밋 실패 등)를 흉내낸다.
            // 목이 아니라 실객체를 상속해 만든다 (Java 25 인라인 Mockito 제약).
            ModeTransitionRecorder exploding = new ModeTransitionRecorder(
                    new ModeTransitionWriter(repository), clock) {
                @Override
                public void record(TradingMode previous, TradingMode next, String reason) {
                    throw new IllegalStateException("커밋 실패");
                }
            };
            TradingStatusManager sut = new TradingStatusManager(exploding);

            assertThatCode(() -> sut.changeMode(TradingMode.FORCE_LIQUIDATING))
                    .doesNotThrowAnyException();

            assertThat(sut.getCurrentMode()).isEqualTo(TradingMode.FORCE_LIQUIDATING);
        }

        @Test
        @DisplayName("기록기가 없어도(단위 테스트·backtest) 전환은 정상")
        void works_without_a_recorder() {
            TradingStatusManager sut = new TradingStatusManager();

            sut.changeMode(TradingMode.SAFE_MODE);

            assertThat(sut.getCurrentMode()).isEqualTo(TradingMode.SAFE_MODE);
            verify(repository, never()).save(any());
        }
    }

    // ── 저장 형태 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("트리거 출처에 changeMode를 부른 클래스가 찍힌다 (사유가 없는 지금 유일한 단서)")
    void records_the_calling_class_as_source() {
        sut().changeMode(TradingMode.SAFE_MODE);

        assertThat(savedTransition().getSource()).isEqualTo("TradingStatusManagerTest");
    }

    // ── 감사 24_audit M-1 — 기록이 남의 트랜잭션을 오염시키지 않는다 ──────────

    @Nested
    @DisplayName("트랜잭션 격리 (24_audit M-1)")
    class TransactionIsolation {

        /**
         * 이게 왜 중요한가: changeMode는 남의 트랜잭션 안에서도 불린다.
         * StopLossArmer.onOrderFilled(@Transactional REQUIRES_NEW) → arm() → KIS HTTP →
         * KisApiClient.recordFailure() → changeMode. 저장이 그 트랜잭션에 합류하면
         * 실패 시 <b>방금 장착한 손절선까지 롤백된다.</b>
         */
        @Test
        @DisplayName("저장은 REQUIRES_NEW 독립 트랜잭션으로 나간다 — 바깥 트랜잭션에 합류하지 않는다")
        void save_runs_in_its_own_transaction() throws Exception {
            Transactional annotation = ModeTransitionWriter.class
                    .getMethod("saveInNewTransaction", ModeTransition.class)
                    .getAnnotation(Transactional.class);

            assertThat(annotation).isNotNull();
            assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        }

        @Test
        @DisplayName("저장기는 기록기와 다른 빈이다 — 같은 클래스 안이면 프록시를 안 타 REQUIRES_NEW가 안 걸린다")
        void writer_is_a_separate_bean() {
            assertThat(ModeTransitionWriter.class).isNotEqualTo(ModeTransitionRecorder.class);
            assertThat(ModeTransitionWriter.class.getAnnotation(Component.class)).isNotNull();
        }

        @Test
        @DisplayName("저장기가 프록시 경계에서 터져도(커밋 실패) 기록기가 삼킨다")
        void writer_failure_at_the_proxy_boundary_is_swallowed() {
            ModeTransitionWriter explodingWriter = new ModeTransitionWriter(repository) {
                @Override
                public void saveInNewTransaction(ModeTransition transition) {
                    throw new IllegalStateException("커밋 실패");
                }
            };
            TradingStatusManager sut = new TradingStatusManager(
                    new ModeTransitionRecorder(explodingWriter, clock));

            assertThatCode(() -> sut.changeMode(TradingMode.EMERGENCY_STOPPED))
                    .doesNotThrowAnyException();
            assertThat(sut.getCurrentMode()).isEqualTo(TradingMode.EMERGENCY_STOPPED);
        }
    }

    @Test
    @DisplayName("긴 사유는 컬럼 길이에 맞춰 잘린다 — INSERT가 깨지지 않게")
    void long_reason_is_clipped() {
        String tooLong = "가".repeat(ModeTransition.REASON_MAX + 50);

        ModeTransition t = ModeTransition.of(NOW, TradingMode.RUNNING,
                TradingMode.SAFE_MODE, tooLong, "Somewhere");

        assertThat(t.getReason()).hasSize(ModeTransition.REASON_MAX);
    }

    // ── 스프링 배선 (앱이 못 뜨는 사고를 구조로 막는다) ────────────────────────

    @Nested
    @DisplayName("스프링 배선")
    class Wiring {

        @Test
        @DisplayName("기록기 빈이 없어도 기동한다 — backtest 프로필이 이 경우다")
        void boots_without_the_recorder_bean() {
            try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
                ctx.register(TradingStatusManager.class);
                ctx.refresh();

                assertThat(ctx.getBean(TradingStatusManager.class).getCurrentMode())
                        .isEqualTo(TradingMode.RUNNING);
            }
        }

        @Test
        @DisplayName("기록기 빈이 있으면 주입돼 이력이 남는다")
        void injects_the_recorder_when_present() {
            try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
                ctx.registerBean(ModeTransitionRepository.class, () -> repository);
                ctx.registerBean(Clock.class, () -> clock);
                ctx.register(ModeTransitionWriter.class, ModeTransitionRecorder.class,
                             TradingStatusManager.class);
                ctx.refresh();

                ctx.getBean(TradingStatusManager.class).changeMode(TradingMode.SAFE_MODE);

                assertThat(savedTransition().getNewMode()).isEqualTo(TradingMode.SAFE_MODE);
            }
        }
    }

    @Test
    @DisplayName("기록기를 직접 불러도 예외가 새어 나오지 않는다")
    void recorder_swallows_everything() {
        when(repository.save(any())).thenThrow(new RuntimeException("DB 다운"));

        assertThatCode(() -> recorder()
                .record(TradingMode.RUNNING, TradingMode.SAFE_MODE, null))
                .doesNotThrowAnyException();
    }
}
