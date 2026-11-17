package com.trading.order;

import com.trading.market.MarketCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 타임아웃 주문의 취소 시도 관문 — <b>성공할 수 없는 호출은 하지 않는다</b>.
 *
 * <p><b>왜 생겼나 (2026-09-22 실측).</b> 15:24:04에 낸 주문이 체결되지 않아 FillProcessor가
 * 취소를 시도했는데 KIS가 {@code 모의투자 장종료 입니다.}로 거부했다. 포기 조건이 없어
 * 15:34부터 자정까지 <b>5,983회</b>(시간당 604~798회) 재시도했다. 장이 끝난 뒤의 취소는
 * 절대 성공할 수 없으므로 전부 헛호출이었다. 거부 자체는 HTTP 200(rt_cd=1)이라 실패로
 * 세지 않지만, 그 헛호출 중 <b>231회가 장외 KIS에서 HTTP 500을 받아</b> 재시도 사다리
 * (1s·2s·4s)를 타고 KisApiClient의 연속 실패 카운터를 올렸다(연속 3회면 SAFE_MODE) —
 * 그 결과 장외 실패 건수(시간당 40~62)가 장중(11~39)보다 많아져 아침을 SAFE_MODE 근처에서
 * 시작하게 만들었다. 루프가 멈춘 이유는 고쳐져서가 아니라 자정에 주문번호가 사라져 응답이
 * "원주문번호 없음"으로 바뀐 것뿐이라 조건이 같으면 재발한다.
 *
 * <p><b>두 겹으로 막는다.</b>
 * <ol>
 *   <li>우리 달력이 장외라고 하면 호출 자체를 하지 않는다 — 거부될 게 확실한 호출로
 *       레이트리밋 유량과 실패 카운터를 낭비하지 않는다.</li>
 *   <li>달력을 맹신하지 않는다(휴장일 목록에 추석이 빠져 있던 2026-09-15 사고). KIS가 스스로
 *       "장종료"라고 답하면 그 말을 진실로 받아 <b>그 날은</b> 그 주문의 취소를 더 시도하지 않는다.</li>
 * </ol>
 *
 * <p><b>막는 것은 취소 시도뿐 — 체결조회(폴링)는 계속된다.</b> 그 주문이 이미 체결됐다는 사실을
 * 나중에 알게 될 수 있고, 취소거부(잔량없음)=체결로 보는 desync 자동복구가 그 경로를 쓴다.
 * 그래서 주문 상태(OrderStatus)를 새로 만들거나 바꾸지 않고 <b>메모리로만</b> 기억한다 —
 * 상태머신을 늘리는 쪽이 더 위험하고, 재기동하면 기동 재동기화가 미체결을 처리한다
 * (OPERATIONS §3 ④).
 */
@Component
@Profile("paper")
public class CancelRetryGate {

    private static final Logger log = LoggerFactory.getLogger(CancelRetryGate.class);

    private final OrderCancelClient cancelClient;
    private final MarketCalendarService marketCalendar;
    private final Clock clock;

    /** 주문번호 → KIS가 "장종료"로 취소를 거부한 날(KST). 그 날 안에서는 재시도하지 않는다. */
    private final Map<String, LocalDate> marketClosedOn = new ConcurrentHashMap<>();

    public CancelRetryGate(OrderCancelClient cancelClient,
                           MarketCalendarService marketCalendar,
                           Clock clock) {
        this.cancelClient = cancelClient;
        this.marketCalendar = marketCalendar;
        this.clock = clock;
    }

    /**
     * 취소를 시도하고 결과를 돌려준다.
     *
     * @return 시도하지 않았으면 빈 값 — 호출 측은 주문 상태를 건드리지 않고 다음 폴에서
     *         체결조회를 계속하면 된다. 다음 개장 후 첫 폴에서 다시 시도된다.
     */
    public Optional<OrderCancelClient.CancelOutcome> attemptCancel(String orderNo) {
        LocalDate today = LocalDate.now(clock);

        if (today.equals(marketClosedOn.get(orderNo))) {
            // 이미 오늘 "장종료"를 받은 주문 — 로그도 남기지 않는다(그 소음이 문제의 일부였다)
            return Optional.empty();
        }
        if (!marketCalendar.isDuringMarketHoursNow()) {
            // 폴 주기(3초)마다 찍히면 그게 또 소음이다 — 운영 로그 레벨(INFO) 아래로 둔다
            log.debug("장외 — 취소 시도 보류(다음 개장에 재시도): ordNo={}", orderNo);
            return Optional.empty();
        }

        OrderCancelClient.CancelOutcome outcome = cancelClient.cancelAll(orderNo);
        if (outcome == OrderCancelClient.CancelOutcome.MARKET_CLOSED) {
            // 이 put 이후로는 위 첫 분기에서 조용히 걸러진다 → 이 WARN은 주문·날짜당 1회
            marketClosedOn.put(orderNo, today);
            log.warn("KIS가 장종료로 취소를 거부 — 오늘은 이 주문의 취소를 더 시도하지 않는다"
                    + "(체결조회는 계속, 다음 개장에 재시도): ordNo={}", orderNo);
        } else {
            // 상황이 바뀌었다(접수됨·이미체결·일시적 실패) — 낡은 기억을 들고 있지 않는다.
            // FAILED는 재시도로 성공할 수 있으므로 억제 대상이 아니다.
            marketClosedOn.remove(orderNo);
        }
        return Optional.of(outcome);
    }
}
