package com.trading.position;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.market.KisApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * KIS 잔고조회 API(VTTC8434R) 래퍼.
 *
 * 반환하는 BalanceSnapshot의 totalAssetValue는 output2의 tot_evlu_amt로,
 * "예수금 + 보유종목 평가금액"이다 — 현금을 포함한 진짜 총자산.
 * 보유종목의 currentPrice는 KIS가 계산해 주는 실시간 현재가(prpr)다.
 *
 * 감사 F-1(현금 미포함 + 평단가 근사로 인한 MDD 오탐/미탐)의 해소 지점.
 */
@Component
@Profile("paper")
public class KisBalanceClient implements BalanceClient {

    private static final Logger log = LoggerFactory.getLogger(KisBalanceClient.class);

    private static final String BALANCE_URI = "/uapi/domestic-stock/v1/trading/inquire-balance";
    private static final String TR_BALANCE  = "VTTC8434R"; // 모의투자 잔고조회

    private final KisApiClient kisApiClient;

    public KisBalanceClient(KisApiClient kisApiClient) {
        this.kisApiClient = kisApiClient;
    }

    @Override
    public BalanceSnapshot fetchBalance() {
        String[] acnt = splitAccountNo(kisApiClient.getProps().getAccountNo());

        BalanceResponse resp = kisApiClient.getClient().get()
                .uri(b -> b.path(BALANCE_URI)
                        .queryParam("CANO",                  acnt[0])
                        .queryParam("ACNT_PRDT_CD",          acnt[1])
                        .queryParam("AFHR_FLPR_YN",          "N")
                        .queryParam("OFL_YN",                "")
                        .queryParam("INQR_DVSN",             "02")
                        .queryParam("UNPR_DVSN",             "01")
                        .queryParam("FUND_STTL_ICLD_YN",     "N")
                        .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                        .queryParam("PRCS_DVSN",             "00")
                        .queryParam("CTX_AREA_FK100",        "")
                        .queryParam("CTX_AREA_NK100",        "")
                        .build())
                .header("tr_id",    TR_BALANCE)
                .header("custtype", "P")
                .retrieve()
                .body(BalanceResponse.class);

        if (resp == null) {
            throw new IllegalStateException("잔고조회 응답이 비어있습니다 (null)");
        }
        // rt_cd(결과코드)를 먼저 확인해 KIS의 실제 오류 메시지(msg1)를 그대로 드러낸다.
        // (output2 검사를 먼저 하면 "output2 없음"이 진짜 원인 메시지를 가려버린다)
        if (resp.rtCd() != null && !"0".equals(resp.rtCd())) {
            throw new IllegalStateException("잔고조회 실패: rt_cd=" + resp.rtCd() + " msg=" + resp.msg1());
        }
        if (resp.output2() == null || resp.output2().isEmpty()) {
            throw new IllegalStateException(
                    "잔고조회 응답 비정상: output2 없음 (rt_cd=" + resp.rtCd() + " msg=" + resp.msg1() + ")");
        }

        AccountSummary summary = resp.output2().get(0);
        double totalAssetValue = parseDouble(summary.totalEvaluation());

        List<Holding> holdings = resp.output1() == null ? List.of()
                : resp.output1().stream()
                        .filter(h -> parseInt(h.quantity()) > 0)
                        .map(h -> new Holding(
                                h.stockCode(),
                                parseInt(h.quantity()),
                                parseDouble(h.avgPrice()),
                                parseDouble(h.currentPrice())))
                        .toList();

        log.debug("잔고조회 완료: 총자산={} 보유종목={}", totalAssetValue, holdings.size());
        return new BalanceSnapshot(totalAssetValue, holdings);
    }

    // ── 파싱 헬퍼 ─────────────────────────────────────────────────────────────

    private static double parseDouble(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            log.warn("잔고 숫자 파싱 실패 (0.0 반환): '{}'", s);
            return 0.0;
        }
    }

    private static int parseInt(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            log.warn("잔고 정수 파싱 실패 (0 반환): '{}'", s);
            return 0;
        }
    }

    /** "50000000-01" → ["50000000","01"], "5000000001" → ["50000000","01"] (KisOrderClientImpl과 동일 규칙) */
    private static String[] splitAccountNo(String accountNo) {
        if (accountNo.contains("-")) {
            return accountNo.split("-", 2);
        }
        if (accountNo.length() >= 10) {
            return new String[]{accountNo.substring(0, 8), accountNo.substring(8)};
        }
        throw new IllegalArgumentException("잘못된 account-no 형식: " + accountNo);
    }

    // ── KIS 응답 DTO ──────────────────────────────────────────────────────────

    private record BalanceResponse(
            @JsonProperty("rt_cd")   String rtCd,
            @JsonProperty("msg1")    String msg1,
            @JsonProperty("output1") List<HoldingData> output1,
            @JsonProperty("output2") List<AccountSummary> output2
    ) {}

    private record HoldingData(
            @JsonProperty("pdno")          String stockCode,     // 종목코드
            @JsonProperty("hldg_qty")      String quantity,       // 보유수량
            @JsonProperty("pchs_avg_pric") String avgPrice,       // 매입평균가
            @JsonProperty("prpr")          String currentPrice    // 현재가
    ) {}

    private record AccountSummary(
            @JsonProperty("dnca_tot_amt") String deposit,          // 예수금총액
            @JsonProperty("tot_evlu_amt") String totalEvaluation   // 총평가금액 (예수금+평가금액)
    ) {}
}
