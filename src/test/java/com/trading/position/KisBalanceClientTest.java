package com.trading.position;

import ch.qos.logback.classic.Level;
import com.trading.backtest.MutableClock;
import com.trading.market.KisApiClientFixture;
import com.trading.position.BalanceClient.BalanceSnapshot;
import com.trading.position.BalanceClient.Holding;
import com.trading.position.EquityCrossCheck.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * KIS 잔고조회(VTTC8434R) — 실제 JSON 본문을 가짜 서버로 흘려 끝에서 끝까지 본다.
 * 운영과 같은 인터셉터가 붙은 {@code KisApiClient}를 거친다({@link KisApiClientFixture}).
 */
@DisplayName("KisBalanceClient — JSON → 스냅샷 + 대조 판정 + 원래 숫자 기록")
class KisBalanceClientTest {

    private static final String BALANCE_PATH = "/uapi/domestic-stock/v1/trading/inquire-balance";

    private MockRestServiceServer server;
    private KisBalanceClient sut;

    @BeforeEach
    void setUp() {
        KisApiClientFixture.Bound kis = KisApiClientFixture.bind();
        server = kis.apiServer();
        MutableClock clock = new MutableClock(ZonedDateTime.of(LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                ZoneId.of("Asia/Seoul")).toInstant());
        sut = new KisBalanceClient(kis.client(), clock);
    }

    /** 매매 직후 응답 — D+0 예수금 1,000만, D+2 정산 9,699,955, 삼성전자 4주(현재가 75,500) */
    private static String body(String total) {
        return "{\"rt_cd\":\"0\",\"msg_cd\":\"20310000\",\"msg1\":\"모의투자 조회가 완료되었습니다.\","
                + "\"ctx_area_fk100\":\"50000000^01^N^N^01^01^N^\",\"ctx_area_nk100\":\"\","
                + "\"output1\":["
                + "{\"pdno\":\"005930\",\"prdt_name\":\"삼성전자\",\"hldg_qty\":\"4\",\"ord_psbl_qty\":\"4\","
                + "\"pchs_avg_pric\":\"75000.0000\",\"prpr\":\"75500\",\"evlu_amt\":\"302000\"},"
                + "{\"pdno\":\"000660\",\"prdt_name\":\"SK하이닉스\",\"hldg_qty\":\"0\","
                + "\"pchs_avg_pric\":\"0.0000\",\"prpr\":\"200000\",\"evlu_amt\":\"0\"}],"
                + "\"output2\":[{\"dnca_tot_amt\":\"10000000\",\"nxdy_excc_amt\":\"10000000\","
                + "\"prvs_rcdl_excc_amt\":\"9699955\",\"scts_evlu_amt\":\"302000\",\"tot_evlu_amt\":\"" + total + "\","
                + "\"nass_amt\":\"10001955\",\"fncg_gld_auto_rdpt_yn\":\"\",\"bfdy_tot_asst_evlu_amt\":\"10000000\","
                + "\"asst_icdc_amt\":\"1955\",\"asst_icdc_erng_rt\":\"0.01955000\"}]}";
    }

    private void respond(String json) {
        server.expect(ExpectedCount.once(), requestTo(containsString(BALANCE_PATH)))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("기존 세 값의 뜻은 그대로 — 총자산 tot_evlu_amt · 예수금 dnca_tot_amt(D+0) · 보유는 수량>0만")
    void snapshot_keeps_existing_meanings() {
        respond(body("10001955"));

        BalanceSnapshot s = sut.fetchBalance();

        server.verify();
        assertThat(s.totalAssetValue()).isEqualTo(10_001_955.0);
        assertThat(s.deposit()).isEqualTo(10_000_000.0);
        assertThat(s.holdings()).containsExactly(new Holding("005930", 4, 75_000.0, 75_500.0));
    }

    @Test
    @DisplayName("매매 직후(D+0 ≠ D+2) 정상 응답은 D+2 정산액으로 계산해 일치")
    void post_trade_response_is_a_match() {
        respond(body("10001955"));

        assertThat(sut.fetchBalance().equityCheck().verdict()).isEqualTo(Verdict.MATCH);
    }

    @Test
    @DisplayName("총자산만 튀면 불일치 — 원래 숫자를 WARN으로 남기되 계좌번호·키·토큰은 남기지 않는다")
    void spiked_total_is_a_mismatch_logged_without_secrets() {
        respond(body("10800000"));

        try (LogCapture logs = LogCapture.of("com.trading")) {
            BalanceSnapshot s = sut.fetchBalance();

            assertThat(s.equityCheck().verdict()).isEqualTo(Verdict.MISMATCH);
            assertThat(s.totalAssetValue()).isEqualTo(10_800_000.0);   // 값은 손대지 않는다
            assertThat(logs.messages(Level.WARN))
                    .anyMatch(m -> m.contains("불일치") && m.contains("tot_evlu_amt=10800000")
                            && m.contains("prvs_rcdl_excc_amt=9699955") && m.contains("pdno=005930"));
            assertThat(logs.allMessages()).noneMatch(m -> m.contains("50000000")
                    || m.contains(KisApiClientFixture.APPKEY) || m.contains(KisApiClientFixture.SECRETKEY)
                    || m.contains(KisApiClientFixture.TOKEN));
        }
    }

    @Test
    @DisplayName("결과코드가 실패면 예전처럼 KIS 메시지를 담아 예외")
    void failed_result_code_throws_with_kis_message() {
        respond("{\"rt_cd\":\"1\",\"msg1\":\"초당 거래건수를 초과하였습니다.\"}");

        assertThatThrownBy(() -> sut.fetchBalance())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rt_cd=1").hasMessageContaining("초당 거래건수");
    }

    @Test
    @DisplayName("output2가 비면 예전처럼 예외")
    void empty_output2_throws() {
        respond("{\"rt_cd\":\"0\",\"msg1\":\"ok\",\"output1\":[],\"output2\":[]}");

        assertThatThrownBy(() -> sut.fetchBalance())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("output2 없음");
    }
}
