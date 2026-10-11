package com.trading.control;

import com.trading.bucket.BucketProperties;
import com.trading.bucket.SleeveDrawdownProperties;
import com.trading.bucket.SleeveEquity;
import com.trading.bucket.SleeveEquityCalculator;
import com.trading.bucket.SleeveRealizedSource;
import com.trading.bucket.SleeveStateStore;
import com.trading.bucket.StrategyBucket;
import com.trading.position.Account;
import com.trading.position.PositionManager;
import com.trading.risk.SleeveDrawdownGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 칸별 낙폭 상한 조회·해제 API (ADR-001 §2.2 개정 2026-08-07). 앱은 127.0.0.1에서만 받는다(paper yml).
 *
 * <pre>
 * GET  /api/buckets/sleeve-status        칸마다 자산·최고 기록·낙폭·한도·잠김·측정 불가 개수
 * POST /api/buckets/{bucket}/unlock      잠금 해제 — body {"reason":"..."} 필수 (사람만)
 * </pre>
 *
 * <p>해제하면 그 칸의 최고 기록을 <b>지금 칸 자산</b>으로 다시 잡는다 — 안 그러면 풀자마자 다시 잠긴다.
 * 칸 자산을 정확히 모르면(보유가 남았는데 잔고가 낡음, 오늘 판 거래의 매도가를 아직 모름) 해제하지 않는다 —
 * 틀린 값을 새 최고 기록으로 박으면 그 뒤의 낙폭이 전부 틀어진다.
 */
@RestController
@RequestMapping("/api/buckets")
@Profile("paper")
public class SleeveController {

    private static final Logger log = LoggerFactory.getLogger(SleeveController.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    static final int MAX_REASON_LENGTH = 200;

    static final String WARNING =
            "실현손익 일부는 추정치입니다 — 증권사 모의계좌가 매도 체결가를 주지 않아(CLAUDE.md 결함 5) "
            + "그날 분봉 종가로 추정합니다. 가격을 못 구한 거래는 0원으로 넣고 개수(unmeasurableTrades)로 알립니다";

    private final BucketProperties buckets;
    private final SleeveDrawdownProperties limits;
    private final SleeveStateStore store;
    private final SleeveEquityCalculator calculator;
    private final SleeveDrawdownGuard guard;
    private final PositionManager positionManager;
    private final SleeveRealizedSource realizedSource;

    public SleeveController(BucketProperties buckets, SleeveDrawdownProperties limits, SleeveStateStore store,
                            SleeveEquityCalculator calculator, SleeveDrawdownGuard guard,
                            PositionManager positionManager, SleeveRealizedSource realizedSource) {
        this.buckets = buckets;
        this.limits = limits;
        this.store = store;
        this.calculator = calculator;
        this.guard = guard;
        this.positionManager = positionManager;
        this.realizedSource = realizedSource;
    }

    @GetMapping("/sleeve-status")
    public Map<String, Object> status() {
        Account account = snapshotQuietly();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", buckets.isEnabled());
        m.put("estimated", true);
        m.put("warning", WARNING);
        m.put("snapshotFresh", account != null && account.isFresh());
        m.put("sleeves", Arrays.stream(StrategyBucket.values()).map(b -> row(b, account)).toList());
        return m;
    }

    @PostMapping("/{bucket}/unlock")
    public Map<String, Object> unlock(@PathVariable String bucket,
                                      @RequestBody(required = false) Map<String, String> body) {
        StrategyBucket target = parse(bucket);
        if (target == null) return fail("알 수 없는 칸입니다: " + bucket + " (TREND·VB·EVENT·MIX 중 하나)");
        String reason = cleanReason(body == null ? null : body.get("reason"));
        if (reason == null) {
            return fail("사유를 적어야 합니다 — body에 {\"reason\":\"왜 푸는지\"} (" + MAX_REASON_LENGTH + "자 이하)");
        }
        if (!store.isLocked(target)) return fail(target + " 칸은 잠겨 있지 않습니다 — 해제할 것이 없습니다");

        // 새 최고 기록이 될 값이라 직전 감시 회차의 조회를 다시 쓰지 않는다(46b N-2) — 해제 직전 반영된 체결까지 넣는다.
        // 잔고를 먼저 읽고 나서 새로 읽기를 요청한다: 잔고 조회(최대 약 1초) 사이에 감시 회차가 요청을 먼저 써 버리면
        // 실현손익은 옛 값·보유는 새 값으로 어긋나기 때문이다(46b N-2′)
        Account account = snapshotQuietly();
        realizedSource.refreshOnNextCall();
        SleeveEquityCalculator.Result now = calculator.compute(target, account);
        if (now.isDeferred()) {
            return fail("지금은 칸 자산을 정확히 계산할 수 없어 해제하지 않았습니다 — " + now.deferredReason()
                    + ". 잠시 뒤 다시 시도하세요(오늘 판 거래가 있으면 15:50 이후나 다음 거래일)");
        }
        if (!guard.unlock(now.equity(), reason)) {
            return fail(target + " 칸은 잠겨 있지 않습니다 — 해제할 것이 없습니다");
        }
        Map<String, Object> m = result(true, target.getDisplayName()
                + " 칸 잠금을 풀었습니다 — 최고 기록을 지금 칸 자산으로 다시 잡았습니다");
        m.put("bucket", target.name());
        m.put("newPeak", Math.round(now.equity().equity()));
        return m;
    }

    // ── 조회 조립 ────────────────────────────────────────────────────────────

    private Map<String, Object> row(StrategyBucket bucket, Account account) {
        double allocation = buckets.allocationOf(bucket);
        boolean active = buckets.isBucketActive(bucket) && allocation > 0;
        SleeveStateStore.LockState lock = store.lockState(bucket);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("bucket", bucket.name());
        r.put("displayName", bucket.getDisplayName());
        r.put("active", active);
        r.put("allocation", Math.round(allocation));
        r.put("limitPercent", percent(limits.limitOf(bucket)));
        r.put("locked", lock.locked());
        r.put("lockedAt", lock.locked() && lock.lockedAt() != null
                ? LocalDateTime.ofInstant(lock.lockedAt(), KST).toString() : null);
        r.put("lockDrawdownPercent", lock.locked() ? percent(lock.drawdown()) : null);
        if (active || lock.locked()) r.putAll(equityFields(bucket, allocation, account));
        return r;
    }

    private Map<String, Object> equityFields(StrategyBucket bucket, double allocation, Account account) {
        Map<String, Object> f = new LinkedHashMap<>();
        double peak = guard.displayPeak(bucket, allocation);
        f.put("peak", Math.round(peak));
        f.put("peakRecorded", store.peak(bucket).isPresent());

        SleeveEquityCalculator.Result result = calculator.compute(bucket, account);
        if (result.isDeferred()) {
            f.put("equity", null);
            f.put("drawdownPercent", null);
            f.put("deferredReason", result.deferredReason());
            return f;
        }
        SleeveEquity e = result.equity();
        f.put("equity", Math.round(e.equity()));
        f.put("realizedPnl", Math.round(e.realized().pnl()));
        f.put("unrealizedPnl", Math.round(e.unrealized()));
        f.put("drawdownPercent", peak > 0 ? percent((peak - e.equity()) / peak) : null);
        f.put("recordedTrades", e.realized().recordedCount());
        f.put("estimatedTrades", e.realized().estimatedCount());
        f.put("unmeasurableTrades", e.realized().unmeasurableCount());
        f.put("deferredReason", null);
        return f;
    }

    // ── 입력 검증·공통 ───────────────────────────────────────────────────────

    private static StrategyBucket parse(String bucket) {
        if (bucket == null) return null;
        try {
            return StrategyBucket.valueOf(bucket.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 비었거나 너무 길면 null. 줄바꿈은 공백으로 — 로그 한 줄을 여러 줄로 위조하지 못하게 */
    private static String cleanReason(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String reason = raw.trim().replaceAll("[\\r\\n]+", " ");
        return reason.length() > MAX_REASON_LENGTH ? null : reason;
    }

    private Account snapshotQuietly() {
        try {
            return positionManager.snapshotAccount();
        } catch (Exception e) {
            log.warn("[칸 조회] 잔고 스냅샷 실패 — 보유가 있는 칸은 계산을 보류한다: {}", e.getMessage());
            return null;
        }
    }

    private static double percent(double ratio) {
        return Math.round(ratio * 10_000) / 100.0;
    }

    private static Map<String, Object> fail(String message) {
        return result(false, message);
    }

    private static Map<String, Object> result(boolean success, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", success);
        m.put("message", message);
        return m;
    }
}
