package com.trading.market;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * KIS 초당 호출 한도 조정자 — 모든 KIS 호출을 하나의 초당 예산 아래 균등 간격으로 직렬화한다.
 *
 * KIS는 <b>계좌 단위</b>로 초당 호출을 제한한다. 매매 루프·잔고 감시·체결 폴링·대시보드
 * 시세가 각자 호출하면 순간적으로 한도를 넘겨 EGW00201("초당 거래건수 초과")이 터지고,
 * 그 여파로 SAFE_MODE 플래핑이 발생한다. 이 컴포넌트는 acquire()를 통해 호출들을
 * 한도 간격(1000/rate ms)만큼 벌려 흘려보내 한도를 절대 넘지 않게 한다.
 *
 * <b>공식 한도 (KIS 공지 "API 호출 유량 안내", 2026.04.20 기준 — 2026-08-04 확인):</b>
 *   모의투자 <b>1건/초</b>(이전 2건) · 실전투자 <b>18건/초</b>(이전 20건) · 토큰발급 1건/초.
 *   기본값 2로 두었던 동안 모의 한도의 2배로 호출해 EGW00201·"모의투자 서비스가 지연되고
 *   있습니다"·주문 접수 실패(2026-08-04: 42건 중 25건 실패)를 자초했다.
 *   신규 고객 3일간 3건/초 제한은 모의투자 계좌에는 적용되지 않는다(같은 공지).
 *
 * 균등 간격(leaky bucket) 방식이라 순간 버스트가 원천 차단된다 — KIS가 "초당" 단위로 세므로
 * 버스트를 허용하는 토큰버킷보다 안전하다. rate는 `kis.rate-limit-per-sec`로 조정한다
 * (기본 1=모의, 실전 전환 시 18).
 *
 * 우선순위(매매 > 체결/잔고 > 대시보드)는 v2 개선 과제 — v1은 FIFO 균등 배분으로
 * 한도 초과 자체를 없애는 데 집중한다.
 */
@Component
public class KisRateLimiter {

    private final long intervalNanos;
    private final Object gate = new Object();
    private long nextSlotNanos = System.nanoTime();

    public KisRateLimiter(@Value("${kis.rate-limit-per-sec:1}") int ratePerSec) {
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
