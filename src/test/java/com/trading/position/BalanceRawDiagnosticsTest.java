package com.trading.position;

import ch.qos.logback.classic.Level;
import com.trading.backtest.MutableClock;
import com.trading.position.KisBalanceRaw.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 잔고 원래 숫자 기록 — 튀는 원인을 알 수 없었던 이유는 증권사가 보낸 숫자가 한 번도 남지 않았기 때문이다(결함 6).
 *
 * <p>① 하루 1회 기준선 INFO(1% 허용오차를 1주일간 검증할 근거) ② 불일치 WARN ③ ±2% 급변 WARN —
 * 경고는 각각 처음 1회 + 이어지면 10분에 최대 1회, 생략 건수를 적는다. 동작은 바꾸지 않는다(기록 전용).
 */
@DisplayName("BalanceRawDiagnostics — 잔고 원래 숫자 기록")
class BalanceRawDiagnosticsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);   // 월요일

    private final MutableClock clock = new MutableClock(
            ZonedDateTime.of(DAY, LocalTime.of(9, 0, 5), ZoneId.of("Asia/Seoul")).toInstant());
    private final BalanceRawDiagnostics sut = new BalanceRawDiagnostics(clock);

    private static KisBalanceRaw raw(String total, String settled) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dnca_tot_amt", "10000000");
        m.put("prvs_rcdl_excc_amt", settled);
        m.put("scts_evlu_amt", "302000");
        m.put("tot_evlu_amt", total);
        return KisBalanceRaw.of(m, List.of(new Row("005930", "4", "75000.0000", "75500", "302000")));
    }

    /** 정상 응답 — 9,699,955 + 4×75,500 = 10,001,955 */
    private static KisBalanceRaw normal() {
        return raw("10001955", "9699955");
    }

    /** 총자산만 튄 응답 (+7.9%) */
    private static KisBalanceRaw spiked() {
        return raw("10800000", "9699955");
    }

    private void record(KisBalanceRaw raw) {
        sut.record(raw, raw.toSnapshot());
    }

    private void at(int hour, int minute) {
        clock.setTo(DAY, LocalTime.of(hour, minute));
    }

    @Nested
    @DisplayName("하루 1회 기준선 INFO")
    class DailyBaseline {

        @Test
        @DisplayName("그날 첫 성공 조회에 6개 숫자와 차이%를 한 줄 남긴다")
        void first_success_of_the_day_logs_one_baseline_line() {
            try (LogCapture logs = LogCapture.of(BalanceRawDiagnostics.class)) {
                record(normal());

                List<String> info = logs.messages(Level.INFO);
                assertThat(info).hasSize(1);
                assertThat(info.get(0)).contains("[잔고기준선]", "2026-10-12",
                        "tot_evlu_amt=10001955", "prvs_rcdl_excc_amt=9699955", "dnca_tot_amt=10000000",
                        "scts_evlu_amt=302000", "보유평가(수량×현재가)=302000", "차이=+0.000%", "MATCH");
            }
        }

        @Test
        @DisplayName("같은 날 두 번째 조회부터는 남기지 않고, 다음 날 첫 조회에 다시 남긴다")
        void baseline_once_per_kst_day() {
            try (LogCapture logs = LogCapture.of(BalanceRawDiagnostics.class)) {
                record(normal());
                at(10, 0);
                record(normal());
                clock.setTo(DAY.plusDays(1), LocalTime.of(9, 0, 3));
                record(normal());

                assertThat(logs.messages(Level.INFO)).hasSize(2);
                assertThat(logs.messages(Level.INFO).get(1)).contains("2026-10-13");
            }
        }

        @Test
        @DisplayName("D+2 정산액이 없으면 기준선에 '없음'과 판정 불가를 적는다")
        void baseline_without_settled_cash_says_unchecked() {
            try (LogCapture logs = LogCapture.of(BalanceRawDiagnostics.class)) {
                record(raw("10001955", null));

                assertThat(logs.messages(Level.INFO).get(0))
                        .contains("prvs_rcdl_excc_amt=(없음)", "UNCHECKED", "차이=판정 불가");
            }
        }
    }

    @Nested
    @DisplayName("불일치 WARN")
    class MismatchWarn {

        @Test
        @DisplayName("불일치면 계산값·증권사값·직전 성공 총자산과 시각 + 원래 숫자 전부를 남긴다")
        void mismatch_logs_raw_numbers_and_previous_success() {
            record(normal());                    // 09:00:05 직전 성공 = 10,001,955
            at(10, 15);
            try (LogCapture logs = LogCapture.of(BalanceRawDiagnostics.class)) {
                record(spiked());

                List<String> warn = logs.messages(Level.WARN);
                assertThat(warn).hasSize(1);
                assertThat(warn.get(0)).contains("[잔고원본]", "불일치",
                        "증권사 총자산=10800000", "계산값=10001955", "D+2 정산=9699955", "보유평가=302000",
                        "직전 성공=10001955", "09:00:05",
                        "tot_evlu_amt=10800000", "prvs_rcdl_excc_amt=9699955", "pdno=005930", "evlu_amt=302000");
            }
        }

        @Test
        @DisplayName("불일치가 이어지면 처음 1회 + 10분에 최대 1회 — 생략 건수를 적는다")
        void mismatch_warn_is_throttled_with_skipped_count() {
            try (LogCapture logs = LogCapture.of(BalanceRawDiagnostics.class)) {
                at(10, 0);
                record(spiked());                // 기록
                at(10, 3);
                record(spiked());                // 생략 1
                at(10, 6);
                record(spiked());                // 생략 2
                at(10, 10);
                record(spiked());                // 기록 (2회 생략)

                List<String> mismatchWarns = logs.messages(Level.WARN).stream()
                        .filter(m -> m.contains("불일치")).toList();
                assertThat(mismatchWarns).hasSize(2);
                assertThat(mismatchWarns.get(1)).contains("2회 생략");
            }
        }

        @Test
        @DisplayName("일치하는 정상 조회는 WARN을 남기지 않는다")
        void normal_response_logs_no_warn() {
            try (LogCapture logs = LogCapture.of(BalanceRawDiagnostics.class)) {
                record(normal());
                at(10, 0);
                record(normal());

                assertThat(logs.messages(Level.WARN)).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("±2% 급변 WARN (진단 전용)")
    class JumpWarn {

        @Test
        @DisplayName("직전 성공 대비 정확히 +2%면 원래 숫자를 남긴다 — 일치하는 응답이어도")
        void two_percent_jump_logs_even_when_consistent() {
            record(raw("10000000", "9698000"));                 // 9,698,000 + 302,000 = 10,000,000
            at(10, 0);
            try (LogCapture logs = LogCapture.of(BalanceRawDiagnostics.class)) {
                record(raw("10200000", "9898000"));             // +2.00%, 식은 맞는다

                List<String> warn = logs.messages(Level.WARN);
                assertThat(warn).hasSize(1);
                assertThat(warn.get(0)).contains("급변", "+2.00%", "직전 성공=10000000",
                        "tot_evlu_amt=10200000");
            }
        }

        @Test
        @DisplayName("-2% 이상 떨어져도 남긴다")
        void two_percent_drop_logs() {
            record(raw("10000000", "9698000"));
            at(10, 0);
            try (LogCapture logs = LogCapture.of(BalanceRawDiagnostics.class)) {
                record(raw("9800000", "9498000"));              // -2.00%

                assertThat(logs.messages(Level.WARN)).anyMatch(m -> m.contains("급변") && m.contains("-2.00%"));
            }
        }

        @Test
        @DisplayName("2% 미만 변화는 남기지 않는다")
        void below_two_percent_logs_nothing() {
            record(raw("10000000", "9698000"));
            at(10, 0);
            try (LogCapture logs = LogCapture.of(BalanceRawDiagnostics.class)) {
                record(raw("10199000", "9897000"));             // +1.99%

                assertThat(logs.messages(Level.WARN)).isEmpty();
            }
        }

        @Test
        @DisplayName("처음 조회(비교할 직전값 없음)는 급변 판정을 하지 않는다")
        void first_ever_fetch_has_no_jump_check() {
            try (LogCapture logs = LogCapture.of(BalanceRawDiagnostics.class)) {
                record(raw("10200000", "9898000"));

                assertThat(logs.messages(Level.WARN)).isEmpty();
            }
        }

        @Test
        @DisplayName("불일치이면서 급변이면 한 줄에 두 이유를 함께 적는다 — 원래 숫자를 두 번 찍지 않는다")
        void mismatch_and_jump_share_one_line() {
            record(normal());
            at(10, 0);
            try (LogCapture logs = LogCapture.of(BalanceRawDiagnostics.class)) {
                record(spiked());

                List<String> warn = logs.messages(Level.WARN);
                assertThat(warn).hasSize(1);
                assertThat(warn.get(0)).contains("불일치", "급변");
            }
        }
    }
}
