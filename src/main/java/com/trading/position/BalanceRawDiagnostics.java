package com.trading.position;

import com.trading.position.BalanceClient.BalanceSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import static com.trading.position.KisBalanceRaw.DEPOSIT;
import static com.trading.position.KisBalanceRaw.SECURITIES_EVALUATION;
import static com.trading.position.KisBalanceRaw.SETTLED_CASH;
import static com.trading.position.KisBalanceRaw.TOTAL_EVALUATION;

/**
 * 잔고 응답의 원래 숫자를 남기는 진단 기록기 — <b>기록 전용, 동작은 바꾸지 않는다</b> (결함 6, 2026-10-11).
 *
 * <p>총자산이 가끔 튀는데 증권사가 보낸 원래 숫자가 한 번도 남지 않아 원인을 몰랐다. 그래서:
 * <ol>
 *   <li><b>하루 1회 기준선 INFO</b> — 그날(KST) 첫 성공 조회의 tot_evlu_amt·prvs_rcdl_excc_amt·dnca_tot_amt·
 *       scts_evlu_amt·Σ수량×현재가·차이%. 대조 허용오차 1%가 맞는지 1주일간 확인하는 근거다.</li>
 *   <li><b>불일치 WARN</b> — 증권사 총자산이 "D+2 정산 + Σ수량×현재가"와 1% 넘게 어긋날 때</li>
 *   <li><b>급변 WARN</b> — 직전 성공 조회 대비 총자산이 ±2% 이상 변했을 때(식이 맞아도)</li>
 * </ol>
 * WARN에는 계산값·증권사값·직전 성공 총자산과 그 시각, 그리고 원래 숫자 전부({@link KisBalanceRaw#describe()})를 싣는다.
 * 각 경고는 처음 1회 + 이어지면 10분에 최대 1회이고 생략 건수를 적는다. 두 경고가 함께 나면 한 줄로 합친다.
 *
 * <p>잔고 조회는 여러 스레드(감시 루프·체결 확인·대조기)에서 오므로 기록은 통째로 잠근다.
 * 스프링 빈이 아니다 — {@code KisBalanceClient}가 직접 만든다(paper 전용).
 */
final class BalanceRawDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(BalanceRawDiagnostics.class);

    /** 직전 성공 조회 대비 이만큼(±2%) 변하면 원래 숫자를 남긴다 */
    static final double JUMP_THRESHOLD = 0.02;

    /** 같은 종류의 경고는 처음 1회 + 이어지면 이 간격마다 최대 1회 */
    static final Duration WARN_INTERVAL = Duration.ofMinutes(10);

    private static final DateTimeFormatter AT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Clock clock;
    private final LogThrottle mismatchThrottle;
    private final LogThrottle jumpThrottle;

    private LocalDate baselineLoggedOn;
    /** 직전 성공 조회의 총자산과 시각 — 시각이 null이면 아직 성공 이력이 없다 */
    private double lastTotal;
    private LocalDateTime lastAt;

    BalanceRawDiagnostics(Clock clock) {
        this.clock = clock;
        this.mismatchThrottle = new LogThrottle(clock, WARN_INTERVAL);
        this.jumpThrottle = new LogThrottle(clock, WARN_INTERVAL);
    }

    /** 성공한 잔고 조회 1건을 기록한다 — 실패한 조회는 여기 오지 않는다 */
    synchronized void record(KisBalanceRaw raw, BalanceSnapshot snapshot) {
        LocalDateTime now = LocalDateTime.now(clock);
        logBaselineOncePerDay(now.toLocalDate(), raw, snapshot);
        warnIfSuspicious(raw, snapshot);
        lastTotal = snapshot.totalAssetValue();
        lastAt = now;
    }

    private void logBaselineOncePerDay(LocalDate today, KisBalanceRaw raw, BalanceSnapshot snapshot) {
        if (today.equals(baselineLoggedOn)) return;
        baselineLoggedOn = today;
        EquityCrossCheck check = snapshot.equityCheck();
        log.info("[잔고기준선] {} 첫 조회 — tot_evlu_amt={} prvs_rcdl_excc_amt={} dnca_tot_amt={} scts_evlu_amt={} "
                        + "보유평가(수량×현재가)={} 계산값={} 차이={} 판정={}",
                today, text(raw, TOTAL_EVALUATION), text(raw, SETTLED_CASH), text(raw, DEPOSIT),
                text(raw, SECURITIES_EVALUATION), won(EquityCrossCheck.holdingsValue(snapshot.holdings())),
                computedText(check), diffText(check), check.verdict());
    }

    private void warnIfSuspicious(KisBalanceRaw raw, BalanceSnapshot snapshot) {
        EquityCrossCheck check = snapshot.equityCheck();
        List<String> reasons = new ArrayList<>();
        if (check.isMismatch()) {
            mismatchThrottle.tryAcquire().ifPresent(skipped -> reasons.add(String.format(
                    "증권사 총자산과 계산값 불일치(허용 %.0f%%)%s",
                    EquityCrossCheck.TOLERANCE * 100, LogThrottle.skippedNote(skipped))));
        }
        OptionalDouble jump = jumpRatio(snapshot.totalAssetValue());
        if (jump.isPresent() && Math.abs(jump.getAsDouble()) >= JUMP_THRESHOLD) {
            jumpThrottle.tryAcquire().ifPresent(skipped -> reasons.add(String.format(
                    "직전 성공 대비 총자산 급변 %+.2f%%%s", jump.getAsDouble() * 100, LogThrottle.skippedNote(skipped))));
        }
        if (reasons.isEmpty()) return;

        log.warn("[잔고원본] {} — 증권사 총자산={} 계산값={} (D+2 정산={} + 보유평가={}) 차이={} 판정={} / 직전 성공={} @ {} | {}",
                String.join(" + ", reasons), won(snapshot.totalAssetValue()), computedText(check),
                text(raw, SETTLED_CASH), won(EquityCrossCheck.holdingsValue(snapshot.holdings())), diffText(check),
                check.verdict(), lastAt == null ? "(없음)" : won(lastTotal),
                lastAt == null ? "(없음)" : AT_FMT.format(lastAt), raw.describe());
    }

    /** 직전 성공 대비 변화율 — 직전이 없거나 0 이하면 잴 수 없다 */
    private OptionalDouble jumpRatio(double current) {
        if (lastAt == null || lastTotal <= 0) return OptionalDouble.empty();
        return OptionalDouble.of((current - lastTotal) / lastTotal);
    }

    private static String text(KisBalanceRaw raw, String key) {
        String value = raw.value(key);
        return value == null || value.isBlank() ? "(없음)" : value;
    }

    private static String computedText(EquityCrossCheck check) {
        return check.verdict() == EquityCrossCheck.Verdict.UNCHECKED ? "(판정 불가)" : won(check.computedTotal());
    }

    private static String diffText(EquityCrossCheck check) {
        if (check.verdict() == EquityCrossCheck.Verdict.UNCHECKED) return "판정 불가";
        return String.format("%+.3f%%", check.diffRatio() * 100);
    }

    private static String won(double value) {
        return String.format("%.0f", value);
    }
}
