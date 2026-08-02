package com.trading.market;

import com.trading.NotificationService;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * KIS API 장애 대응 (OPERATIONS §4) — 토큰 발급 재시도, 연속 호출 실패 시 SAFE_MODE 전환.
 * 실제 네트워크 대신 MockRestServiceServer로 tokenClient/apiClient 각각의
 * RestClient 호출을 검증한다.
 */
@DisplayName("KisApiClient — 토큰 재시도 · 연속 실패 SAFE_MODE 전환")
class KisApiClientTest {

    private static final String TOKEN_JSON = "{\"access_token\":\"test-token\",\"expires_in\":86400}";

    private KisProperties props;
    private TradingStatusManager statusManager;
    private NotificationService notifier;
    private RestClient.Builder tokenBuilder;
    private RestClient.Builder apiBuilder;
    private MockRestServiceServer tokenMock;
    private MockRestServiceServer apiMock;

    @BeforeEach
    void setUp() {
        props = configuredProps();
        statusManager = new TradingStatusManager();
        notifier = mock(NotificationService.class);

        tokenBuilder = RestClient.builder();
        tokenMock = MockRestServiceServer.bindTo(tokenBuilder).build();

        apiBuilder = RestClient.builder();
        apiMock = MockRestServiceServer.bindTo(apiBuilder).build();
    }

    private static KisProperties configuredProps() {
        KisProperties p = new KisProperties();
        p.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        p.setAppkey("test-appkey");
        p.setSecretkey("test-secretkey");
        p.setAccountNo("50000000-01");
        return p;
    }

    private KisApiClient sut() {
        // 테스트는 한도 대기가 무의미하도록 빠른 레이트리미터(10000/초)를 쓴다
        return new KisApiClient(props, statusManager, notifier,
                new KisRateLimiter(10_000), tokenBuilder, apiBuilder);
    }

    // ── 토큰 발급 재시도 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("토큰 발급 3회 연속 실패 → SAFE_MODE 전환 + 알림, 예외 전파")
    void token_refresh_fails_three_times_triggers_safe_mode() {
        tokenMock.expect(ExpectedCount.times(3), requestTo("/oauth2/tokenP"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        KisApiClient sut = sut();

        assertThatThrownBy(sut::getBearerToken).isInstanceOf(IllegalStateException.class);
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.SAFE_MODE);
        verify(notifier, atLeastOnce()).sendCritical(anyString());
        tokenMock.verify();
    }

    @Test
    @DisplayName("토큰 발급 1회차 실패 후 2회차 성공 → SAFE_MODE 전환 없이 토큰 반환")
    void token_refresh_succeeds_on_retry() {
        tokenMock.expect(ExpectedCount.once(), requestTo("/oauth2/tokenP"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        tokenMock.expect(ExpectedCount.once(), requestTo("/oauth2/tokenP"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));

        String token = sut().getBearerToken();

        assertThat(token).isEqualTo("test-token");
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.RUNNING);
        verify(notifier, never()).sendCritical(anyString());
        tokenMock.verify();
    }

    // ── 연속 API 호출 실패 ────────────────────────────────────────────────────

    @Test
    @DisplayName("연속 3회 API 호출 실패(4xx) → SAFE_MODE 전환 + 알림")
    void three_consecutive_api_failures_trigger_safe_mode() {
        tokenMock.expect(ExpectedCount.once(), requestTo("/oauth2/tokenP"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        apiMock.expect(ExpectedCount.times(3), requestTo("/test"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        KisApiClient sut = sut();
        for (int i = 0; i < 3; i++) {
            try {
                sut.getClient().get().uri("/test").retrieve().toBodilessEntity();
            } catch (Exception ignored) {
                // RestClient.retrieve()는 4xx에서 예외를 던진다 — intercept()는 이미 실행된 뒤라 무관
            }
        }

        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.SAFE_MODE);
        verify(notifier, atLeastOnce()).sendCritical(anyString());
        apiMock.verify();
    }

    @Test
    @DisplayName("성공 응답이 섞이면 연속 실패 카운터가 리셋된다 — 3회 미만이면 SAFE_MODE 전환 없음")
    void success_resets_consecutive_failure_counter() {
        tokenMock.expect(ExpectedCount.once(), requestTo("/oauth2/tokenP"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        apiMock.expect(ExpectedCount.once(), requestTo("/test"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        apiMock.expect(ExpectedCount.once(), requestTo("/test"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess());
        apiMock.expect(ExpectedCount.once(), requestTo("/test"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        KisApiClient sut = sut();
        for (int i = 0; i < 3; i++) {
            try {
                sut.getClient().get().uri("/test").retrieve().toBodilessEntity();
            } catch (Exception ignored) {
            }
        }

        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.RUNNING);
        verify(notifier, never()).sendCritical(anyString());
        apiMock.verify();
    }

    // ── 토큰 만료가 HTTP 500(EGW00123)로 오는 경우 ───────────────────────────────

    @Test
    @DisplayName("토큰 만료가 HTTP 500(EGW00123)로 와도 재발급 후 재시도 — SAFE_MODE 안 걸림")
    void expired_token_as_500_refreshes_and_retries() {
        // 최초 발급 + 만료 감지 후 재발급 = 토큰 2회
        tokenMock.expect(ExpectedCount.once(), requestTo("/oauth2/tokenP"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        tokenMock.expect(ExpectedCount.once(), requestTo("/oauth2/tokenP"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        // 첫 호출: 토큰 만료를 401이 아니라 500 본문으로 반환 (KIS 실제 동작)
        apiMock.expect(ExpectedCount.once(), requestTo("/test"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"기간이 만료된 token 입니다.\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        // 재발급 후 재시도: 성공
        apiMock.expect(ExpectedCount.once(), requestTo("/test"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess());

        KisApiClient sut = sut();
        sut.getClient().get().uri("/test").retrieve().toBodilessEntity();

        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.RUNNING);
        verify(notifier, never()).sendCritical(anyString());
        apiMock.verify();
        tokenMock.verify();
    }

    // ── 연결 회복 시 자동 재개 ──────────────────────────────────────────────────

    @Test
    @DisplayName("연결 끊김으로 SAFE_MODE가 된 뒤, 다시 연결되면 자동으로 RUNNING 복귀 + 알림")
    void auto_resumes_to_running_after_connection_recovers() {
        tokenMock.expect(ExpectedCount.once(), requestTo("/oauth2/tokenP"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        // 3회 실패 → SAFE_MODE
        apiMock.expect(ExpectedCount.times(3), requestTo("/test"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        // 2회 성공 → 자동 RUNNING 복귀
        apiMock.expect(ExpectedCount.times(2), requestTo("/test"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess());

        KisApiClient sut = sut();
        for (int i = 0; i < 5; i++) {
            try {
                sut.getClient().get().uri("/test").retrieve().toBodilessEntity();
            } catch (Exception ignored) {
            }
        }

        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.RUNNING);
        // 멈춤 알림 1회 + 재개 알림 1회 = 정확히 2회
        verify(notifier, times(2)).sendCritical(anyString());
        apiMock.verify();
    }

    @Test
    @DisplayName("연결 끊김이 아닌(사람/재시작) SAFE_MODE는 연결이 살아있어도 자동복귀하지 않는다")
    void does_not_auto_resume_when_safe_mode_not_from_connection_loss() {
        statusManager.changeMode(TradingMode.SAFE_MODE); // 재시작/사람 조작으로 진입한 SAFE_MODE 시뮬레이션
        tokenMock.expect(ExpectedCount.once(), requestTo("/oauth2/tokenP"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        apiMock.expect(ExpectedCount.times(3), requestTo("/test"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess());

        KisApiClient sut = sut();
        for (int i = 0; i < 3; i++) {
            sut.getClient().get().uri("/test").retrieve().toBodilessEntity();
        }

        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.SAFE_MODE); // 그대로 유지
        verify(notifier, never()).sendCritical(anyString());
        apiMock.verify();
    }
}
