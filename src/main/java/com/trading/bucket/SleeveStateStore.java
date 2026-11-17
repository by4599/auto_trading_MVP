package com.trading.bucket;

import com.trading.position.PortfolioState;
import com.trading.position.PortfolioStateRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 칸별 낙폭 상한의 영속 상태 — portfolio_state(키 → 숫자 한 칸)에 칸마다 몇 줄씩 남긴다 (2026-10-10).
 *
 * <p>재시작해도 남아야 하는 것: 칸 최고 기록(리셋 없음), 잠금(사람이 풀 때까지), 굳힌 실현손익.
 * 표가 숫자만 담으므로 사유는 숫자로 남긴다(잠금 시각 epoch ms · 낙폭 · 칸 자산 · 한도), 날짜는 yyyyMMdd.
 * 여러 줄이 함께 바뀌는 쓰기는 {@code saveAll} 한 번으로 묶는다(Spring Data의 saveAll은 트랜잭션 하나) —
 * "잠금 표시만 있고 시각은 없는" 반쪽 상태가 남지 않게 하려는 것이다.
 *
 * <p>잠금 룰({@code SleeveLockRule}, paper·real 공통)이 읽어야 하므로 백테스트에서만 빠진다.
 */
@Component
@Profile("!backtest")
public class SleeveStateStore {

    private static final String PREFIX = "SLEEVE_";
    private static final String PEAK = "PEAK";
    private static final String PEAK_BASIS = "PEAK_BASIS";
    private static final String LOCKED = "LOCKED";
    private static final String LOCKED_AT = "LOCKED_AT";
    private static final String LOCK_DRAWDOWN = "LOCK_DD";
    private static final String LOCK_EQUITY = "LOCK_EQUITY";
    private static final String LOCK_LIMIT = "LOCK_LIMIT";
    private static final String FROZEN_UNTIL = "FROZEN_UNTIL";
    private static final String FROZEN_PNL = "FROZEN_PNL";
    private static final String FROZEN_RECORDED = "FROZEN_RECORDED";
    private static final String FROZEN_ESTIMATED = "FROZEN_ESTIMATED";
    private static final String FROZEN_UNMEASURABLE = "FROZEN_UNMEAS";

    /**
     * 칸 최고 기록. basis = 그 기록을 잡을 때의 배분금 — 배분금 설정이 바뀌면 같은 금액만큼 옮기는 데 쓴다.
     * 사람이 DB에 최고 기록만 넣은 경우처럼 basis를 모르면 NaN이다.
     */
    public record Peak(double value, double basis) {}

    /** 잠금 상태와 그 사유(숫자). 잠기지 않았으면 나머지 값은 의미가 없다 */
    public record LockState(boolean locked, Instant lockedAt, double drawdown, double equity, double limit) {
        static LockState unlocked() {
            return new LockState(false, null, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    /** until(이 날 전까지 판 거래)은 이미 굳혔다 — 그 합계가 frozen */
    public record Checkpoint(LocalDate until, SleeveRealized frozen) {}

    private final PortfolioStateRepository repository;

    public SleeveStateStore(PortfolioStateRepository repository) {
        this.repository = repository;
    }

    public static String key(String field, StrategyBucket bucket) {
        return PREFIX + field + "_" + bucket.name();
    }

    // ── 최고 기록 ─────────────────────────────────────────────────────────────

    public Optional<Peak> peak(StrategyBucket bucket) {
        Map<String, Double> v = read(bucket, PEAK, PEAK_BASIS);
        Double value = v.get(key(PEAK, bucket));
        if (value == null) return Optional.empty();
        return Optional.of(new Peak(value, v.getOrDefault(key(PEAK_BASIS, bucket), Double.NaN)));
    }

    public void savePeak(StrategyBucket bucket, Peak peak) {
        repository.saveAll(List.of(
                state(PEAK, bucket, peak.value()),
                state(PEAK_BASIS, bucket, peak.basis())));
    }

    // ── 잠금 ─────────────────────────────────────────────────────────────────

    public LockState lockState(StrategyBucket bucket) {
        Map<String, Double> v = read(bucket, LOCKED, LOCKED_AT, LOCK_DRAWDOWN, LOCK_EQUITY, LOCK_LIMIT);
        if (v.getOrDefault(key(LOCKED, bucket), 0.0) < 0.5) return LockState.unlocked();
        Double at = v.get(key(LOCKED_AT, bucket));
        return new LockState(true,
                at == null ? null : Instant.ofEpochMilli(at.longValue()),
                v.getOrDefault(key(LOCK_DRAWDOWN, bucket), Double.NaN),
                v.getOrDefault(key(LOCK_EQUITY, bucket), Double.NaN),
                v.getOrDefault(key(LOCK_LIMIT, bucket), Double.NaN));
    }

    public boolean isLocked(StrategyBucket bucket) {
        return lockState(bucket).locked();
    }

    public void lock(StrategyBucket bucket, LockState s) {
        repository.saveAll(List.of(
                state(LOCKED, bucket, 1),
                state(LOCKED_AT, bucket, s.lockedAt().toEpochMilli()),
                state(LOCK_DRAWDOWN, bucket, s.drawdown()),
                state(LOCK_EQUITY, bucket, s.equity()),
                state(LOCK_LIMIT, bucket, s.limit())));
    }

    /** 잠금을 풀고 최고 기록을 다시 잡는다 — 마지막 잠금의 사유 숫자는 기록으로 남겨 둔다 */
    public void unlock(StrategyBucket bucket, Peak resetPeak) {
        repository.saveAll(List.of(
                state(LOCKED, bucket, 0),
                state(PEAK, bucket, resetPeak.value()),
                state(PEAK_BASIS, bucket, resetPeak.basis())));
    }

    // ── 굳힌 실현손익 ─────────────────────────────────────────────────────────

    public Checkpoint checkpoint(StrategyBucket bucket, LocalDate defaultUntil) {
        Map<String, Double> v = read(bucket, FROZEN_UNTIL, FROZEN_PNL,
                FROZEN_RECORDED, FROZEN_ESTIMATED, FROZEN_UNMEASURABLE);
        Double until = v.get(key(FROZEN_UNTIL, bucket));
        if (until == null) return new Checkpoint(defaultUntil, SleeveRealized.none());
        return new Checkpoint(fromNumber(until), new SleeveRealized(
                v.getOrDefault(key(FROZEN_PNL, bucket), 0.0),
                count(v, FROZEN_RECORDED, bucket),
                count(v, FROZEN_ESTIMATED, bucket),
                count(v, FROZEN_UNMEASURABLE, bucket),
                null));
    }

    public void saveCheckpoint(StrategyBucket bucket, Checkpoint cp) {
        SleeveRealized f = cp.frozen();
        repository.saveAll(List.of(
                state(FROZEN_UNTIL, bucket, toNumber(cp.until())),
                state(FROZEN_PNL, bucket, f.pnl()),
                state(FROZEN_RECORDED, bucket, f.recordedCount()),
                state(FROZEN_ESTIMATED, bucket, f.estimatedCount()),
                state(FROZEN_UNMEASURABLE, bucket, f.unmeasurableCount())));
    }

    // ── 내부 ─────────────────────────────────────────────────────────────────

    private Map<String, Double> read(StrategyBucket bucket, String... fields) {
        List<String> keys = Arrays.stream(fields).map(f -> key(f, bucket)).toList();
        Map<String, Double> values = new HashMap<>();
        for (PortfolioState s : repository.findAllById(keys)) {
            values.put(s.getStateKey(), s.getStateValue());
        }
        return values;
    }

    private static PortfolioState state(String field, StrategyBucket bucket, double value) {
        return PortfolioState.of(key(field, bucket), value);
    }

    private static int count(Map<String, Double> v, String field, StrategyBucket bucket) {
        return v.getOrDefault(key(field, bucket), 0.0).intValue();
    }

    private static double toNumber(LocalDate date) {
        return Long.parseLong(date.format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    private static LocalDate fromNumber(double number) {
        return LocalDate.parse(String.valueOf((long) number), DateTimeFormatter.BASIC_ISO_DATE);
    }
}
