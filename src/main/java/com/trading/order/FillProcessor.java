package com.trading.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.market.KisApiClient;
import com.trading.position.BalanceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** 폴 주기(3초)보다 짧게 — 같은 주기의 주문들만 한 응답을 공유하게 한다 */
    private static final Duration SNAPSHOT_TTL = Duration.ofMillis(2500);

    private volatile DailyFills cachedFills;
    /** 건수가 바뀔 때만 로그 — 3초마다 같은 줄을 찍지 않으려는 것 */
    private volatile int lastReportedFillCount = -1;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final KisApiClient kisApiClient;
    private final FillStateUpdater stateUpdater;
    private final OrderCancelClient cancelClient;
    private final BalanceClient balanceClient;

    public FillProcessor(KisApiClient kisApiClient, FillStateUpdater stateUpdater,
                         OrderCancelClient cancelClient, BalanceClient balanceClient) {
        this.kisApiClient = kisApiClient;
        this.stateUpdater = stateUpdater;
        this.cancelClient = cancelClient;
        this.balanceClient = balanceClient;
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
            switch (cancelClient.cancelAll(snapshot.orderNo())) {
                case SENT -> stateUpdater.markCancelRequested(snapshot.id());
                // 취소할 잔량 없음 = 이미 체결됨. 체결조회가 놓친 체결을 실잔고로 대사해 종결한다.
                case NO_OPEN_QTY -> resolveFilledByBalance(snapshot);
                case FAILED -> log.warn("주문 취소 실패 - 폴링 계속: ordNo={} requestedAt={}",
                        snapshot.orderNo(), snapshot.requestedAt());
            }
        }
    }

    /**
     * 취소가 "잔량 없음"으로 거부된 주문 처리 — 그 주문은 브로커에서 이미 체결(종료)됐다.
     * 체결조회(VTTC8001R)가 이 체결을 빈 응답으로 놓치므로, 실제 잔고를 진실로 삼아
     * 포지션을 정렬하고 주문을 종결해 무한 폴링을 끊는다.
     * 잔고 조회 실패 시에는 종결하지 않고 다음 폴에서 재시도한다.
     */
    private void resolveFilledByBalance(OrderSnapshot snapshot) {
        int brokerQty;
        double brokerAvg;
        try {
            BalanceClient.Holding h = balanceClient.fetchBalance().holdings().stream()
                    .filter(x -> x.stockCode().equals(snapshot.stockCode()))
                    .findFirst().orElse(null);
            brokerQty = h == null ? 0    : h.quantity();
            brokerAvg = h == null ? 0.0  : h.averagePrice();
        } catch (Exception e) {
            log.warn("[체결 대사] 잔고 조회 실패 - 다음 폴 재시도: ordNo={}", snapshot.orderNo());
            return;
        }
        stateUpdater.reconcileFilledFromBalance(snapshot.id(), brokerQty, brokerAvg);
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
        DailyFills fills = dailyFills();
        if (fills.apiError()) return FillResult.apiError();
        return fills.byOrderNo().getOrDefault(orderNo, FillResult.notFilled());
    }

    /**
     * 당일 체결 내역을 <b>주문번호 필터 없이</b> 한 번 받아 주문번호로 색인한다.
     *
     * <p>바뀐 이유 둘:
     * <ol>
     *   <li><b>ODNO 필터가 체결을 놓쳤다.</b> 주문번호로 조회하면 실제 체결된 주문에도
     *       {@code rt_cd=0 · output1=[]}가 돌아왔다(2026-07-28~30 진단). 그 결과 매도 체결
     *       38건 중 37건이 체결가 없이 종결됐고(2026-08-07 실측), 실현손익·슬리피지 측정이
     *       모두 막혔다. 필터를 빼고 당일 전체를 받아 앱에서 매칭한다.</li>
     *   <li><b>호출 수.</b> 예전에는 대기 주문 N개마다 3초 주기로 N번 호출해 모의 유량
     *       (1건/초)을 통째로 먹었다. 이제 주기당 1번이면 된다.</li>
     * </ol>
     *
     * <p>스냅샷은 짧게(폴 주기보다 조금 짧게) 재사용한다 — 같은 주기 안의 주문들이
     * 같은 응답을 공유하게 하려는 것이지 캐싱이 목적이 아니다.
     */
    private DailyFills dailyFills() {
        DailyFills snapshot = cachedFills;
        if (snapshot != null && Instant.now().isBefore(snapshot.expiresAt())) {
            return snapshot;
        }
        DailyFills fresh = fetchDailyFills();
        cachedFills = fresh;
        return fresh;
    }

    private DailyFills fetchDailyFills() {
        Instant expiry = Instant.now().plus(SNAPSHOT_TTL);
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
                            .queryParam("ODNO",             "")
                            .queryParam("INQR_DVSN_3",     "00")
                            .queryParam("INQR_DVSN_1",     "")
                            .queryParam("CTX_AREA_FK100",   "")
                            .queryParam("CTX_AREA_NK100",   "")
                            .build())
                    .header("tr_id",    TR_FILL)
                    .header("custtype", "P")
                    .retrieve()
                    .body(FillResponse.class);

            if (resp == null) return DailyFills.error(expiry);
            if (!resp.isSuccess()) {
                log.error("체결조회 API 오류: rt_cd={} msg={}", resp.rtCd(), resp.msg1());
                return DailyFills.error(expiry);
            }
            Map<String, FillResult> indexed = index(resp.output1());
            if (indexed.size() != lastReportedFillCount) {
                lastReportedFillCount = indexed.size();
                log.info("[체결조회] 당일 체결 {}건 (주문번호 {})", indexed.size(), indexed.keySet());
            }
            return DailyFills.of(expiry, indexed);

        } catch (Exception e) {
            log.error("체결조회 HTTP 실패 — 당일 전체 조회", e);
            return DailyFills.error(expiry);
        }
    }

    /**
     * 응답 행들을 주문번호별로 접는다. tot_ccld_qty는 행마다 같은 누적값이라 첫 행이면
     * 충분하지만, 안전하게 가장 큰 값을 취한다(부분 체결 이력이 섞여 와도 누적이 줄지 않게).
     */
    private Map<String, FillResult> index(List<FillItem> rows) {
        Map<String, FillResult> byOrderNo = new HashMap<>();
        if (rows == null) return byOrderNo;
        for (FillItem item : rows) {
            if (item.ordNo() == null || item.ordNo().isBlank()) continue;
            int qty = parseInt(item.totCcldQty());
            double price = parseDouble(item.avgPrvs());
            byOrderNo.merge(item.ordNo(), FillResult.filled(qty, price),
                    (a, b) -> a.totalFilledQty() >= b.totalFilledQty() ? a : b);
        }
        return byOrderNo;
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

    /** 당일 체결 스냅샷 — 폴 주기 안의 주문들이 한 번의 응답을 공유한다 */
    private record DailyFills(Instant expiresAt, Map<String, FillResult> byOrderNo, boolean apiError) {
        static DailyFills of(Instant expiresAt, Map<String, FillResult> byOrderNo) {
            return new DailyFills(expiresAt, byOrderNo, false);
        }
        static DailyFills error(Instant expiresAt) {
            return new DailyFills(expiresAt, Map.of(), true);
        }
    }

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
