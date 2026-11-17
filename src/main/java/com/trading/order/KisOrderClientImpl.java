package com.trading.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.bucket.StrategyBucket;
import com.trading.market.KisApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 한국투자증권 주문 API(POST /uapi/domestic-stock/v1/trading/order-cash)를
 * 사용하는 KisOrderClient 모의투자 구현체.
 *
 * 주문 접수 성공 시 OrderHistory(ACCEPTED)를 저장한다.
 * 실제 체결 확인은 FillPoller가 주기적으로 수행한다.
 *
 * tr_id: VTTC0802U(매수) / VTTC0801U(매도), ORD_DVSN=01(시장가)
 */
@Component
@Profile("paper")
public class KisOrderClientImpl implements KisOrderClient {

    private static final Logger log = LoggerFactory.getLogger(KisOrderClientImpl.class);

    private static final String ORDER_URI  = "/uapi/domestic-stock/v1/trading/order-cash";
    private static final String TR_BUY     = "VTTC0802U";
    private static final String TR_SELL    = "VTTC0801U";
    private static final String ORD_DVSN   = "01";  // 시장가
    private static final int    ORD_QTY    = 1;      // v1 MVP: 1주 고정

    private final KisApiClient kisApiClient;
    private final OrderHistoryRepository orderHistoryRepository;
    private final OrderFailureTracker failureTracker;

    public KisOrderClientImpl(KisApiClient kisApiClient,
                              OrderHistoryRepository orderHistoryRepository,
                              OrderFailureTracker failureTracker) {
        this.kisApiClient = kisApiClient;
        this.orderHistoryRepository = orderHistoryRepository;
        this.failureTracker = failureTracker;
    }

    @Override
    public void buy(String stockCode) {
        placeOrder(TR_BUY, stockCode, OrderSide.BUY, ORD_QTY, null);
    }

    @Override
    public void buy(String stockCode, int quantity) {
        buy(stockCode, quantity, null);
    }

    @Override
    public void buy(String stockCode, int quantity, StrategyBucket bucket) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("매수 수량은 1 이상이어야 합니다: " + quantity);
        }
        placeOrder(TR_BUY, stockCode, OrderSide.BUY, quantity, bucket);
    }

    @Override
    public void sell(String stockCode) {
        placeOrder(TR_SELL, stockCode, OrderSide.SELL, ORD_QTY, null);
    }

    @Override
    public void sell(String stockCode, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("매도 수량은 1 이상이어야 합니다: " + quantity);
        }
        placeOrder(TR_SELL, stockCode, OrderSide.SELL, quantity, null);
    }

    private void placeOrder(String trId, String stockCode, OrderSide side, int quantity,
                            StrategyBucket bucket) {
        log.info("주문 요청: side={}, stockCode={}, qty={}", side, stockCode, quantity);

        String[] acnt = splitAccountNo(kisApiClient.getProps().getAccountNo());

        // 실패한 주문 시도는 예전에 아무 흔적도 남기지 않아, PendingOrderRule이 중복을 못 막고
        // 전략이 매 틱 같은 매수를 재시도했다(2026-08-04 실측). 이제 두 갈래로 나눠 기록한다:
        //   응답 불명(예외) = 실제 체결됐을 수 있음 → 길게 차단
        //   명시적 거부(rt_cd≠0) = 미성립 확실   → 짧게 차단
        OrderResponse resp;
        try {
            resp = kisApiClient.getClient().post()
                    .uri(ORDER_URI)
                    .header("tr_id",    trId)
                    .header("custtype", "P")
                    .body(Map.of(
                            "CANO",         acnt[0],
                            "ACNT_PRDT_CD", acnt[1],
                            "PDNO",         stockCode,
                            "ORD_DVSN",     ORD_DVSN,
                            "ORD_QTY",      String.valueOf(quantity),
                            "ORD_UNPR",     "0"
                    ))
                    .retrieve()
                    .body(OrderResponse.class);
        } catch (RuntimeException e) {
            recordFailure(stockCode, side, quantity, bucket, true);
            throw e;
        }

        if (resp == null) {
            recordFailure(stockCode, side, quantity, bucket, true);
            throw new IllegalStateException(side + " 주문 응답 없음: " + stockCode);
        }
        if (!"0".equals(resp.rtCd())) {
            recordFailure(stockCode, side, quantity, bucket, false);
            throw new IllegalStateException(
                    String.format("%s 주문 실패: stockCode=%s rtCd=%s msg=%s",
                            side, stockCode, resp.rtCd(), resp.msg1()));
        }

        // rt_cd==0 = 접수 성공. 체결 여부는 FillPoller가 확인한다.
        String ordNo = resp.ordNo();
        orderHistoryRepository.save(OrderHistory.accepted(stockCode, side, quantity, ordNo, bucket));
        log.info("주문 접수 완료 — 저장됨: side={} stockCode={} qty={} ordNo={} bucket={}",
                side, stockCode, quantity, ordNo, bucket);
    }

    /**
     * 접수 실패를 장부와 쿨다운에 남긴다. 기록 자체가 실패해도 원래 예외를 가리지 않는다.
     *
     * @param ambiguous 응답을 못 받아 실제 체결 여부를 모르는 경우 true (더 길게 차단)
     */
    private void recordFailure(String stockCode, OrderSide side, int quantity,
                               StrategyBucket bucket, boolean ambiguous) {
        try {
            orderHistoryRepository.save(OrderHistory.failed(stockCode, side, quantity, bucket));
            if (side == OrderSide.BUY) {
                if (ambiguous) failureTracker.recordAmbiguous(stockCode);
                else           failureTracker.recordRejected(stockCode);
            }
        } catch (RuntimeException e) {
            log.warn("[Order] 실패 기록 실패 — 무시하고 원래 오류를 올린다: {} {}", stockCode, e.getMessage());
        }
    }

    /**
     * "50000000-01" → ["50000000","01"], "5000000001" → ["50000000","01"]
     */
    private String[] splitAccountNo(String accountNo) {
        if (accountNo.contains("-")) {
            return accountNo.split("-", 2);
        }
        if (accountNo.length() >= 10) {
            return new String[]{accountNo.substring(0, 8), accountNo.substring(8)};
        }
        throw new IllegalArgumentException("잘못된 account-no 형식: " + accountNo);
    }

    // ── KIS 응답 DTO ──────────────────────────────────────────────────────────

    private record OrderResponse(
            @JsonProperty("rt_cd")  String rtCd,
            @JsonProperty("msg1")   String msg1,
            @JsonProperty("output") OrderOutput output
    ) {
        String ordNo() { return output != null ? output.ordNo() : "N/A"; }
    }

    private record OrderOutput(
            @JsonProperty("KRX_FWDG_ORD_ORGNO") String ordOrgNo,
            @JsonProperty("ODNO")                String ordNo,
            @JsonProperty("ORD_TMD")             String ordTime
    ) {}
}
