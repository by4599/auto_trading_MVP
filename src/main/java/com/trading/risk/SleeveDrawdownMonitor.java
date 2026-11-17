package com.trading.risk;

import com.trading.bucket.BucketProperties;
import com.trading.bucket.SleeveEquityCalculator;
import com.trading.bucket.SleeveRealizedLedger;
import com.trading.bucket.StrategyBucket;
import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.Account;
import com.trading.position.PositionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 칸(슬리브)별 낙폭 감시 — ADR-001 §2.2 개정(2026-08-07): A동 −12% / B동 −20%에 닿으면 <b>그 칸만</b>
 * 신규 매수를 멈추고 보유를 정리한다. 다른 칸은 계속 운영한다.
 *
 * <p>판정 조건 — 하나라도 아니면 그 회차는 건너뛰고 연속 확인을 끊는다(옛 값 헛발동 방지):
 * 칸 나누기 ON(paper) · KIS 설정됨 · 장중({@link MarketCalendarService}) · 운전 모드 RUNNING/SAFE_MODE ·
 * <b>신선한</b> 잔고 스냅샷. 그다음 칸마다: 배분금이 있는 칸은 <b>활성·잠김과 무관하게</b> 장부를 굳히고(46_audit M-3 —
 * 꺼진 채 분봉 보존 60일이 지나면 굳히지 않은 이익이 0원이 돼 다시 켤 때 가짜 낙폭으로 잠긴다), 잠긴 칸은 남은
 * 보유를 다시 팔고, 배분금 &gt; 0인 활성 칸은 판정한다.
 *
 * <p>1분 주기 · 이름 없는 {@code @Scheduled} = 기본 1스레드 — 잔고를 읽고 주문을 내므로 손절·청산 감시와
 * 같은 줄에 서야 한다(SchedulingConfig: I/O 풀은 KIS·매매 상태와 무관한 수집 전용). 그 스레드를 얼마나 썼는지
 * 보이도록 {@value #SLOW_ROUND_MS}ms 넘는 회차는 소요 시간을 INFO로 남긴다(46_audit M-2). 계좌 청산 상태머신이
 * 포지션을 쥔 동안(FORCE_LIQUIDATING·EMERGENCY_STOPPED)에는 양보한다.
 */
@Component
@Profile("paper")
public class SleeveDrawdownMonitor {

    private static final Logger log = LoggerFactory.getLogger(SleeveDrawdownMonitor.class);

    /** 이보다 오래 걸린 회차는 소요 시간을 남긴다 — 손절·강제청산 감시가 그만큼 밀렸다는 뜻이다 */
    static final long SLOW_ROUND_MS = 300;

    private final BucketProperties buckets;
    private final KisProperties kisProperties;
    private final MarketCalendarService marketCalendar;
    private final TradingStatusManager statusManager;
    private final PositionManager positionManager;
    private final SleeveRealizedLedger ledger;
    private final SleeveEquityCalculator calculator;
    private final SleeveDrawdownGuard guard;
    private final SleeveLiquidator liquidator;

    /** 칸별 마지막 보류 사유 — 같은 사유를 1분마다 반복해 찍지 않으려고 바뀔 때만 남긴다 */
    private final Map<StrategyBucket, String> lastDeferral = new EnumMap<>(StrategyBucket.class);

    public SleeveDrawdownMonitor(BucketProperties buckets, KisProperties kisProperties,
                                 MarketCalendarService marketCalendar, TradingStatusManager statusManager,
                                 PositionManager positionManager, SleeveRealizedLedger ledger,
                                 SleeveEquityCalculator calculator, SleeveDrawdownGuard guard,
                                 SleeveLiquidator liquidator) {
        this.buckets = buckets;
        this.kisProperties = kisProperties;
        this.marketCalendar = marketCalendar;
        this.statusManager = statusManager;
        this.positionManager = positionManager;
        this.ledger = ledger;
        this.calculator = calculator;
        this.guard = guard;
        this.liquidator = liquidator;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void monitor() {
        long started = System.nanoTime();
        checkSleeves();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        if (elapsedMs > SLOW_ROUND_MS) {
            log.info("[칸 낙폭] 회차 소요 {}ms — 그동안 같은 스레드의 손절·청산 감시가 기다렸다", elapsedMs);
        }
    }

    void checkSleeves() {
        if (!buckets.isEnabled() || !kisProperties.isConfigured()) return;
        if (!marketCalendar.isDuringMarketHoursNow() || !tradingModeAllows()) {
            guard.resetAllStreaks();
            return;
        }
        Account account = freshSnapshot();
        if (account == null) {
            guard.resetAllStreaks();
            return;
        }
        for (StrategyBucket bucket : StrategyBucket.values()) {
            try {
                checkOne(bucket, account);
            } catch (Exception e) {
                // 한 칸의 오류가 다른 칸 감시를 멈추지 않는다. 그 칸의 연속 확인은 처음부터 다시
                guard.resetStreak(bucket);
                log.error("[칸 낙폭] {} 감시 오류 — 이번 회차 건너뜀", bucket, e);
            }
        }
    }

    private void checkOne(StrategyBucket bucket, Account account) {
        freezeQuietly(bucket);
        if (guard.isLocked(bucket)) {
            liquidator.sellHoldings(bucket, account);   // 잠긴 뒤에도 남은 보유가 있으면 다시 판다
            return;
        }
        if (!isWatched(bucket)) return;

        SleeveEquityCalculator.Result result = calculator.compute(bucket, account);
        if (result.isDeferred()) {
            guard.resetStreak(bucket);
            noteDeferral(bucket, result.deferredReason());
            return;
        }
        noteDeferral(bucket, null);

        SleeveDrawdownGuard.Decision decision = guard.evaluate(result.equity());
        log.debug("[칸 낙폭] {} 칸 자산 {}원 · 최고 {}원 · 낙폭 {} · 판정 {}", bucket,
                Math.round(result.equity().equity()), Math.round(decision.peak()),
                decision.drawdown(), decision.outcome());
        if (decision.outcome() == SleeveDrawdownGuard.Outcome.LOCKED_NOW) {
            liquidator.sellHoldings(bucket, account);
        }
    }

    /**
     * 장부는 칸 상태와 무관하게 굳힌다(M-3) — 하루 한 번꼴이고 같은 날 다시 불러도 아무것도 안 한다.
     * 굳히기가 실패해도 같은 회차의 나머지(잠긴 칸 재매도·활성 칸 판정)는 계속한다(46b N-1). 굳히기는 마지막
     * 저장({@code saveAll} 한 번) 전에는 아무것도 바꾸지 않고, 칸 자산은 "굳힌 몫 + 굳힌 날 이후 몫"으로 계산되므로
     * 굳히기가 밀려도 합계는 같다({@code SleeveRealizedLedger.realized}). 다음 회차에 다시 시도한다.
     */
    private void freezeQuietly(StrategyBucket bucket) {
        if (buckets.allocationOf(bucket) <= 0) return;
        try {
            ledger.freezeIfDue(bucket);
        } catch (RuntimeException e) {
            log.warn("[칸 장부] {} 굳히기 실패 — 이번 회차는 넘기고 나머지(재매도·판정)는 계속한다: {}", bucket, e.toString());
        }
    }

    /** 배분금이 있는 활성 칸만 판정한다 (꺼진 칸은 매수 자체가 막혀 있다 — BucketBudgetRule) */
    private boolean isWatched(StrategyBucket bucket) {
        return buckets.isBucketActive(bucket) && buckets.allocationOf(bucket) > 0;
    }

    private boolean tradingModeAllows() {
        TradingMode mode = statusManager.getCurrentMode();
        return mode == TradingMode.RUNNING || mode == TradingMode.SAFE_MODE;
    }

    /** 신선한 스냅샷만 — 실패·낡음이면 null (옛 가격으로 판정하지 않는다) */
    private Account freshSnapshot() {
        try {
            Account account = positionManager.snapshotAccount();
            if (account != null && account.isFresh()) return account;
            log.debug("[칸 낙폭] 잔고 스냅샷이 낡음 — 이번 회차 판정 보류");
        } catch (Exception e) {
            log.warn("[칸 낙폭] 잔고 스냅샷 실패 — 이번 회차 판정 보류: {}", e.getMessage());
        }
        return null;
    }

    private void noteDeferral(StrategyBucket bucket, String reason) {
        String previous = reason == null ? lastDeferral.remove(bucket) : lastDeferral.put(bucket, reason);
        if (Objects.equals(previous, reason)) return;
        if (reason != null) {
            log.warn("[칸 낙폭] {} 판정 보류 — {}", bucket, reason);
        } else {
            log.info("[칸 낙폭] {} 판정 재개", bucket);
        }
    }
}
