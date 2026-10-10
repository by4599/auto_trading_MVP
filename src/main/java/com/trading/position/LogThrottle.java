package com.trading.position;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;

/**
 * 경고 로그 문지기 — "처음 1회 + 이어지면 간격마다 최대 1회", 참은 횟수는 다음 기록에 붙인다 (2026-10-11).
 *
 * <p>잔고 조회는 장중 3초마다, 전고점 감시는 1초마다 돈다. 같은 이상이 이어질 때 매번 남기면 하루 수만 줄이 쌓여
 * 정작 첫 줄을 찾기 어렵다. 그렇다고 첫 줄만 남기면 "몇 번이었는지"를 잃는다 — 그래서 센다.
 * 시계는 주입받는다(테스트는 가짜 시계로 10분을 건너뛴다). 스프링 빈이 아니다 — 쓰는 쪽이 직접 만든다.
 */
final class LogThrottle {

    private final Clock clock;
    private final Duration interval;

    /** 마지막으로 남긴 시각 — null이면 아직 한 번도 안 남겼다 */
    private Instant lastEmittedAt;
    private int suppressed;

    LogThrottle(Clock clock, Duration interval) {
        this.clock = clock;
        this.interval = interval;
    }

    /**
     * @return 지금 남길 차례면 직전 기록 뒤 참은 횟수(0 이상), 참아야 하면 empty
     */
    synchronized OptionalInt tryAcquire() {
        Instant now = clock.instant();
        if (lastEmittedAt != null && now.isBefore(lastEmittedAt.plus(interval))) {
            suppressed++;
            return OptionalInt.empty();
        }
        int skipped = suppressed;
        suppressed = 0;
        lastEmittedAt = now;
        return OptionalInt.of(skipped);
    }

    /** 로그 끝에 붙일 생략 안내 — 참은 게 없으면 빈 문자열 */
    static String skippedNote(int skipped) {
        return skipped > 0 ? " (직전 기록 뒤 " + skipped + "회 생략)" : "";
    }
}
