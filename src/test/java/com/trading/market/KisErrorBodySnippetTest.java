package com.trading.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 500 본문 로깅의 안전장치 검증 (2026-09-30).
 * 본문에 계좌번호·토큰이 섞일 수 있으므로 "가리기 → 자르기" 순서가 지켜져야 한다.
 */
@DisplayName("KisErrorBodySnippet — 오류 본문 로깅: 비밀값 가리기 · 길이 자르기")
class KisErrorBodySnippetTest {

    private static final List<String> SECRETS =
            List.of("PS1234appkey5678", "SeCrEtKeY0987654321", "50000000-01", "live-access-token");

    @Test
    @DisplayName("appkey·secretkey·계좌번호·토큰이 본문에 있으면 모두 가린다")
    void masks_every_credential() {
        String body = "{\"rt_cd\":\"1\",\"msg1\":\"오류\",\"appkey\":\"PS1234appkey5678\","
                + "\"appsecret\":\"SeCrEtKeY0987654321\",\"CANO\":\"50000000-01\","
                + "\"authorization\":\"Bearer live-access-token\"}";

        String snippet = KisErrorBodySnippet.redact(body, SECRETS);

        assertThat(snippet).doesNotContain("PS1234appkey5678")
                .doesNotContain("SeCrEtKeY0987654321")
                .doesNotContain("50000000-01")
                .doesNotContain("live-access-token");
        assertThat(snippet).contains(KisErrorBodySnippet.MASK);
        assertThat(snippet).contains("rt_cd");   // 진단에 필요한 부분은 남는다
    }

    @Test
    @DisplayName("200자를 넘으면 자른다 — 자르기보다 가리기가 먼저라 반토막 유출이 없다")
    void truncates_after_masking() {
        String body = "x".repeat(190) + "50000000-01" + "y".repeat(190);

        String snippet = KisErrorBodySnippet.redact(body, SECRETS);

        assertThat(snippet).hasSize(KisErrorBodySnippet.MAX_CHARS + "…(생략)".length());
        assertThat(snippet).doesNotContain("50000000-01")
                .doesNotContain("50000000")   // 잘린 조각으로도 새지 않는다
                .contains(KisErrorBodySnippet.MASK);
    }

    @Test
    @DisplayName("빈 비밀값은 무시한다 — 빈 문자열을 치환하면 로그 전체가 ***가 된다")
    void ignores_blank_secrets() {
        String snippet = KisErrorBodySnippet.redact("{\"msg1\":\"서버 오류\"}", List.of("", "  "));

        assertThat(snippet).isEqualTo("{\"msg1\":\"서버 오류\"}");
    }

    @Test
    @DisplayName("본문이 비었으면 표시만 남긴다")
    void empty_body_is_labelled() {
        assertThat(KisErrorBodySnippet.redact("", SECRETS)).isEqualTo("(본문 없음)");
        assertThat(KisErrorBodySnippet.redact(null, SECRETS)).isEqualTo("(본문 없음)");
    }

    @Test
    @DisplayName("여러 줄 본문은 한 줄로 접는다")
    void folds_multiline_body_into_one_line() {
        String snippet = KisErrorBodySnippet.redact("{\n  \"rt_cd\": \"1\"\n}", SECRETS);

        assertThat(snippet).isEqualTo("{ \"rt_cd\": \"1\" }");
    }

    @Test
    @DisplayName("응답에서 직접 읽어도 같은 결과 — 버퍼링된 본문이라 소진되지 않는다")
    void reads_from_response_body() {
        MockClientHttpResponse response = new MockClientHttpResponse(
                "{\"CANO\":\"50000000-01\"}".getBytes(StandardCharsets.UTF_8),
                HttpStatus.INTERNAL_SERVER_ERROR);

        String snippet = KisErrorBodySnippet.of(response, SECRETS);

        assertThat(snippet).isEqualTo("{\"CANO\":\"" + KisErrorBodySnippet.MASK + "\"}");
    }
}
