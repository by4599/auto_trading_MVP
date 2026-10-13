package com.trading.bucket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 칸 실현손익 장부 — 굳힌 몫(portfolio_state) + 굳힌 날 이후 몫(원천에서 매번 계산).
 *
 * <p><b>왜 굳히나.</b> 매도가 추정은 분봉을 읽는데, 운영 DB의 분봉은 60일 뒤 지워진다
 * ({@code MinuteCandleRetention}). 그대로 두면 오래된 거래의 추정 손익이 어느 날 0(측정 불가)으로
 * 바뀌어 칸 자산이 저절로 출렁인다 — 이긴 거래가 사라지면 가짜 낙폭으로 헛발동하고, 진 거래가 사라지면
 * 가짜 최고 기록이 생긴다. 그래서 {@value #FREEZE_LAG_DAYS}일 지난 거래는 분봉이 아직 살아 있을 때
 * 금액을 굳혀 둔다(보존 60일의 절반 — 앱이 몇 주 꺼져 있어도 여유가 남는다).
 *
 * <p>읽기({@link #realized})는 저장하지 않는다. 굳히기({@link #freezeIfDue})는 감시기만 부른다.
 * 둘 다 {@code synchronized} — 굳힌 금액과 날짜를 따로 읽다가 사이에 굳히기가 끼면 같은 몫을
 * 두 번 셀 수 있기 때문이다(감시기 스레드와 조회 API 스레드).
 */
@Component
@Profile("paper")
public class SleeveRealizedLedger {

    private static final Logger log = LoggerFactory.getLogger(SleeveRealizedLedger.class);

    /** 매도 후 이만큼 지나면 굳힌다 — 분봉 보존(60일)보다 충분히 짧게 */
    public static final int FREEZE_LAG_DAYS = 30;

    private final SleeveRealizedSource source;
    private final SleeveStateStore store;
    private final BucketProperties properties;
    private final Clock clock;

    public SleeveRealizedLedger(SleeveRealizedSource source, SleeveStateStore store,
                                BucketProperties properties, Clock clock) {
        this.source = source;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /** 실험 시작일 이후 그 칸의 실현손익 전부 = 굳힌 몫 + 굳힌 날부터 오늘까지 */
    public synchronized SleeveRealized realized(StrategyBucket bucket) {
        SleeveStateStore.Checkpoint cp = checkpoint(bucket);
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        return cp.frozen().plus(window(bucket, cp.until(), tomorrow));
    }

    /** 굳힐 몫이 생겼으면(하루 한 번꼴) 굳힌다. 같은 날 다시 불러도 아무것도 하지 않는다 */
    public synchronized void freezeIfDue(StrategyBucket bucket) {
        SleeveStateStore.Checkpoint cp = checkpoint(bucket);
        LocalDate target = LocalDate.now(clock).minusDays(FREEZE_LAG_DAYS);
        if (!target.isAfter(cp.until())) return;

        SleeveRealized slice = window(bucket, cp.until(), target);
        SleeveRealized frozen = cp.frozen().plus(slice);
        store.saveCheckpoint(bucket, new SleeveStateStore.Checkpoint(target,
                new SleeveRealized(frozen.pnl(), frozen.recordedCount(), frozen.estimatedCount(),
                        frozen.unmeasurableCount(), null)));
        log.info("[칸 장부] {} {}~{} 실현손익 {}원(측정 불가 {}건)을 굳힘 — 굳힌 합계 {}원",
                bucket, cp.until(), target.minusDays(1), Math.round(slice.pnl()),
                slice.unmeasurableCount(), Math.round(frozen.pnl()));
    }

    private SleeveStateStore.Checkpoint checkpoint(StrategyBucket bucket) {
        return store.checkpoint(bucket, properties.getExperimentStart());
    }

    private SleeveRealized window(StrategyBucket bucket, LocalDate from, LocalDate toExclusive) {
        if (!from.isBefore(toExclusive)) return SleeveRealized.none();
        return source.realized(bucket, from, toExclusive);
    }
}
