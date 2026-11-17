package com.trading.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주문 접수 실패 후 같은 종목 재시도를 잠시 막기 위한 상태 보관소 (2026-08-04).
 *
 * 실패한 주문은 장부에 ACCEPTED로 남지 않아 {@code PendingOrderRule}이 중복을 못 막는다.
 * 그 사이 전략은 매 틱 같은 신호를 내므로 실패가 계속되면 몇 분 간격으로 같은 매수를
 * 반복한다(실측: 034020이 08:48~08:59에 7회). 이 추적기가 실패 시각을 기억하고
 * {@link com.trading.risk.OrderFailureCooldownRule}이 그동안 신규 매수를 차단한다.
 *
 * 실패를 두 종류로 나눈다 — 브로커가 <b>명시적으로 거부</b>한 경우(rt_cd≠0)는 주문이
 * 성립하지 않았음이 확실하므로 짧게 쉬고, <b>응답을 못 받은</b> 경우(타임아웃·빈 응답·
 * 파싱 불가)는 주문이 실제로 체결됐을 수 있으므로 길게 쉰다. 후자는 재동기화 주기가
 * 실제 보유를 흡수할 시간을 벌어주는 것이 목적이다.
 */
@Component
public class OrderFailureTracker {

    private static final Logger log = LoggerFactory.getLogger(OrderFailureTracker.class);

    private final Clock clock;
    private final Duration rejectedCooldown;
    private final Duration ambiguousCooldown;

    private final Map<String, Instant> blockedUntil = new ConcurrentHashMap<>();

    public OrderFailureTracker(
            Clock clock,
            @Value("${trading.order.rejected-cooldown-sec:60}") long rejectedCooldownSec,
            @Value("${trading.order.ambiguous-cooldown-sec:600}") long ambiguousCooldownSec) {
        this.clock = clock;
        this.rejectedCooldown = Duration.ofSeconds(rejectedCooldownSec);
        this.ambiguousCooldown = Duration.ofSeconds(ambiguousCooldownSec);
    }

    /** 브로커가 명시적으로 거부 — 주문 미성립이 확실하므로 짧게 쉰다 */
    public void recordRejected(String stockCode) {
        record(stockCode, rejectedCooldown, "거부");
    }

    /** 응답 불명 — 실제로 체결됐을 수 있으므로 재동기화가 흡수할 시간만큼 길게 쉰다 */
    public void recordAmbiguous(String stockCode) {
        record(stockCode, ambiguousCooldown, "응답불명");
    }

    private void record(String stockCode, Duration cooldown, String kind) {
        Instant until = Instant.now(clock).plus(cooldown);
        blockedUntil.merge(stockCode, until, (a, b) -> a.isAfter(b) ? a : b);
        log.warn("[OrderFailure] {} 주문 {} — {}초간 신규 매수 차단", stockCode, kind, cooldown.toSeconds());
    }

    public boolean isCoolingDown(String stockCode) {
        Instant until = blockedUntil.get(stockCode);
        if (until == null) return false;
        if (Instant.now(clock).isBefore(until)) return true;
        blockedUntil.remove(stockCode, until);
        return false;
    }

    /** 체결이 확인되면 남은 차단을 푼다 — 불확실성이 해소됐으므로 더 막을 이유가 없다 */
    public void clear(String stockCode) {
        blockedUntil.remove(stockCode);
    }
}
