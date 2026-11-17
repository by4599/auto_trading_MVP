package com.trading.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.market.KisApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 브로커 체결 원장 조회 (VTTC8001R, 읽기 전용) — 체결 추적의 단일 조회 지점.
 *
 * <p><b>주문번호(ODNO) 필터를 쓰지 않는다.</b> 필터를 걸면 실제 체결된 주문에도
 * {@code rt_cd=0 · output1=[]}가 돌아왔다(2026-07-28~30 진단). 그 대가로 매도 체결 38건 중
 * 37건이 체결가 없이 종결됐다(2026-08-07 실측). 범위 전체를 받아 앱에서 주문번호로 매칭한다.
 *
 * <p>날짜 범위를 받는 이유는 두 가지다. 실시간 폴링은 당일만 보면 되지만,
 * <b>과거에 가격 없이 종결된 체결을 되찾으려면</b> 지난 날짜를 조회해야 한다 —
 * 우리 DB에는 0으로 적혔어도 브로커 원장에는 실제 체결가가 남아 있다.
 *
 * <p>주문을 내지 않는다. 조회만 한다.
 */
@Component
@Profile("paper")
public class BrokerFillHistoryClient {

    private static final Logger log = LoggerFactory.getLogger(BrokerFillHistoryClient.class);

    private static final String TR_FILL  = "VTTC8001R";
    private static final String FILL_URI = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final KisApiClient kisApiClient;

    public BrokerFillHistoryClient(KisApiClient kisApiClient) {
        this.kisApiClient = kisApiClient;
    }

    /** 브로커가 기록한 체결 한 건 — 주문번호 기준 누적값 */
    public record BrokerFill(String orderNo, String stockCode, int filledQty, double avgPrice) {}

    /** 조회 실패와 "체결 없음"을 구분한다 — 실패를 0건으로 오해하면 유령이 생긴다 */
    public record FillPage(boolean apiError, Map<String, BrokerFill> byOrderNo) {
        public static FillPage error()                              { return new FillPage(true, Map.of()); }
        public static FillPage of(Map<String, BrokerFill> byOrderNo) { return new FillPage(false, byOrderNo); }
    }

    public FillPage fetch(LocalDate from, LocalDate to) {
        try {
            String[] acnt = split(kisApiClient.getProps().getAccountNo());
            FillResponse resp = kisApiClient.getClient().get()
                    .uri(b -> b.path(FILL_URI)
                            .queryParam("CANO",            acnt[0])
                            .queryParam("ACNT_PRDT_CD",    acnt[1])
                            .queryParam("INQR_STRT_DT",    from.format(DATE_FMT))
                            .queryParam("INQR_END_DT",     to.format(DATE_FMT))
                            .queryParam("SLL_BUY_DVSN_CD", "00")
                            .queryParam("INQR_DVSN",       "00")
                            .queryParam("PDNO",             "")
                            .queryParam("CCLD_DVSN",       "00")
                            .queryParam("ORD_GNO_BRNO",    "")
                            .queryParam("ODNO",             "")   // 필터 금지 — 클래스 주석 참고
                            .queryParam("INQR_DVSN_3",     "00")
                            .queryParam("INQR_DVSN_1",     "")
                            .queryParam("CTX_AREA_FK100",   "")
                            .queryParam("CTX_AREA_NK100",   "")
                            .build())
                    .header("tr_id",    TR_FILL)
                    .header("custtype", "P")
                    .retrieve()
                    .body(FillResponse.class);

            if (resp == null) return FillPage.error();
            if (!"0".equals(resp.rtCd())) {
                log.error("[체결원장] 조회 오류: rt_cd={} msg={} 범위={}~{}",
                        resp.rtCd(), resp.msg1(), from, to);
                return FillPage.error();
            }
            return FillPage.of(index(resp.output1()));

        } catch (Exception e) {
            log.error("[체결원장] 조회 실패: 범위={}~{}", from, to, e);
            return FillPage.error();
        }
    }

    /**
     * 주문번호별로 접는다. tot_ccld_qty는 행마다 같은 누적값이지만, 부분 체결 이력이 섞여
     * 와도 누적이 줄지 않도록 가장 큰 수량의 행을 취한다.
     */
    private Map<String, BrokerFill> index(List<FillItem> rows) {
        Map<String, BrokerFill> byOrderNo = new HashMap<>();
        if (rows == null) return byOrderNo;
        for (FillItem r : rows) {
            if (r.ordNo() == null || r.ordNo().isBlank()) continue;
            BrokerFill fill = new BrokerFill(
                    r.ordNo(), r.stockCode(), parseInt(r.totCcldQty()), parseDouble(r.avgPrvs()));
            byOrderNo.merge(r.ordNo(), fill,
                    (a, b) -> a.filledQty() >= b.filledQty() ? a : b);
        }
        return byOrderNo;
    }

    private static String[] split(String accountNo) {
        if (accountNo.contains("-")) return accountNo.split("-", 2);
        if (accountNo.length() >= 10)
            return new String[]{accountNo.substring(0, 8), accountNo.substring(8)};
        throw new IllegalArgumentException("잘못된 account-no 형식: " + accountNo);
    }

    private static int    parseInt(String s)    { return (s == null || s.isBlank()) ? 0   : Integer.parseInt(s.trim()); }
    private static double parseDouble(String s) { return (s == null || s.isBlank()) ? 0.0 : Double.parseDouble(s.trim()); }

    private record FillResponse(
            @JsonProperty("rt_cd")   String rtCd,
            @JsonProperty("msg1")    String msg1,
            @JsonProperty("output1") List<FillItem> output1
    ) {}

    private record FillItem(
            @JsonProperty("odno")         String ordNo,
            @JsonProperty("pdno")         String stockCode,
            @JsonProperty("tot_ccld_qty") String totCcldQty,
            @JsonProperty("avg_prvs")     String avgPrvs
    ) {}
}
