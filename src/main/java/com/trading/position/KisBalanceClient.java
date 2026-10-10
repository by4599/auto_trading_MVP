package com.trading.position;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.market.KisApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * KIS 잔고조회 API(VTTC8434R) 래퍼.
 *
 * 반환하는 BalanceSnapshot의 totalAssetValue는 output2의 tot_evlu_amt로,
 * "예수금 + 보유종목 평가금액"이다 — 현금을 포함한 진짜 총자산.
 * deposit은 output2의 dnca_tot_amt(예수금총액) — 수수료·세금이 이미 차감된 현금이다.
 * 보유종목의 currentPrice는 KIS가 계산해 주는 실시간 현재가(prpr)다.
 *
 * 감사 F-1(현금 미포함 + 평단가 근사로 인한 MDD 오탐/미탐)의 해소 지점.
 *
 * 2026-10-11(결함 6 — 총자산이 가끔 튄다): 응답 → 스냅샷 조립은 {@link KisBalanceRaw}(순수 계산)로 옮겼고,
 * 스냅샷에 총자산 대조 판정({@link EquityCrossCheck})을 싣는다. 증권사가 보낸 원래 숫자는
 * {@link BalanceRawDiagnostics}가 이상할 때·하루 1회 로그로 남긴다. 기존 세 값의 뜻은 그대로다.
 * 이 클래스는 HTTP 호출과 결과코드 검사만 하는 얇은 래퍼다.
 */
@Component
@Profile("paper")
public class KisBalanceClient implements BalanceClient {

    private static final Logger log = LoggerFactory.getLogger(KisBalanceClient.class);

    private static final String BALANCE_URI = "/uapi/domestic-stock/v1/trading/inquire-balance";
    private static final String TR_BALANCE  = "VTTC8434R"; // 모의투자 잔고조회

    private final KisApiClient kisApiClient;
    private final BalanceRawDiagnostics diagnostics;

    /** @param clock KST 시계(ClockConfig) — 하루 1회 기준선의 "하루"를 가른다 */
    public KisBalanceClient(KisApiClient kisApiClient, Clock clock) {
        this.kisApiClient = kisApiClient;
        this.diagnostics = new BalanceRawDiagnostics(clock);
    }

    @Override
    public BalanceSnapshot fetchBalance() {
        BalanceResponse resp = request();

        if (resp == null) {
            throw new IllegalStateException("잔고조회 응답이 비어있습니다 (null)");
        }
        // rt_cd(결과코드)를 먼저 확인해 KIS의 실제 오류 메시지(msg1)를 그대로 드러낸다.
        // (output2 검사를 먼저 하면 "output2 없음"이 진짜 원인 메시지를 가려버린다)
        if (resp.rtCd() != null && !"0".equals(resp.rtCd())) {
            throw new IllegalStateException("잔고조회 실패: rt_cd=" + resp.rtCd() + " msg=" + resp.msg1());
        }
        if (resp.output2() == null || resp.output2().isEmpty() || resp.output2().get(0) == null) {
            throw new IllegalStateException(
                    "잔고조회 응답 비정상: output2 없음 (rt_cd=" + resp.rtCd() + " msg=" + resp.msg1() + ")");
        }

        KisBalanceRaw raw = KisBalanceRaw.of(resp.output2().get(0), resp.output1());
        BalanceSnapshot snapshot = raw.toSnapshot();
        recordDiagnostics(raw, snapshot);

        log.debug("잔고조회 완료: 총자산={} 예수금={} 보유종목={}",
                snapshot.totalAssetValue(), snapshot.deposit(), snapshot.holdings().size());
        return snapshot;
    }

    private BalanceResponse request() {
        String[] acnt = splitAccountNo(kisApiClient.getProps().getAccountNo());
        return kisApiClient.getClient().get()
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
    }

    /** 진단은 기록 전용이다 — 기록이 실패해도 잔고 조회(청산·손절 감시의 입력)를 막지 않는다 */
    private void recordDiagnostics(KisBalanceRaw raw, BalanceSnapshot snapshot) {
        try {
            diagnostics.record(raw, snapshot);
        } catch (RuntimeException e) {
            log.warn("잔고 원래 숫자 기록 실패(조회 결과는 그대로 사용): {}", e.toString());
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

    /**
     * output2는 칸을 고르지 않고 키=값 전부를 받는다 — 이상할 때 증권사가 보낸 원래 숫자를 모두 남기기 위해서다.
     * 상위 필드(연속조회키 등)는 받지 않는다(계좌번호를 품을 수 있다).
     */
    private record BalanceResponse(
            @JsonProperty("rt_cd")   String rtCd,
            @JsonProperty("msg1")    String msg1,
            @JsonProperty("output1") List<KisBalanceRaw.Row> output1,
            @JsonProperty("output2") List<Map<String, Object>> output2
    ) {}
}
