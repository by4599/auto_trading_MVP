package com.trading.position;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.position.BalanceClient.BalanceSnapshot;
import com.trading.position.BalanceClient.Holding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * KIS 잔고조회 응답에서 붙잡아 둔 "증권사가 보낸 원래 숫자" + 스냅샷 조립 (HTTP 없음 — 순수 계산, 2026-10-11).
 *
 * <p>output2[0]은 키=값을 <b>전부</b> 응답 순서대로, output1은 행마다 핵심 숫자 5개를 든다. 튀는 원인을 몰랐던 건
 * 원래 숫자가 한 번도 남지 않았기 때문이다(결함 6) — 이상할 때 {@link #describe()}를 그대로 로그에 남긴다.
 *
 * <p>스냅샷의 세 값은 예전 {@code KisBalanceClient}와 뜻·파싱이 같다: 총자산 = tot_evlu_amt, 예수금 = dnca_tot_amt(D+0),
 * 보유 = 수량 &gt; 0 행(평단 pchs_avg_pric, 현재가 prpr). 빈 칸·숫자 아님은 예전처럼 0으로 읽는다.
 * 대조 판정의 현금만 D+2 정산액(prvs_rcdl_excc_amt)이다 — 근거는 {@link EquityCrossCheck}.
 *
 * <p>비밀값: 응답 본문 숫자만 담는다. 요청 헤더·appkey·토큰·계좌번호는 여기 들어오지 않고, 상위 필드
 * (계좌번호를 품을 수 있는 연속조회키 ctx_area_fk100 등)도 담지 않는다. 혹시 계좌·키처럼 보이는 칸이 섞여 오면 값을 가린다.
 */
record KisBalanceRaw(Map<String, String> summary, List<Row> rows) {

    private static final Logger log = LoggerFactory.getLogger(KisBalanceRaw.class);

    static final String TOTAL_EVALUATION      = "tot_evlu_amt";        // 총평가금액 = D+2 정산액 + 유가평가금액
    static final String DEPOSIT               = "dnca_tot_amt";        // 예수금총금액 (D+0)
    static final String SETTLED_CASH          = "prvs_rcdl_excc_amt";  // 가수도정산금액 (D+2)
    static final String SECURITIES_EVALUATION = "scts_evlu_amt";       // 유가평가금액

    /** 계좌번호·인증값처럼 보이는 칸 이름 — 응답 정의에는 없지만 섞여 오면 값을 가린다 */
    private static final Pattern SENSITIVE_KEY = Pattern.compile("cano|acnt|token|appkey|secret|ctx_area");

    /** output1 한 행의 핵심 숫자 — KIS 응답에 그대로 매핑된다 */
    record Row(@JsonProperty("pdno")          String stockCode,         // 종목코드
               @JsonProperty("hldg_qty")      String quantity,          // 보유수량
               @JsonProperty("pchs_avg_pric") String avgPrice,          // 매입평균가
               @JsonProperty("prpr")          String currentPrice,      // 현재가
               @JsonProperty("evlu_amt")      String evaluationAmount   // 평가금액
    ) {}

    KisBalanceRaw {
        summary = Collections.unmodifiableMap(new LinkedHashMap<>(summary == null ? Map.of() : summary));
        rows = rows == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(rows));
    }

    /** JSON에서 받은 값을 문자열로 붙잡는다(응답 순서 보존, null 값은 null 그대로) */
    static KisBalanceRaw of(Map<String, ?> summaryFromKis, List<Row> rowsFromKis) {
        Map<String, String> summary = new LinkedHashMap<>();
        if (summaryFromKis != null) {
            summaryFromKis.forEach((k, v) -> summary.put(k, v == null ? null : String.valueOf(v)));
        }
        return new KisBalanceRaw(summary, rowsFromKis);
    }

    /** 원래 문자열 그대로 — 없는 칸은 null */
    String value(String key) {
        return summary.get(key);
    }

    BalanceSnapshot toSnapshot() {
        double totalAssetValue = parseDouble(value(TOTAL_EVALUATION));
        double deposit         = parseDouble(value(DEPOSIT));
        List<Holding> holdings = rows.stream()
                .filter(r -> parseInt(r.quantity()) > 0)
                .map(r -> new Holding(r.stockCode(), parseInt(r.quantity()),
                        parseDouble(r.avgPrice()), parseDouble(r.currentPrice())))
                .toList();
        EquityCrossCheck check = EquityCrossCheck.evaluate(
                totalAssetValue, optionalDouble(value(SETTLED_CASH)), holdings);
        return new BalanceSnapshot(totalAssetValue, deposit, holdings, check);
    }

    /** 로그용 원본 한 줄 — "output2={키=값, ...} output1=[{pdno=..., ...}, ...]" */
    String describe() {
        String summaryText = summary.entrySet().stream()
                .map(e -> e.getKey() + "=" + masked(e.getKey(), e.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
        String rowsText = rows.stream()
                .map(KisBalanceRaw::describeRow)
                .collect(Collectors.joining(", ", "[", "]"));
        return "output2=" + summaryText + " output1=" + rowsText;
    }

    private static String describeRow(Row r) {
        if (r == null) return "null";
        return String.format("{pdno=%s, hldg_qty=%s, pchs_avg_pric=%s, prpr=%s, evlu_amt=%s}",
                r.stockCode(), r.quantity(), r.avgPrice(), r.currentPrice(), r.evaluationAmount());
    }

    private static String masked(String key, String value) {
        if (value == null) return "null";
        return SENSITIVE_KEY.matcher(key.toLowerCase(Locale.ROOT)).find() ? "***" : value;
    }

    // ── 파싱 (예전 KisBalanceClient와 같은 규칙) ─────────────────────────────

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

    /** 대조식 전용 — 칸이 없거나 숫자가 아니면 "모른다"(empty). 0으로 메우면 거짓 불일치가 난다 */
    private static OptionalDouble optionalDouble(String s) {
        if (s == null || s.isBlank()) return OptionalDouble.empty();
        try {
            return OptionalDouble.of(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }
}
