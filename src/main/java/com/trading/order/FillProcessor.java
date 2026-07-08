package com.trading.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.market.KisApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 주문 1건의 체결 확인 오케스트레이터.
 *
 * 역할 분리:
 *   - 이 클래스      : HTTP I/O (체결조회), 취소는 OrderCancelClient 위임, 분기 결정
 *   - FillStateUpdater: DB I/O (@Transactional)
 *
 * @Transactional 미사용 — DB 커넥션을 HTTP 대기 중에 점유하지 않는다.
 */
@Component
@Profile("paper")
public class FillProcessor {

    private static final Logger log = LoggerFactory.getLogger(FillProcessor.class);

    private static final String TR_FILL    = "VTTC8001R";
    private static final String FILL_URI   = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld";

    private static final long TIMEOUT_MINUTES       = 10;
    private static final long CANCEL_TIMEOUT_HOURS  = 24;  // P3: CANCEL_REQUESTED 타임아웃

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final KisApiClient kisApiClient;
    private final FillStateUpdater stateUpdater;
    private final OrderCancelClient cancelClient;

    public FillProcessor(KisApiClient kisApiClient, FillStateUpdater stateUpdater,
                         OrderCancelClient cancelClient) {
        this.kisApiClient = kisApiClient;
        this.stateUpdater = stateUpdater;
        this.cancelClient = cancelClient;
    }

    public void process(Long orderId) {
        OrderSnapshot snapshot = stateUpdater.loadSnapshot(orderId);

        if (snapshot.status() == OrderStatus.CANCEL_REQUESTED) {
            processCancelRequested(snapshot);
            return;
        }

        processNormal(snapshot);
    }

    // ── CANCEL_REQUESTED ────────────────────────────────────────────────────

    /**
     * [P3] 24시간 초과 시 CANCEL_FAILED로 전환 후 폴링 중단.
     * 미초과 시 최종 체결조회로 FILLED 또는 CANCELLED 확정.
     */
    private void processCancelRequested(OrderSnapshot snapshot) {
        LocalDateTime cancelTime = snapshot.cancelRequestedAt();
        if (cancelTime != null
                && cancelTime.plusHours(CANCEL_TIMEOUT_HOURS).isBefore(LocalDateTime.now())) {
            log.error("CANCEL_REQUESTED 24h 초과 — CANCEL_FAILED 전환: ordNo={} cancelRequestedAt={}",
                    snapshot.orderNo(), cancelTime);
            stateUpdater.markCancelFailed(snapshot.id());
            // 알림은 markCancelFailed() 내부의 CancelFailedEvent → AFTER_COMMIT → TradingEventListener
            return;
        }

        FillResult result = inquireFill(snapshot.orderNo());
        if (!result.isSuccess()) return;  // API 실패 → 다음 폴에서 재시도

        stateUpdater.finalizeAfterCancel(snapshot.id(), result.totalFilledQty(), result.avgPrice());
        // 전량 체결이면 finalizeAfterCancel() 내부의 OrderFilledEvent → AFTER_COMMIT → TradingEventListener
    }

    // ── ACCEPTED / PARTIAL_FILLED ────────────────────────────────────────────

    private void processNormal(OrderSnapshot snapshot) {
        FillResult result = inquireFill(snapshot.orderNo());

        // [P3] API 실패와 실제 미체결 구분 — 실패면 스킵, 다음 폴에서 재시도
        if (!result.isSuccess()) {
            log.warn("체결조회 API 실패 - 다음 폴에서 재시도: ordNo={}", snapshot.orderNo());
            return;
        }

        if (result.totalFilledQty() > snapshot.filledQuantity()) {
            boolean applied = stateUpdater.applyFill(
                    snapshot.id(), result.totalFilledQty(), result.avgPrice());

            if (!applied) {
                // P1 가드 발동 — 이미 FILLED/CANCELLED 등 터미널 상태.
                // DB 변경 없음이므로 알림 불가. 취소 시도도 불필요.
                return;
            }
            if (result.totalFilledQty() >= snapshot.quantity()) {
                // 알림은 applyFill() 내부의 OrderFilledEvent → AFTER_COMMIT → TradingEventListener
                return;
            }
        }

        // [중복 취소 방어] snapshot 상태가 ACCEPTED|PARTIAL_FILLED일 때만 취소 시도.
        // process()에서 CANCEL_REQUESTED는 이미 processCancelRequested()로 분기했으므로
        // 이 조건이 없어도 실질적으로 중복 취소는 불가하지만, 명시적 체크로 안전망 추가.
        boolean cancellable = snapshot.status() == OrderStatus.ACCEPTED
                || snapshot.status() == OrderStatus.PARTIAL_FILLED;

        if (cancellable && isTimedOut(snapshot)) {
            boolean sent = cancelClient.cancelAll(snapshot.orderNo());
            if (sent) {
                stateUpdater.markCancelRequested(snapshot.id());
            } else {
                log.warn("주문 취소 실패 - 폴링 계속: ordNo={} requestedAt={}",
                        snapshot.orderNo(), snapshot.requestedAt());
            }
        }
    }

    // ── KIS 체결조회 API (VTTC8001R) ────────────────────────────────────────

    /**
     * [P3] API 실패와 실제 미체결을 구분한다.
     *
     * FillQueryStatus.SUCCESS  + totalFilledQty > 0 : 체결 있음
     * FillQueryStatus.SUCCESS  + totalFilledQty = 0 : 미체결 (정상 응답)
     * FillQueryStatus.API_ERROR                     : 네트워크/KIS 오류 (다음 폴 재시도)
     */
    private FillResult inquireFill(String orderNo) {
        try {
            String today = LocalDate.now().format(DATE_FMT);
            String[] acnt = split(kisApiClient.getProps().getAccountNo());

            FillResponse resp = kisApiClient.getClient().get()
                    .uri(b -> b.path(FILL_URI)
                            .queryParam("CANO",            acnt[0])
                            .queryParam("ACNT_PRDT_CD",    acnt[1])
                            .queryParam("INQR_STRT_DT",    today)
                            .queryParam("INQR_END_DT",     today)
                            .queryParam("SLL_BUY_DVSN_CD", "00")
                            .queryParam("INQR_DVSN",       "00")
                            .queryParam("PDNO",             "")
                            .queryParam("CCLD_DVSN",       "00")
                            .queryParam("ORD_GNO_BRNO",    "")
                            .queryParam("ODNO",             orderNo)
                            .queryParam("INQR_DVSN_3",     "00")
                            .queryParam("INQR_DVSN_1",     "")
                            .queryParam("CTX_AREA_FK100",   "")
                            .queryParam("CTX_AREA_NK100",   "")
                            .build())
                    .header("tr_id",    TR_FILL)
                    .header("custtype", "P")
                    .retrieve()
                    .body(FillResponse.class);

            if (resp == null) return FillResult.apiError();

            if (!resp.isSuccess()) {
                log.error("체결조회 API 오류: rt_cd={} msg={} ordNo={}", resp.rtCd(), resp.msg1(), orderNo);
                return FillResult.apiError();
            }

            if (resp.output1() == null || resp.output1().isEmpty()) {
                return FillResult.notFilled();
            }

            // output1[0]을 사용하는 이유:
            // VTTC8001R 응답에서 tot_ccld_qty(총체결수량)는 모든 행에 동일한 누적 합계로 반환된다.
            // 복수 행은 체결 이벤트 이력이며, 첫 행(최신)의 tot_ccld_qty 만으로 충분하다.
            // KIS 공식 샘플도 output1[0]만 참조한다.
            FillItem item = resp.output1().get(0);
            return FillResult.filled(parseInt(item.totCcldQty()), parseDouble(item.avgPrvs()));

        } catch (Exception e) {
            log.error("체결조회 HTTP 실패: ordNo={}", orderNo, e);
            return FillResult.apiError();
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private boolean isTimedOut(OrderSnapshot snapshot) {
        return snapshot.requestedAt().plusMinutes(TIMEOUT_MINUTES).isBefore(LocalDateTime.now());
    }

    private String[] split(String accountNo) {
        if (accountNo.contains("-")) return accountNo.split("-", 2);
        if (accountNo.length() >= 10)
            return new String[]{accountNo.substring(0, 8), accountNo.substring(8)};
        throw new IllegalArgumentException("잘못된 account-no 형식: " + accountNo);
    }

    private static int    parseInt(String s)    { return (s == null || s.isBlank()) ? 0   : Integer.parseInt(s.trim()); }
    private static double parseDouble(String s) { return (s == null || s.isBlank()) ? 0.0 : Double.parseDouble(s.trim()); }

    // ── 내부 타입 ─────────────────────────────────────────────────────────────

    /** [P3] 체결조회 결과 — API 오류와 실제 미체결을 queryStatus로 구분한다. */
    enum FillQueryStatus { SUCCESS, API_ERROR }

    record FillResult(FillQueryStatus queryStatus, int totalFilledQty, double avgPrice) {
        static FillResult filled(int qty, double price) {
            return new FillResult(FillQueryStatus.SUCCESS, qty, price);
        }
        static FillResult notFilled() {
            return new FillResult(FillQueryStatus.SUCCESS, 0, 0.0);
        }
        static FillResult apiError() {
            return new FillResult(FillQueryStatus.API_ERROR, 0, 0.0);
        }
        boolean isSuccess() { return queryStatus == FillQueryStatus.SUCCESS; }
    }

    private record FillResponse(
            @JsonProperty("rt_cd")   String rtCd,
            @JsonProperty("msg1")    String msg1,
            @JsonProperty("output1") List<FillItem> output1
    ) {
        boolean isSuccess() { return "0".equals(rtCd); }
    }

    private record FillItem(
            @JsonProperty("odno")         String ordNo,
            @JsonProperty("tot_ccld_qty") String totCcldQty,
            @JsonProperty("avg_prvs")     String avgPrvs
    ) {}

}
