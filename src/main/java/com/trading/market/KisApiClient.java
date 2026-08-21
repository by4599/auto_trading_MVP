package com.trading.market;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.NotificationService;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 한국투자증권 Open API 공통 클라이언트.
 *
 * - 토큰 발급/캐싱: POST /oauth2/tokenP, 만료 5분 전 자동 갱신 —
 *   실패 시 3회 재시도(1s→2s→4s), 모두 실패하면 SAFE_MODE 전환 (OPERATIONS §4)
 * - 401 수신 시 토큰 재발급 후 1회 자동 재시도
 * - 429/5xx·타임아웃은 최대 3회 재시도, 연속 실패가 임계치를 넘으면 SAFE_MODE 전환
 * - getClient()가 반환하는 RestClient에 인터셉터가 붙어있어
 *   호출 측은 .get() / .post() 를 그대로 쓰면 된다
 */
@Component
public class KisApiClient {

    private static final Logger log = LoggerFactory.getLogger(KisApiClient.class);
    private static final long TOKEN_BUFFER_SECONDS = 300;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int TOKEN_REFRESH_MAX_ATTEMPTS = 3;
    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 3;
    private static final int RESUME_SUCCESS_THRESHOLD = 2;

    private final KisProperties props;
    private final TradingStatusManager statusManager;
    private final NotificationService notifier;
    private final KisRateLimiter rateLimiter;
    // 토큰 발급 전용 — 인터셉터 없음 (순환 참조 방지)
    private volatile RestClient tokenClient;
    // 모든 API 호출용 — 인터셉터(인증 주입 + 401 재시도)가 붙어있음
    private volatile RestClient apiClient;
    private final AtomicReference<TokenHolder> tokenRef = new AtomicReference<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);
    // 이 클라이언트가 "연결 끊김"으로 SAFE_MODE를 걸었을 때만 true — 연결 회복 시 자동복귀 대상 판별용.
    // (앱 재시작·사람 조작으로 들어간 SAFE_MODE는 자동복귀시키지 않는다)
    private final AtomicBoolean pausedByConnectionLoss = new AtomicBoolean(false);

    @Autowired
    public KisApiClient(KisProperties props, TradingStatusManager statusManager, NotificationService notifier,
                        KisRateLimiter rateLimiter) {
        this.props = props;
        this.statusManager = statusManager;
        this.notifier = notifier;
        this.rateLimiter = rateLimiter;
        // 자격증명 미설정 시 placeholder — 실제 요청 시 getBearerToken()에서 명시적 오류 발생
        buildClients(props.isConfigured() ? props.getBaseUrl() : "https://placeholder.invalid");
    }

    /**
     * 테스트 전용 — MockRestServiceServer로 바인딩한 RestClient.Builder를 직접 주입한다.
     * apiBuilder에는 프로덕션과 동일하게 이 인스턴스의 intercept()를 붙여야 401/429/5xx
     * 재시도·연속실패 집계 로직이 실제로 검증된다 (완성된 RestClient를 그냥 넘기면
     * 인터셉터가 빠진 채로 조립돼 아무 것도 검증하지 못한다).
     */
    KisApiClient(KisProperties props, TradingStatusManager statusManager, NotificationService notifier,
                 KisRateLimiter rateLimiter, RestClient.Builder tokenBuilder, RestClient.Builder apiBuilder) {
        this.props = props;
        this.statusManager = statusManager;
        this.notifier = notifier;
        this.rateLimiter = rateLimiter;
        this.tokenClient = tokenBuilder.build();
        this.apiClient = apiBuilder.requestInterceptor(this::intercept).build();
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

    /** 자격증명 변경 후 RestClient·토큰 캐시를 즉시 재초기화한다. 재시작 불필요. */
    public synchronized void reconfigure() {
        if (!props.isConfigured()) return;
        buildClients(props.getBaseUrl());
        this.tokenRef.set(null);
        log.info("KisApiClient 재설정 완료 — baseUrl={}", props.getBaseUrl());
    }

    private void buildClients(String base) {
        // 네트워크 장애 시 빠른 실패: 응답 지연이 1초 루프를 계속 붙잡지 않도록 타임아웃 명시 (OPERATIONS §4)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);

        this.tokenClient = RestClient.builder()
                .baseUrl(base)
                .requestFactory(factory)
                .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.apiClient = RestClient.builder()
                .baseUrl(base)
                .requestFactory(factory)
                .requestInterceptor(this::intercept)
                .build();
    }

    // ── 토큰 관리 ─────────────────────────────────────────────────────────────

    String getBearerToken() {
        if (!props.isConfigured()) {
            throw new IllegalStateException("KIS 자격증명 미설정 — http://localhost:8080 에서 입력 후 재시작하세요");
        }
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

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= TOKEN_REFRESH_MAX_ATTEMPTS; attempt++) {
            try {
                log.info("KIS OAuth 토큰 발급 요청 ({}/{}차 시도)", attempt, TOKEN_REFRESH_MAX_ATTEMPTS);
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
                consecutiveFailures.set(0);
                log.info("KIS OAuth 토큰 발급 완료, 만료: {}", expiresAt);
                return next.token();
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("KIS 토큰 발급 실패 ({}/{}차 시도) — {}", attempt, TOKEN_REFRESH_MAX_ATTEMPTS, e.getMessage());
                if (attempt < TOKEN_REFRESH_MAX_ATTEMPTS) {
                    sleepQuietly((1L << (attempt - 1)) * 1_000L);
                }
            }
        }

        triggerSafeModeIfRunning("KIS 토큰 발급 " + TOKEN_REFRESH_MAX_ATTEMPTS + "회 연속 실패 — 시세를 못 보는 채로 매매하지 않습니다");
        throw new IllegalStateException(
                "KIS 토큰 발급 실패 (" + TOKEN_REFRESH_MAX_ATTEMPTS + "회 재시도 소진)", lastError);
    }

    // ── 인터셉터 ──────────────────────────────────────────────────────────────

    /**
     * 모든 API 요청에 인증 헤더를 주입하고, 오류 유형에 따라 재시도한다.
     *   401 → 토큰 재발급 후 1회 재시도
     *   429/5xx → 지수 백오프 최대 3회 재시도 (1s → 2s → 4s)
     *   위 재시도를 모두 소진하고도 실패(타임아웃 포함)면 연속 실패로 집계 —
     *   임계치를 넘으면 SAFE_MODE 전환 (OPERATIONS §4)
     */
    private ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {

        ClientHttpResponse response = buffer(executeTracked(request, body, execution));
        int status = response.getStatusCode().value();

        // 401(표준) 또는 토큰 만료 → 재발급 후 1회만 재시도.
        // KIS는 토큰 만료를 401이 아니라 HTTP 500 본문(EGW00123 "만료된 token")으로 돌려주므로
        // 본문까지 확인해야 한다. 이걸 놓치면 죽은 토큰으로 재시도만 하다 SAFE_MODE에 갇힌다.
        if (status == 401 || isTokenExpiredResponse(status, response)) {
            log.warn("인증 만료 감지(status={}) — 토큰 강제 재발급 후 재시도", status);
            tokenRef.set(null);
            response = buffer(executeTracked(request, body, execution));
            recordOutcome(response.getStatusCode().value());
            return response;
        }

        // 429 / 5xx: 한투 서버 과부하, 장 시작 직후 응답 지연 등
        for (int attempt = 1; attempt <= 3 && (status == 429 || status / 100 == 5); attempt++) {
            response.close();
            long waitMs = (1L << (attempt - 1)) * 1_000L; // 1s, 2s, 4s
            log.warn("HTTP {} 수신 — {}ms 후 재시도 ({}/3)", status, waitMs, attempt);
            sleepQuietly(waitMs);
            response = buffer(executeTracked(request, body, execution));
            status  = response.getStatusCode().value();
        }
        recordOutcome(status);
        return response;
    }

    /** KIS의 토큰 만료 응답(HTTP 5xx 본문에 EGW00123/"만료된 token")을 401과 동등하게 감지한다. */
    private boolean isTokenExpiredResponse(int status, ClientHttpResponse response) {
        if (status / 100 != 5) return false;
        try {
            String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
            return body.contains("EGW00123") || body.contains("만료된 token");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 응답 본문을 바이트로 미리 읽어 재판독 가능한 응답으로 감싼다.
     * 인터셉터가 본문(토큰 만료 여부)을 들여다봐도 호출 측이 그대로 다시 읽을 수 있게 한다.
     */
    private static ClientHttpResponse buffer(ClientHttpResponse original) throws IOException {
        byte[] bytes = StreamUtils.copyToByteArray(original.getBody());
        HttpStatusCode statusCode = original.getStatusCode();
        String statusText = original.getStatusText();
        HttpHeaders headers = HttpHeaders.readOnlyHttpHeaders(original.getHeaders());
        original.close();
        return new ClientHttpResponse() {
            @Override public HttpStatusCode getStatusCode() { return statusCode; }
            @Override public String getStatusText()          { return statusText; }
            @Override public HttpHeaders getHeaders()        { return headers; }
            @Override public InputStream getBody()           { return new ByteArrayInputStream(bytes); }
            @Override public void close()                    { /* 버퍼 — 닫을 자원 없음 */ }
        };
    }

    private ClientHttpResponse executeTracked(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        rateLimiter.acquire();  // 초당 한도 준수 — 모든 물리 호출(최초·재시도 포함)이 예산 슬롯을 받는다
        try {
            return execution.execute(withAuth(request), body);
        } catch (IOException e) {
            // 연결/읽기 타임아웃 등 — 응답 자체를 못 받은 경우도 실패로 집계
            recordFailure();
            throw e;
        }
    }

    private void recordOutcome(int status) {
        if (status / 100 == 2) {
            recordSuccess();
        } else {
            recordFailure();
        }
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
        int successes = consecutiveSuccesses.incrementAndGet();
        maybeAutoResume(successes);
    }

    private void recordFailure() {
        consecutiveSuccesses.set(0);
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= CONSECUTIVE_FAILURE_THRESHOLD) {
            triggerSafeModeIfRunning(String.format(
                    "KIS API 연속 %d회 호출 실패 — 시세를 못 보는 채로 매매하지 않습니다", failures));
        }
    }

    /** 연결 끊김으로 스스로 멈춘다. 연결 회복 시 자동복귀 대상으로 표시(pausedByConnectionLoss). */
    private void triggerSafeModeIfRunning(String reason) {
        if (statusManager.getCurrentMode() == TradingMode.RUNNING) {
            statusManager.changeMode(TradingMode.SAFE_MODE);
            pausedByConnectionLoss.set(true);
            notifier.sendCritical("🚨 [자동정지] 증권사 연결이 끊겨 매매를 멈춥니다(신규 매수 중지) — " + reason);
        }
    }

    /**
     * 연결이 회복되면(연속 성공 {@value #RESUME_SUCCESS_THRESHOLD}회) 스스로 멈춰 있던 경우에 한해
     * 자동으로 RUNNING 복귀 + 알림. 앱 재시작·사람 조작으로 들어간 SAFE_MODE는 대상이 아니다.
     */
    private void maybeAutoResume(int consecutiveSuccessCount) {
        if (!pausedByConnectionLoss.get()) return;
        if (statusManager.getCurrentMode() != TradingMode.SAFE_MODE) {
            // 그 사이 사람이 다른 상태로 바꿨으면 자동복귀 권한을 내려놓는다
            pausedByConnectionLoss.set(false);
            return;
        }
        if (consecutiveSuccessCount >= RESUME_SUCCESS_THRESHOLD
                && pausedByConnectionLoss.compareAndSet(true, false)) {
            statusManager.changeMode(TradingMode.RUNNING);
            notifier.sendCritical("✅ [자동재개] 증권사 연결이 회복돼 매매를 다시 시작합니다");
        }
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
        return new HttpRequest() {
            @Override public HttpMethod getMethod() { return original.getMethod(); }
            @Override public URI getURI()            { return original.getURI(); }
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(original.getHeaders());
                headers.set("Authorization", "Bearer " + getBearerToken());
                headers.set("appkey",   props.getAppkey());
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
