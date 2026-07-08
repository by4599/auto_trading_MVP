package com.trading.market;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpRequestWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 한국투자증권 Open API 공통 클라이언트.
 *
 * - 토큰 발급/캐싱: POST /oauth2/tokenP, 만료 5분 전 자동 갱신
 * - 401 수신 시 토큰 재발급 후 1회 자동 재시도
 * - getClient()가 반환하는 RestClient에 인터셉터가 붙어있어
 *   호출 측은 .get() / .post() 를 그대로 쓰면 된다
 */
@Component
public class KisApiClient {

    private static final Logger log = LoggerFactory.getLogger(KisApiClient.class);
    private static final long TOKEN_BUFFER_SECONDS = 300;

    private final KisProperties props;
    // 토큰 발급 전용 — 인터셉터 없음 (순환 참조 방지)
    private final RestClient tokenClient;
    // 모든 API 호출용 — 인터셉터(인증 주입 + 401 재시도)가 붙어있음
    private final RestClient apiClient;
    private final AtomicReference<TokenHolder> tokenRef = new AtomicReference<>();

    public KisApiClient(KisProperties props) {
        this.props = props;
        this.tokenClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.apiClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestInterceptor(this::intercept)
                .build();
    }

    /**
     * 인증 인터셉터가 적용된 RestClient를 반환한다.
     * 호출 측에서 .get() / .post() 를 직접 선택해서 사용한다.
     */
    public RestClient getClient() {
        return apiClient;
    }

    public KisProperties getProps() {
        return props;
    }

    // ── 토큰 관리 ─────────────────────────────────────────────────────────────

    String getBearerToken() {
        TokenHolder holder = tokenRef.get();
        if (holder != null && !holder.isExpiredSoon()) {
            return holder.token();
        }
        return refreshToken();
    }

    private synchronized String refreshToken() {
        TokenHolder current = tokenRef.get();
        if (current != null && !current.isExpiredSoon()) {
            return current.token();
        }
        log.info("KIS OAuth 토큰 발급 요청");

        TokenResponse resp = tokenClient.post()
                .uri("/oauth2/tokenP")
                .body(Map.of(
                        "grant_type", "client_credentials",
                        "appkey",     props.getAppkey(),
                        "appsecret",  props.getSecretkey()
                ))
                .retrieve()
                .body(TokenResponse.class);

        if (resp == null || resp.accessToken() == null) {
            throw new IllegalStateException("KIS 토큰 발급 실패: 응답이 비어있습니다");
        }

        Instant expiresAt = Instant.now().plusSeconds(resp.expiresIn());
        TokenHolder next = new TokenHolder(resp.accessToken(), expiresAt);
        tokenRef.set(next);
        log.info("KIS OAuth 토큰 발급 완료, 만료: {}", expiresAt);
        return next.token();
    }

    // ── 인터셉터 ──────────────────────────────────────────────────────────────

    /**
     * 모든 API 요청에 인증 헤더를 주입하고, 오류 유형에 따라 재시도한다.
     *   401 → 토큰 재발급 후 1회 재시도
     *   429/5xx → 지수 백오프 최대 3회 재시도 (1s → 2s → 4s)
     */
    private ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {

        ClientHttpResponse response = execution.execute(withAuth(request), body);
        int status = response.getStatusCode().value();

        // 401: 토큰 만료 → 재발급 후 1회만 재시도
        if (status == 401) {
            response.close();
            log.warn("401 수신 — 토큰 강제 재발급 후 재시도");
            tokenRef.set(null);
            return execution.execute(withAuth(request), body);
        }

        // 429 / 5xx: 한투 서버 과부하, 장 시작 직후 응답 지연 등
        for (int attempt = 1; attempt <= 3 && (status == 429 || status / 100 == 5); attempt++) {
            response.close();
            long waitMs = (1L << (attempt - 1)) * 1_000L; // 1s, 2s, 4s
            log.warn("HTTP {} 수신 — {}ms 후 재시도 ({}/3)", status, waitMs, attempt);
            sleepQuietly(waitMs);
            response = execution.execute(withAuth(request), body);
            status  = response.getStatusCode().value();
        }
        return response;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 원본 요청의 헤더를 모두 유지하면서 인증 헤더를 추가한 래퍼를 반환한다.
     * 매번 새 인스턴스를 만들어 401 재시도 시에도 갱신된 토큰이 반영된다.
     */
    private HttpRequest withAuth(HttpRequest original) {
        return new HttpRequestWrapper(original) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(original.getHeaders());
                headers.set("Authorization", "Bearer " + getBearerToken());
                headers.set("appkey",  props.getAppkey());
                headers.set("appsecret", props.getSecretkey());
                return headers;
            }
        };
    }

    // ── 내부 타입 ──────────────────────────────────────────────────────────────

    private record TokenHolder(String token, Instant expiresAt) {
        boolean isExpiredSoon() {
            return Instant.now().plusSeconds(TOKEN_BUFFER_SECONDS).isAfter(expiresAt);
        }
    }

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in")   long   expiresIn
    ) {}
}
