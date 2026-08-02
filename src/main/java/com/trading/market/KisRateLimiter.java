package com.trading.market;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * KIS 초당 호출 한도 조정자 — 모든 KIS 호출을 하나의 초당 예산 아래 균등 간격으로 직렬화한다.
 *
 * KIS는 계좌 단위로 초당 호출을 제한한다(모의 2건/초, 실전 20건/초). 매매 루프·잔고 감시·
 * 체결 폴링·대시보드 시세가 각자 호출하면 순간적으로 한도를 넘겨 EGW00201("초당 거래건수
 * 초과")이 터지고, 그 여파로 SAFE_MODE 플래핑이 발생한다. 이 컴포넌트는 acquire()를 통해
 * 호출들을 한도 간격(1000/rate ms)만큼 벌려 흘려보내 한도를 절대 넘지 않게 한다.
 *
 * 균등 간격(leaky bucket) 방식이라 순간 버스트가 원천 차단된다 — KIS가 "초당" 단위로 세므로
 * 버스트를 허용하는 토큰버킷보다 안전하다. rate는 `kis.rate-limit-per-sec`(기본 2=모의)로
 * 조정한다(실전 전환 시 20).
 *
 * 우선순위(매매 > 체결/잔고 > 대시보드)는 v2 개선 과제 — v1은 FIFO 균등 배분으로
 * 한도 초과 자체를 없애는 데 집중한다.
 */
@Component
public class KisRateLimiter {

    private final long intervalNanos;
    private final Object gate = new Object();
    private long nextSlotNanos = System.nanoTime();

    public KisRateLimiter(@Value("${kis.rate-limit-per-sec:2}") int ratePerSec) {
        this.intervalNanos = 1_000_000_000L / Math.max(1, ratePerSec);
    }

    /** 다음 호출 슬롯이 될 때까지 현재 스레드를 대기시킨다. 여러 스레드가 호출하면 순서대로 슬롯을 받는다. */
    public void acquire() {
        long waitNanos;
        synchronized (gate) {
            long now = System.nanoTime();
            long slot = Math.max(now, nextSlotNanos);
            nextSlotNanos = slot + intervalNanos;
            waitNanos = slot - now;
        }
        if (waitNanos > 0) {
            try {
                Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
