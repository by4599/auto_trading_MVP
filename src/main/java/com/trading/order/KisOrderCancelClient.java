package com.trading.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.market.KisApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KIS 취소 주문 API(VTTC0803U) 래퍼 — FillProcessor.attemptCancel()에서 추출.
 * QTY_ALL_ORD_YN=Y 로 잔량 전부 취소를 요청한다.
 */
@Component
@Profile("paper")
public class KisOrderCancelClient implements OrderCancelClient {

    private static final Logger log = LoggerFactory.getLogger(KisOrderCancelClient.class);

    private static final String TR_CANCEL  = "VTTC0803U";
    private static final String CANCEL_URI = "/uapi/domestic-stock/v1/trading/order-rvsecncl";

    private final KisApiClient kisApiClient;

    public KisOrderCancelClient(KisApiClient kisApiClient) {
        this.kisApiClient = kisApiClient;
    }

    @Override
    public CancelOutcome cancelAll(String orderNo) {
        String[] acnt = split(kisApiClient.getProps().getAccountNo());

        Map<String, String> body = new LinkedHashMap<>();
        body.put("CANO",               acnt[0]);
        body.put("ACNT_PRDT_CD",       acnt[1]);
        body.put("KRX_FWDG_ORD_ORGNO", "");
        body.put("ORGN_ODNO",          orderNo);
        body.put("ORD_DVSN",           "00");
        body.put("RVSE_CNCL_DVSN_CD",  "02");
        body.put("ORD_QTY",            "0");
        body.put("ORD_UNPR",           "0");
        body.put("QTY_ALL_ORD_YN",     "Y");

        try {
            CancelResponse resp = kisApiClient.getClient().post()
                    .uri(CANCEL_URI)
                    .header("tr_id",    TR_CANCEL)
                    .header("custtype", "P")
                    .body(body)
                    .retrieve()
                    .body(CancelResponse.class);

            if (resp != null && resp.isSuccess()) return CancelOutcome.SENT;

            // "정정/취소할 수량이 없습니다" = 이미 체결/종료된 주문 → 잔고 대사 트리거
            if (resp != null && resp.msg1() != null && resp.msg1().contains("취소할 수량")) {
                return CancelOutcome.NO_OPEN_QTY;
            }

            log.warn("취소 거부: ordNo={} rt_cd={} msg={}", orderNo,
                    resp == null ? "null" : resp.rtCd(), resp == null ? "null" : resp.msg1());
            return CancelOutcome.FAILED;
        } catch (Exception e) {
            log.error("취소 API 오류: ordNo={}", orderNo, e);
            return CancelOutcome.FAILED;
        }
    }

    private static String[] split(String accountNo) {
        if (accountNo.contains("-")) return accountNo.split("-", 2);
        if (accountNo.length() >= 10)
            return new String[]{accountNo.substring(0, 8), accountNo.substring(8)};
        throw new IllegalArgumentException("잘못된 account-no 형식: " + accountNo);
    }

    private record CancelResponse(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg1")  String msg1
    ) {
        boolean isSuccess() { return "0".equals(rtCd); }
    }
}
