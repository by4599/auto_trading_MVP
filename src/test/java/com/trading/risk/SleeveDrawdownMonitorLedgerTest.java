package com.trading.risk;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.trading.bucket.SleeveRealized;
import com.trading.bucket.SleeveStateStore;
import com.trading.bucket.StrategyBucket;
import com.trading.position.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 감사 후속(46_audit) — 칸 장부는 칸 상태와 무관하게 굳힌다(M-3), 무거운 회차는 소요 시간을 남긴다(M-2 ④),
 * 굳히기가 실패해도 같은 회차의 나머지(잠긴 칸 재매도·활성 칸 판정)는 계속한다(46b N-1).
 *
 * <p>M-3 사고 경로: 이익으로 최고 기록을 올린 칸이 꺼진 채 30~60일 지나면, 굳히지 않은 이익 거래가 분봉 삭제로
 * 0원(측정 불가)이 된다 → 다시 켜는 순간 가짜 낙폭으로 헛잠금·정리 매도.
 * 날짜(시스템 도구 확인): 2026-10-14 수, 10-15 목, 10-14 − 30일 = 09-14.
 */
@DisplayName("SleeveDrawdownMonitor — 꺼진·잠긴 칸 장부 굳히기와 회차 소요 기록")
class SleeveDrawdownMonitorLedgerTest {

    private static final LocalDateTime OCT14 = LocalDateTime.of(2026, 10, 14, 10, 0);
    private static final LocalDate WIN_DAY = LocalDate.of(2026, 8, 20);

    private final SleeveMonitorFixture f = new SleeveMonitorFixture();
    private boolean candlesPurged = false;

    @BeforeEach
    void freshEmptyAccount() {
        when(f.positionManager.snapshotAccount()).thenReturn(new Account(10_000_000, 0.0, 0, List.of()));
    }

    /** EVENT 칸이 08-20에 250만 이익 — 분봉이 지워지면 측정 불가(0원)로 바뀐다 */
    private void givenEventBigWinOnAug20() {
        f.source = (bucket, from, to) -> {
            boolean inWindow = !WIN_DAY.isBefore(from) && WIN_DAY.isBefore(to);
            if (bucket != StrategyBucket.EVENT || !inWindow) return SleeveRealized.none();
            return candlesPurged ? new SleeveRealized(0, 0, 0, 1, WIN_DAY)
                                 : new SleeveRealized(2_500_000, 0, 1, 0, null);
        };
    }

    @Test
    @DisplayName("꺼진 칸(EVENT·MIX)도 장부는 굳힌다 — 활성 여부와 무관")
    void inactive_buckets_are_frozen_too() {
        f.monitorAt(OCT14).checkSleeves();

        assertThat(f.state.get(SleeveStateStore.key("FROZEN_UNTIL", StrategyBucket.EVENT))).isEqualTo(20260914);
        assertThat(f.state.get(SleeveStateStore.key("FROZEN_UNTIL", StrategyBucket.MIX))).isEqualTo(20260914);
    }

    @Test
    @DisplayName("잠긴 칸도 장부는 굳힌다 — 잠긴 채 오래 있다가 풀어도 최고 기록 재설정값이 틀어지지 않게")
    void locked_bucket_is_frozen_too() {
        f.store().lock(StrategyBucket.TREND,
                new SleeveStateStore.LockState(true, Instant.EPOCH, 0.13, 3_480_000, 0.12));

        f.monitorAt(OCT14).checkSleeves();

        assertThat(f.state.get(SleeveStateStore.key("FROZEN_UNTIL", StrategyBucket.TREND))).isEqualTo(20260914);
    }

    @Test
    @DisplayName("이익으로 최고를 올린 칸이 꺼져 있는 동안 분봉이 지워져도, 다시 켤 때 가짜 낙폭으로 잠기지 않는다")
    void reactivated_bucket_is_not_falsely_locked() {
        givenEventBigWinOnAug20();
        f.store().savePeak(StrategyBucket.EVENT, new SleeveStateStore.Peak(12_500_000, 10_000_000));
        f.monitorAt(OCT14).checkSleeves();                 // EVENT 꺼짐 — 이때 굳혀 둬야 한다

        candlesPurged = true;                               // 08-20 분봉 삭제(보존 60일)
        f.eventEnabled = true;                              // 사람이 EVENT를 다시 켰다(재기동)
        SleeveDrawdownMonitor reenabled = f.monitorAt(LocalDateTime.of(2026, 10, 15, 10, 0));
        reenabled.checkSleeves();
        reenabled.checkSleeves();

        assertThat(f.locked(StrategyBucket.EVENT)).isFalse();
    }

    @Test
    @DisplayName("300ms 넘게 걸린 회차는 소요 시간을 INFO로 남긴다 — 손절·청산 감시와 같은 스레드를 얼마나 썼는지 보이게")
    void slow_round_logs_its_duration() {
        when(f.positionManager.snapshotAccount()).thenAnswer(i -> {
            Thread.sleep(350);
            return new Account(10_000_000, 0.0, 0, List.of());
        });
        Logger logger = (Logger) LoggerFactory.getLogger(SleeveDrawdownMonitor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            f.monitorAt(OCT14).monitor();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("회차 소요");
        });
    }

    @Test
    @DisplayName("굳히기가 실패해도 잠긴 칸의 남은 보유 다시 팔기는 그 회차에 나간다 (46b N-1)")
    void freeze_failure_does_not_stop_locked_sleeve_resell() {
        f.source = (bucket, from, to) -> {
            throw new IllegalStateException("분봉 조회 실패");
        };
        f.hold("005930", StrategyBucket.TREND, 100, 40_000);
        when(f.positionManager.snapshotAccount()).thenReturn(new Account(9_000_000, 0.0, 0, List.of(
                new Account.PositionSnapshot("005930", 100, 40_000, 35_000))));
        f.store().lock(StrategyBucket.TREND,
                new SleeveStateStore.LockState(true, Instant.EPOCH, 0.125, 3_500_000, 0.12));

        f.monitorAt(OCT14).checkSleeves();

        verify(f.orderClient).sell("005930", 100);
    }

    @Test
    @DisplayName("굳히기가 실패해도 활성 칸 판정은 돈다 — 칸 자산은 굳힌 몫 + 이후 몫으로 그대로 계산된다 (46b N-1)")
    void freeze_failure_does_not_stop_evaluation() {
        LocalDate freezeTarget = LocalDate.of(2026, 9, 14);   // 10-14 − 30일: 굳히기만 이 날로 끝나는 구간을 묻는다
        f.source = (bucket, from, to) -> {
            if (to.equals(freezeTarget)) throw new IllegalStateException("굳히기 구간 조회 실패");
            return SleeveRealized.none();
        };

        f.monitorAt(OCT14).checkSleeves();

        assertThat(f.storedPeak(StrategyBucket.TREND)).contains(4_000_000.0);   // 판정이 돌아 최고 기록이 잡혔다
        assertThat(f.state).doesNotContainKey(SleeveStateStore.key("FROZEN_UNTIL", StrategyBucket.TREND));
    }
}
