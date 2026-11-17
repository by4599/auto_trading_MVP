package com.trading.bucket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 칸 실현손익 장부 — 굳힌 몫 + 최근 몫.
 *
 * <p>왜 굳히나: 매도가 추정에 쓰는 분봉은 운영 DB에서 60일 뒤 지워진다(MinuteCandleRetention).
 * 그대로 두면 오래된 거래의 추정 손익이 어느 날 0으로 바뀌어 칸 자산이 저절로 출렁인다 —
 * 이긴 거래가 사라지면 가짜 낙폭(헛발동), 진 거래가 사라지면 가짜 최고 기록이 생긴다.
 * 그래서 30일 지난 거래는 분봉이 살아 있을 때 금액을 portfolio_state에 굳혀 둔다.
 *
 * <p>날짜 사실관계(시스템 도구 확인): 2026-10-14 수, 2026-10-14 − 30일 = 2026-09-14.
 */
@DisplayName("SleeveRealizedLedger — 굳힌 실현손익 + 최근 실현손익")
class SleeveRealizedLedgerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate EXPERIMENT_START = LocalDate.of(2026, 7, 20);

    private final Map<String, Double> state = new HashMap<>();
    private final Map<LocalDate, Double> vbTrades = new TreeMap<>();
    private final AtomicInteger sourceCalls = new AtomicInteger();
    private LocalDate purgedBefore = LocalDate.MIN;

    /** 가짜 원천 — 날짜별 거래 손익. purgedBefore 이전 거래는 분봉이 지워져 측정 불가가 된다 */
    private final SleeveRealizedSource source = (bucket, from, toExclusive) -> {
        sourceCalls.incrementAndGet();
        if (bucket != StrategyBucket.VB) return SleeveRealized.none();
        double pnl = 0;
        int estimated = 0;
        int unmeasurable = 0;
        LocalDate latest = null;
        for (Map.Entry<LocalDate, Double> t : vbTrades.entrySet()) {
            LocalDate d = t.getKey();
            if (d.isBefore(from) || !d.isBefore(toExclusive)) continue;
            if (d.isBefore(purgedBefore)) {
                unmeasurable++;
                latest = d;
            } else {
                pnl += t.getValue();
                estimated++;
            }
        }
        return new SleeveRealized(pnl, 0, estimated, unmeasurable, latest);
    };

    private SleeveRealizedLedger ledgerOn(LocalDate day) {
        Clock clock = Clock.fixed(day.atTime(10, 0).atZone(KST).toInstant(), KST);
        BucketProperties props = new BucketProperties(true, EXPERIMENT_START.toString(),
                10_000_000, 10_000_000, 10_000_000, 4_000_000, false, false, true);
        return new SleeveRealizedLedger(source,
                new SleeveStateStore(InMemoryPortfolioState.create(state)), props, clock);
    }

    private void givenVbTrades() {
        vbTrades.put(LocalDate.of(2026, 8, 20), 50_000.0);
        vbTrades.put(LocalDate.of(2026, 9, 20), -30_000.0);
        vbTrades.put(LocalDate.of(2026, 10, 13), 10_000.0);
    }

    @Test
    @DisplayName("굳힌 것이 없으면 실험 시작일부터 오늘까지 전부 합친다")
    void without_checkpoint_sums_from_experiment_start() {
        givenVbTrades();

        SleeveRealized r = ledgerOn(LocalDate.of(2026, 10, 14)).realized(StrategyBucket.VB);

        assertThat(r.pnl()).isEqualTo(30_000);
        assertThat(r.estimatedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("30일 지난 거래를 굳혀도 합계는 그대로다")
    void freezing_does_not_change_the_total() {
        givenVbTrades();
        SleeveRealizedLedger ledger = ledgerOn(LocalDate.of(2026, 10, 14));

        ledger.freezeIfDue(StrategyBucket.VB);

        assertThat(ledger.realized(StrategyBucket.VB).pnl()).isEqualTo(30_000);
        assertThat(state.get(SleeveStateStore.key("FROZEN_PNL", StrategyBucket.VB))).isEqualTo(50_000);
        assertThat(state.get(SleeveStateStore.key("FROZEN_UNTIL", StrategyBucket.VB))).isEqualTo(20260914);
    }

    @Test
    @DisplayName("굳힌 뒤 분봉이 지워져도(측정 불가로 바뀌어도) 칸 실현손익이 출렁이지 않는다")
    void frozen_amount_survives_candle_purge() {
        givenVbTrades();
        SleeveRealizedLedger ledger = ledgerOn(LocalDate.of(2026, 10, 14));
        ledger.freezeIfDue(StrategyBucket.VB);

        purgedBefore = LocalDate.of(2026, 9, 1);   // 08-20 거래의 분봉이 지워졌다

        assertThat(ledger.realized(StrategyBucket.VB).pnl()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("같은 날 다시 불러도 두 번 더하지 않는다 — 다음 날에는 새로 30일이 지난 몫만 더한다")
    void freezing_is_idempotent_within_a_day_and_incremental_across_days() {
        givenVbTrades();
        vbTrades.put(LocalDate.of(2026, 9, 14), -5_000.0);   // 다음 날 굳힐 몫
        SleeveRealizedLedger today = ledgerOn(LocalDate.of(2026, 10, 14));
        today.freezeIfDue(StrategyBucket.VB);
        int callsAfterFirst = sourceCalls.get();

        today.freezeIfDue(StrategyBucket.VB);
        assertThat(sourceCalls.get()).isEqualTo(callsAfterFirst);   // 굳힐 것이 없으면 원천을 다시 안 읽는다
        assertThat(state.get(SleeveStateStore.key("FROZEN_PNL", StrategyBucket.VB))).isEqualTo(50_000);

        ledgerOn(LocalDate.of(2026, 10, 15)).freezeIfDue(StrategyBucket.VB);
        assertThat(state.get(SleeveStateStore.key("FROZEN_PNL", StrategyBucket.VB))).isEqualTo(45_000);
        assertThat(state.get(SleeveStateStore.key("FROZEN_UNTIL", StrategyBucket.VB))).isEqualTo(20260915);
    }

    @Test
    @DisplayName("굳힐 때 이미 측정 불가였던 거래 수도 함께 굳어 계속 보고된다")
    void frozen_unmeasurable_count_is_kept() {
        givenVbTrades();
        purgedBefore = LocalDate.of(2026, 9, 1);   // 배포 전에 이미 지워진 분봉
        SleeveRealizedLedger ledger = ledgerOn(LocalDate.of(2026, 10, 14));

        ledger.freezeIfDue(StrategyBucket.VB);

        SleeveRealized r = ledger.realized(StrategyBucket.VB);
        assertThat(r.pnl()).isEqualTo(-20_000);
        assertThat(r.unmeasurableCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("칸마다 장부가 따로다 — VB를 굳혀도 TREND 몫은 그대로")
    void checkpoints_are_per_bucket() {
        givenVbTrades();
        SleeveRealizedLedger ledger = ledgerOn(LocalDate.of(2026, 10, 14));

        ledger.freezeIfDue(StrategyBucket.VB);

        assertThat(state).doesNotContainKey(SleeveStateStore.key("FROZEN_UNTIL", StrategyBucket.TREND));
        assertThat(ledger.realized(StrategyBucket.TREND)).isEqualTo(SleeveRealized.none());
    }
}
