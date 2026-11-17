package com.trading.market;

import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * KIS 오류 응답(429/5xx) 본문을 로그에 남기기 위해 안전하게 다듬는다 (2026-09-30 신설).
 *
 * <p>왜 필요한가: 2026-09-22 하루에 HTTP 500이 379건인데 본문을 한 줄도 남기지 않아
 * "왜 500인지"가 미규명으로 남았다(_workspace/25_ops_safemode-flapping-diagnosis.md §5).
 * 고치는 장치가 아니라 다음에 진단할 수 있게 하는 장치다.
 *
 * <p>두 가지를 반드시 한다:
 * <ol>
 *   <li><b>비밀값 가리기</b> — 응답에 계좌번호가 되돌아오거나 서버가 요청을 되뿜을 수 있다.
 *       appkey·secretkey·계좌번호·토큰을 {@value #MASK}로 바꾼다. 자르기보다 <b>먼저</b> 가린다
 *       (먼저 자르면 비밀값이 반토막으로 새어 나갈 수 있다).</li>
 *   <li><b>길이 자르기</b> — {@value #MAX_CHARS}자. 로그 파일이 오류 본문으로 부풀지 않게.</li>
 * </ol>
 */
final class KisErrorBodySnippet {

    static final int MAX_CHARS = 200;
    static final String MASK = "***";

    private KisErrorBodySnippet() {
    }

    /**
     * 응답 본문을 읽어 가리고 자른 한 줄을 돌려준다.
     * 본문을 못 읽어도 예외를 던지지 않는다 — 로그 한 줄 때문에 호출이 실패하면 안 된다.
     * (호출 측이 넘기는 응답은 이미 버퍼링된 것이므로 여기서 읽어도 본문이 소진되지 않는다)
     */
    static String of(ClientHttpResponse response, List<String> secretsToMask) {
        try {
            return redact(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8), secretsToMask);
        } catch (IOException e) {
            return "(본문 읽기 실패: " + e.getMessage() + ")";
        }
    }

    /** 비밀값을 가리고 한 줄로 접어 {@value #MAX_CHARS}자까지만 남긴다. */
    static String redact(String rawBody, List<String> secretsToMask) {
        if (rawBody == null || rawBody.isBlank()) return "(본문 없음)";

        String masked = rawBody.replaceAll("\\s+", " ").trim();
        for (String secret : secretsToMask) {
            if (secret != null && !secret.isBlank()) {
                masked = masked.replace(secret, MASK);
            }
        }
        return masked.length() <= MAX_CHARS ? masked : masked.substring(0, MAX_CHARS) + "…(생략)";
    }
}
