package com.trading.position;

import com.trading.position.BalanceClient.BalanceSnapshot;
import com.trading.position.BalanceClient.Holding;
import com.trading.position.EquityCrossCheck.Verdict;
import com.trading.position.KisBalanceRaw.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KIS 잔고 응답 → 스냅샷 조립 (HTTP 없음).
 *
 * <p>① 기존 세 값(총자산 = tot_evlu_amt, 예수금 = dnca_tot_amt, 보유)의 뜻이 바이트 단위로 그대로인지
 * ② 대조식의 "현금"이 D+0 예수금이 아니라 D+2 정산액(prvs_rcdl_excc_amt)인지 ③ 원래 숫자를 빠짐없이 붙잡는지 본다.
 */
@DisplayName("KisBalanceRaw — 잔고 응답 원본 보관 + 스냅샷 조립")
class KisBalanceRawTest {

    /** KIS output2[0] — 응답 순서 그대로(키 순서 보존을 확인하려고 LinkedHashMap) */
    private static Map<String, Object> summary(String deposit, String settled, String securities, String total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dnca_tot_amt", deposit);
        m.put("nxdy_excc_amt", deposit);
        m.put("prvs_rcdl_excc_amt", settled);
        m.put("scts_evlu_amt", securities);
        m.put("tot_evlu_amt", total);
        m.put("nass_amt", total);
        m.put("fncg_gld_auto_rdpt_yn", "");
        m.put("asst_icdc_erng_rt", "0.01955000");
        return m;
    }

    private static final Row SAMSUNG_4 = new Row("005930", "4", "75000.0000", "75500", "302000");
    private static final Row SOLD_OUT  = new Row("000660", "0", "0.0000", "200000", "0");

    @Nested
    @DisplayName("기존 값의 뜻은 그대로")
    class LegacyMeaning {

        @Test
        @DisplayName("총자산 = tot_evlu_amt, 예수금 = dnca_tot_amt, 보유는 수량>0 행만 (평단 = pchs_avg_pric, 현재가 = prpr)")
        void snapshot_values_keep_their_meaning() {
            BalanceSnapshot s = KisBalanceRaw.of(
                    summary("9900000", "9786806", "302000", "10088806"), List.of(SAMSUNG_4, SOLD_OUT)).toSnapshot();

            assertThat(s.totalAssetValue()).isEqualTo(10_088_806.0);
            assertThat(s.deposit()).isEqualTo(9_900_000.0);
            assertThat(s.holdings()).containsExactly(new Holding("005930", 4, 75_000.0, 75_500.0));
        }

        @Test
        @DisplayName("빈 칸·없는 칸·숫자가 아닌 칸은 예전처럼 0으로 읽는다")
        void blank_missing_and_garbage_numbers_read_as_zero_as_before() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dnca_tot_amt", "");
            m.put("tot_evlu_amt", "abc");
            BalanceSnapshot s = KisBalanceRaw.of(m, List.of()).toSnapshot();

            assertThat(s.totalAssetValue()).isZero();
            assertThat(s.deposit()).isZero();
            assertThat(s.holdings()).isEmpty();
        }

        @Test
        @DisplayName("output1이 없으면 보유 없음")
        void missing_output1_means_no_holdings() {
            BalanceSnapshot s = KisBalanceRaw.of(summary("1", "1", "0", "1"), null).toSnapshot();

            assertThat(s.holdings()).isEmpty();
        }

        @Test
        @DisplayName("문자열이 아닌 숫자 값(JSON number)도 같은 값으로 읽는다")
        void json_numbers_are_read_as_the_same_value() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tot_evlu_amt", 10_088_806);
            m.put("dnca_tot_amt", 9_900_000L);
            BalanceSnapshot s = KisBalanceRaw.of(m, List.of()).toSnapshot();

            assertThat(s.totalAssetValue()).isEqualTo(10_088_806.0);
            assertThat(s.deposit()).isEqualTo(9_900_000.0);
        }
    }

    @Nested
    @DisplayName("대조식의 현금은 D+2 정산액")
    class SettledCash {

        @Test
        @DisplayName("매매 직후(D+0 예수금 ≠ D+2 정산액)의 정상 응답은 일치로 나온다")
        void post_trade_response_matches_with_d2_settled_cash() {
            // 어제 전액 현금 1,000만 → 오늘 삼성전자 4주 매수(30만 + 수수료 45원)
            // D+0 예수금은 결제일(D+2)까지 그대로 1,000만, D+2 정산액은 매수대금만큼 줄어든 9,699,955
            BalanceSnapshot s = KisBalanceRaw.of(
                    summary("10000000", "9699955", "302000", "10001955"), List.of(SAMSUNG_4)).toSnapshot();

            assertThat(s.equityCheck().verdict()).isEqualTo(Verdict.MATCH);
            assertThat(s.equityCheck().settledCash()).isEqualTo(9_699_955.0);
            assertThat(s.deposit()).isEqualTo(10_000_000.0);   // 예수금 뜻은 그대로 D+0
        }

        @Test
        @DisplayName("같은 응답을 D+0 예수금으로 계산했다면 3% 어긋나 헛불일치가 났다 — 이 시험이 식의 선택을 가른다")
        void the_same_response_would_mismatch_with_d0_deposit() {
            double total = 10_001_955;
            double d0Computed = 10_000_000 + 4 * 75_500;

            assertThat(Math.abs(total - d0Computed) / total).isGreaterThan(EquityCrossCheck.TOLERANCE);
        }

        @Test
        @DisplayName("총자산이 튀면(2026-09-21 오염값) 불일치")
        void spiked_total_is_a_mismatch() {
            BalanceSnapshot s = KisBalanceRaw.of(
                    summary("10088806", "10088806", "0", "10890158"), List.of()).toSnapshot();

            assertThat(s.equityCheck().verdict()).isEqualTo(Verdict.MISMATCH);
            assertThat(s.totalAssetValue()).isEqualTo(10_890_158.0);   // 값 자체는 손대지 않는다
        }

        @Test
        @DisplayName("D+2 정산액 칸이 없거나·비었거나·숫자가 아니면 판정 불가")
        void missing_blank_or_garbage_settled_cash_is_unchecked() {
            Map<String, Object> missing = summary("1", "x", "0", "10890158");
            missing.remove("prvs_rcdl_excc_amt");
            Map<String, Object> blank = summary("1", " ", "0", "10890158");
            Map<String, Object> garbage = summary("1", "N/A", "0", "10890158");
            Map<String, Object> nullValue = summary("1", null, "0", "10890158");

            for (Map<String, Object> m : List.of(missing, blank, garbage, nullValue)) {
                assertThat(KisBalanceRaw.of(m, List.of()).toSnapshot().equityCheck().verdict())
                        .isEqualTo(Verdict.UNCHECKED);
            }
        }

        @Test
        @DisplayName("총자산이 0이면 판정 불가")
        void zero_total_is_unchecked() {
            BalanceSnapshot s = KisBalanceRaw.of(summary("0", "0", "0", "0"), List.of()).toSnapshot();

            assertThat(s.equityCheck().verdict()).isEqualTo(Verdict.UNCHECKED);
        }
    }

    @Nested
    @DisplayName("원래 숫자 보관")
    class RawNumbers {

        @Test
        @DisplayName("output2[0]의 모든 키=값을 응답 순서대로, output1은 행마다 핵심 숫자 5개를 남긴다")
        void describe_keeps_every_summary_entry_and_row_core_numbers() {
            String text = KisBalanceRaw.of(
                    summary("10000000", "9699955", "302000", "10001955"), List.of(SAMSUNG_4, SOLD_OUT)).describe();

            assertThat(text)
                    .contains("dnca_tot_amt=10000000", "nxdy_excc_amt=10000000", "prvs_rcdl_excc_amt=9699955",
                            "scts_evlu_amt=302000", "tot_evlu_amt=10001955", "nass_amt=10001955",
                            "fncg_gld_auto_rdpt_yn=", "asst_icdc_erng_rt=0.01955000")
                    .contains("pdno=005930", "hldg_qty=4", "pchs_avg_pric=75000.0000", "prpr=75500", "evlu_amt=302000")
                    .contains("pdno=000660");
            assertThat(text.indexOf("dnca_tot_amt")).isLessThan(text.indexOf("tot_evlu_amt="));
        }

        @Test
        @DisplayName("계좌·키·토큰 같은 이름의 칸이 섞여 와도 값은 가린다")
        void sensitive_looking_keys_are_masked() {
            Map<String, Object> m = summary("1", "1", "0", "1");
            m.put("cano", "50000000");
            m.put("acnt_prdt_cd", "01");
            m.put("access_token", "secret-token");

            String text = KisBalanceRaw.of(m, List.of()).describe();

            assertThat(text).doesNotContain("50000000", "secret-token").contains("cano=***", "access_token=***");
        }

        @Test
        @DisplayName("원본 값은 문자열 그대로 꺼낼 수 있다 — 없는 칸은 null")
        void value_returns_the_raw_string() {
            KisBalanceRaw raw = KisBalanceRaw.of(summary("10000000", "9699955", "302000", "10001955"), List.of());

            assertThat(raw.value("prvs_rcdl_excc_amt")).isEqualTo("9699955");
            assertThat(raw.value("no_such_key")).isNull();
        }
    }
}
