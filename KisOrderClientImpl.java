package com.trading.order;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    public KisOrderClientImpl(KisApiClient kisApiClient,
                              OrderHistoryRepository orderHistoryRepository) {
        this.kisApiClient = kisApiClient;
        this.orderHistoryRepository = orderHistoryRepository;
    }

    @Override
    public void buy(String stockCode) {
        placeOrder(TR_BUY, stockCode, OrderSide.BUY);
    }

    @Override
    public void sell(String stockCode) {
        placeOrder(TR_SELL, stockCode, OrderSide.SELL);
    }

    private void placeOrder(String trId, String stockCode, OrderSide side) {
        log.info("주문 요청: side={}, stockCode={}", side, stockCode);

        String[] acnt = splitAccountNo(kisApiClient.getProps().getAccountNo());

        OrderResponse resp = kisApiClient.getClient().post()
                .uri(ORDER_URI)
                .header("tr_id",    trId)
                .header("custtype", "P")
                .body(Map.of(
                        "CANO",         acnt[0],
                        "ACNT_PRDT_CD", acnt[1],
                        "PDNO",         stockCode,
                        "ORD_DVSN",     ORD_DVSN,
                        "ORD_QTY",      String.valueOf(ORD_QTY),
                        "ORD_UNPR",     "0"
                ))
                .retrieve()
                .body(OrderResponse.class);

        if (resp == null) {
            throw new IllegalStateException(side + " 주문 응답 없음: " + stockCode);
        }
        if (!"0".equals(resp.rtCd())) {
            throw new IllegalStateException(
                    String.format("%s 주문 실패: stockCode=%s rtCd=%s msg=%s",
                            side, stockCode, resp.rtCd(), resp.msg1()));
        }

        // rt_cd==0 = 접수 성공. 체결 여부는 FillPoller가 확인한다.
        String ordNo = resp.ordNo();
        orderHistoryRepository.save(OrderHistory.accepted(stockCode, side, ORD_QTY, ordNo));
        log.info("주문 접수 완료 — 저장됨: side={} stockCode={} ordNo={}", side, stockCode, ordNo);
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
